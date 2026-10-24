#!/usr/bin/env python3
"""Own TLS/H2 endpoints test the experimental byte engine on a dev emulator.

Requires h2==4.3.0 in the development interpreter and cryptography in the host
interpreter used by make_certs.py. No public sites or reader state are accessed.
Native library and driver must first be built with build_public_read.py.
"""
import argparse
import ctypes
import ctypes.util
import datetime
import gzip
import hashlib
import json
from pathlib import Path
import shlex
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import zlib
from h2.config import H2Configuration
from h2.connection import H2Connection
from h2.events import RequestReceived, RemoteSettingsChanged, WindowUpdated

BODY = b'One useful public document.\n' * 17
ROOT = Path('/run/media/red/RedtopStorage/DarwinBuild/LiberBuild')


def compress(kind, data):
    if kind == 'gzip':
        return gzip.compress(data)
    if kind == 'deflate':
        return zlib.compress(data)
    if kind == 'br':
        lib = ctypes.CDLL(ctypes.util.find_library('brotlienc'))
        fn = lib.BrotliEncoderCompress
        fn.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_size_t, ctypes.c_void_p, ctypes.POINTER(ctypes.c_size_t), ctypes.c_void_p]
        fn.restype = ctypes.c_int
        size = ctypes.c_size_t(len(data) * 2 + 1024)
        out = ctypes.create_string_buffer(size.value)
        assert fn(5, 22, 0, len(data), data, ctypes.byref(size), out)
        return out.raw[:size.value]
    lib = ctypes.CDLL(ctypes.util.find_library('zstd'))
    fn = lib.ZSTD_compress
    fn.argtypes = [ctypes.c_void_p, ctypes.c_size_t, ctypes.c_void_p, ctypes.c_size_t, ctypes.c_int]
    fn.restype = ctypes.c_size_t
    out = ctypes.create_string_buffer(len(data) * 2 + 1024)
    size = fn(out, len(out), data, len(data), 3)
    assert size <= len(out)
    return out.raw[:size]


def hello(data):
    def u16(b, i):
        return int.from_bytes(b[i:i+2], 'big')
    def grease(v):
        return v in {0x0a0a + i * 0x1010 for i in range(16)}
    assert data[0] == 22 and data[5] == 1
    b = data[9:]
    pos = 34
    pos += 1 + b[pos]
    n = u16(b, pos)
    pos += 2
    ciphers = [u16(b, i) for i in range(pos, pos+n, 2)]
    pos += n
    pos += 1 + b[pos]
    n = u16(b, pos)
    pos += 2
    end = pos + n
    extensions, groups, alpn = [], [], []
    sni = None
    padding = None
    while pos < end:
        kind, n = u16(b, pos), u16(b, pos+2)
        v = b[pos+4:pos+4+n]
        pos += 4+n
        extensions.append(kind)
        if kind == 10:
            groups = [u16(v, i) for i in range(2, len(v), 2)]
        if kind == 16:
            at = 2
            while at < len(v):
                size = v[at]
                alpn.append(v[at+1:at+1+size].decode())
                at += size + 1
        if kind == 0:
            sni = v[5:5+u16(v, 3)].decode()
        if kind == 21:
            assert not any(v)
            padding = len(v)
    return {'ciphers': [v for v in ciphers if not grease(v)], 'extensions': [v for v in extensions if not grease(v)], 'groups': [v for v in groups if not grease(v)], 'alpn': alpn, 'sni': sni, 'padding': padding}


class Server:
    def __init__(self, folder, mode):
        self.mode = mode
        self.sock = socket.socket()
        self.sock.bind(('127.0.0.1', 0))
        self.sock.listen(1)
        self.sock.settimeout(5)
        self.port = self.sock.getsockname()[1]
        self.record = {}
        self.active = None
        self.context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        self.context.load_cert_chain(folder/'trusted.pem', folder/'trusted.key')
        self.context.set_alpn_protocols(['http/1.1'] if mode == 'http1' else ['h2'])
        if mode == 'tls12':
            self.context.maximum_version = ssl.TLSVersion.TLSv1_2
        self.thread = threading.Thread(target=self.run, daemon=True)
        self.thread.start()

    def run(self):
        try:
            raw, _ = self.sock.accept()
            self.active = raw
            raw.settimeout(4)
            deadline = time.monotonic() + 3
            while True:
                first = raw.recv(32768, socket.MSG_PEEK)
                if len(first) >= 5 and len(first) >= 5 + int.from_bytes(first[3:5], 'big'):
                    break
                if time.monotonic() > deadline:
                    raise TimeoutError('fragmented ClientHello incomplete')
                time.sleep(0.001)
            self.record['hello'] = hello(first)
            with self.context.wrap_socket(raw, server_side=True) as peer:
                self.active = peer
                self.record['tls'] = peer.version()
                if self.mode == 'http1':
                    self.record['http1Bytes'] = len(peer.recv(4096))
                    return
                conn = H2Connection(config=H2Configuration(client_side=False, header_encoding='utf-8'))
                conn.initiate_connection()
                peer.sendall(conn.data_to_send())
                while True:
                    data = peer.recv(32768)
                    if not data:
                        return
                    for event in conn.receive_data(data):
                        if isinstance(event, RemoteSettingsChanged):
                            self.record['settings'] = [(int(k), v.new_value) for k, v in event.changed_settings.items()]
                        elif isinstance(event, WindowUpdated) and event.stream_id == 0:
                            self.record.setdefault('windows', []).append(event.delta)
                        elif isinstance(event, RequestReceived):
                            self.record['request'] = event.headers
                            self.respond(conn, peer, event.stream_id)
                            return
                    pending = conn.data_to_send()
                    if pending:
                        peer.sendall(pending)
        except Exception as error:
            self.record['serverError'] = type(error).__name__ + ': ' + str(error)

    def respond(self, conn, peer, stream):
        mode = self.mode
        body = BODY
        headers = [(':status', '302' if mode == 'redirect' else '204' if mode == 'empty204' else '200'), ('content-type', 'text/plain')]
        if mode == 'informational':
            conn.send_headers(stream, [(':status', '103'), ('link', '</style>; rel=preload')])
        kind = next((k for k in ['gzip', 'deflate', 'br', 'zstd'] if mode.startswith(k)), None)
        if kind:
            body = compress(kind, b'x' * 262144 if mode.endswith('limit') else body)
            if mode.endswith('truncated'):
                body = body[:-3]
            headers.append(('content-encoding', ' GZIP ' if mode == 'gzip-case' else kind))
        if mode == 'redirect':
            headers.append(('location', 'https://elsewhere.invalid/next'))
        if mode == 'headers-limit':
            headers += [('x-large-' + str(i), 'v' * 4000) for i in range(12)]
        if mode == 'bad-length':
            headers.append(('content-length', str(len(body) + 17)))
        if mode == 'unsupported-encoding':
            headers.append(('content-encoding', 'unknown'))
        if mode == 'duplicate-encoding':
            headers += [('content-encoding', 'gzip'), ('content-encoding', 'br')]
        if mode == 'empty-encoding':
            headers.append(('content-encoding', ''))
        if mode == 'goaway':
            conn.close_connection(last_stream_id=0)
            peer.sendall(conn.data_to_send())
            self.drain(peer)
            return
        if mode == 'empty204':
            headers.append(('content-encoding', 'gzip'))
            conn.send_headers(stream, headers, end_stream=True)
        else:
            conn.send_headers(stream, headers)
            for at in range(0, len(body), 8192):
                end = at + 8192 >= len(body) and mode not in ('reset', 'truncated-stream', 'trailers')
                conn.send_data(stream, body[at:at+8192], end_stream=end)
            if mode == 'reset':
                conn.reset_stream(stream, 8)
            if mode == 'trailers':
                conn.send_headers(stream, [('location', 'https://trailer.invalid/'), ('x-trailer', 'irrelevant')], end_stream=True)
        peer.sendall(conn.data_to_send())
        if mode != 'truncated-stream':
            # A real HTTP/2 server keeps this connection open. Closing while a
            # client SETTINGS ACK is unread can produce a TCP reset and discard
            # its otherwise valid TLS1.2 reply on some emulator TCP paths.
            # Drain controls until the one-shot client closes instead.
            self.drain(peer)

    @staticmethod
    def drain(peer):
        peer.settimeout(3)
        try:
            while peer.recv(32768):
                pass
        except (ssl.SSLError, ConnectionResetError):
            pass

    def close(self):
        self.sock.close()
        if self.active:
            try:
                self.active.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.active.close()
        self.thread.join(6)
        assert not self.thread.is_alive()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5570')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--native-dir', type=Path, default=ROOT/'public-read-native/x86_64')
    parser.add_argument('--sanitizer', action='store_true')
    args = parser.parse_args()
    args.output.resolve().relative_to(ROOT)
    args.native_dir.resolve().relative_to(ROOT)
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [str(ROOT/'android-sdk/platform-tools/adb'), '-s', args.serial]
    def call(*command):
        return subprocess.check_output(adb + list(command), text=True, stderr=subprocess.STDOUT, timeout=30).strip()
    assert 'uid=2000(' in call('shell', 'id'), 'Use an ordinary dev-emulator shell'
    remote = '/data/local/tmp/anti-social-byte-engine-qualification'
    call('shell', 'mkdir', '-p', remote)
    names = ['libantisocial_public_read.so', 'public-read-fixture']
    if args.sanitizer:
        names.append('libclang_rt.asan-x86_64-android.so')
    for name in names:
        call('push', str(args.native_dir/name), remote+'/'+name)
        call('shell', 'chmod', '555', remote+'/'+name)
    records = []
    cases = [('identity', 1, 32768), ('identity', 0, 17), ('identity', 1, 1), ('tls12', 1, 32768), ('informational', 1, 32768), ('redirect', 1, 32768), ('empty204', 1, 32768), ('trailers', 1, 32768)]
    cases += [(kind + suffix, 1, 32768) for kind in ['gzip', 'deflate', 'br', 'zstd'] for suffix in ['', '-limit', '-truncated']]
    cases += [(mode, 1, 32768) for mode in ['gzip-case', 'wrong-ca', 'wrong-host', 'http1', 'reset', 'truncated-stream', 'headers-limit', 'bad-length', 'unsupported-encoding', 'duplicate-encoding', 'empty-encoding', 'goaway']]
    failed_modes = {'wrong-ca', 'wrong-host', 'http1', 'reset', 'truncated-stream', 'headers-limit', 'bad-length', 'unsupported-encoding', 'duplicate-encoding', 'empty-encoding', 'goaway'}
    try:
        with tempfile.TemporaryDirectory(prefix='byte-engine-', dir=args.output) as temp:
            folder = Path(temp)
            subprocess.run(['python3', str(Path(__file__).with_name('make_certs.py')), str(folder)], check=True)
            for name in ['trusted.pem', 'untrusted.pem']:
                call('push', str(folder/name), remote+'/'+name)
            for mode, cohort, fragment in cases:
                server = Server(folder, mode)
                try:
                    call('reverse', 'tcp:'+str(server.port), 'tcp:'+str(server.port))
                    command = [remote+'/public-read-fixture', str(server.port), str(cohort), 'wrong.invalid' if mode == 'wrong-host' else 'reader.invalid', remote + ('/untrusted.pem' if mode == 'wrong-ca' else '/trusted.pem'), str(fragment), '131072']
                    if args.sanitizer:
                        command = ['env', 'LD_PRELOAD='+remote+'/libclang_rt.asan-x86_64-android.so', 'ASAN_OPTIONS=detect_leaks=0:abort_on_error=1', 'UBSAN_OPTIONS=halt_on_error=1:print_stacktrace=1'] + command
                    result = json.loads(call('shell', shlex.join(command)))
                finally:
                    server.close()
                    call('reverse', '--remove', 'tcp:'+str(server.port))
                row = {'mode': mode, 'cohort': cohort, 'fragment': fragment, 'result': result, 'wire': server.record}
                records.append(row)
                (args.output/'incremental.json').write_text(json.dumps(records, indent=2)+'\n')
                failure = mode in failed_modes or mode.endswith('-limit') or mode.endswith('-truncated')
                assert result.get('created'), row
                wire_hello = server.record['hello']
                assert wire_hello['ciphers'] == [4865,4866,4867,49195,49199,49196,49200,52393,52392,49171,49172,156,157,47,53], row
                assert wire_hello['groups'] == ([29,23,24] if cohort == 0 else [4588,29,23,24]), row
                assert wire_hello['alpn'] == ['h2','http/1.1'], row
                # BoringSSL conditionally pads the ClientHello for RFC7685
                # and empty-final-extension interoperability. Padding is last,
                # zero-filled and bounded; it is not a profile mismatch.
                assert sorted(v for v in wire_hello['extensions'] if v != 21) == sorted([16,13,18,10,5,65037,0,45,65281,11,35,27,51,17513 if cohort == 0 else 17613,23,43]), row
                if wire_hello['padding'] is not None:
                    assert wire_hello['extensions'][-1] == 21 and 1 <= wire_hello['padding'] <= 252, row
                if failure:
                    assert result['status'] == 0 and result['state'] == -1 and result['bodyBytes'] == 0 and result['headers'] == 0, row
                    if mode == 'goaway':
                        assert result['error'] == 'Server declined the request stream', row
                    if mode in ('wrong-ca', 'wrong-host', 'http1'):
                        assert 'request' not in server.record, row
                else:
                    expected = b'' if mode == 'empty204' else BODY
                    assert result['status'] == (302 if mode == 'redirect' else 204 if mode == 'empty204' else 200) and result['state'] == 2, row
                    assert result['bodyBytes'] == len(expected) and result['bodySha256'] == hashlib.sha256(expected).hexdigest(), row
                    assert result['headers'] == (2 if mode in ('redirect', 'empty204') or mode.startswith(('gzip', 'deflate', 'br', 'zstd')) else 1), row
                if 'request' in server.record:
                    assert server.record['settings'] == [(1,65536), (2,0), (4,6291456), (6,262144)], row
                    assert server.record['windows'][0] == 15663105, row
                    assert [x[0] for x in server.record['request'][:4]] == [':method', ':authority', ':scheme', ':path'], row
                print(mode, cohort, fragment, 'PASS', flush=True)
    finally:
        call('shell', 'rm', '-f', remote+'/trusted.pem', remote+'/untrusted.pem')
    (args.output/'summary.json').write_text(json.dumps({'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'serial': args.serial, 'sanitizer': args.sanitizer, 'scope': 'First-party byte engine on owned TLS/H2 fixtures. No Gateway integration, public source access, actual Tor or physical-phone performance claim.', 'checks': len(records), 'passed': len(records), 'records': records}, indent=2)+'\n')


if __name__ == '__main__':
    main()
