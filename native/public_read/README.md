# Byte-only public reading engine (experimental)

This is anti social's first-party single-hop TLS/HTTP engine. It builds the
exchange from established cryptographic, HTTP/2 and compression primitives;
it does not invoke a scraper, browser, curl function, subprocess or HTTP client.
The library has no socket, DNS, proxy, filesystem, redirect, cookie, credential,
retry or persistent connection implementation. Its owner supplies encrypted
bytes and drains encrypted output. `NetworkGateway` remains responsible for
authorizing every connection, destination, privacy session and follow-up.

The C interface is deliberately internal and single-owner. A mandatory callback
must validate the complete certificate chain **and hostname** against the
owner's trust policy. HTTP headers are sent only after this succeeds. The
initial implementation accepts one bounded GET, HTTP/2, explicit TLS cohorts,
and identity/gzip/deflate/Brotli/Zstandard bodies. Redirects are returned as
responses. HTTP/1 negotiation currently fails before sending an HTTP request;
it is not described as working access. It neither runs nor simulates page
JavaScript. The profile constructor in `core/PublicPersona.kt` supplies matching
request headers; a TLS match alone is not a complete browser identity.

Request headers are an owner-supplied, bounded allowlist; the byte interface
itself does not verify all browser-header relationships. Its eventual Gateway
adapter must accept the checked persona object, never an arbitrary rich header
map. Preserve original response headers as observations, but remove compressed
`Content-Encoding`/`Content-Length` when wrapping its **decoded** body for other
consumers. Multiple content encodings and concatenated compressed streams are
initially unsupported and fail explicitly.

Certificate, encrypted input, header, frame, control-output, compressed-input,
decoded-output and compression-window limits apply before success. Incomplete
or reset streams and compressed truncation cannot expose a successful partial
body. The owner must additionally enforce deadlines, cancellation and current
Gateway session identity. Destroy an engine only from its owning thread after
stopping its byte pump. No native address is exposed to arbitrary app callers.

`scripts/build_public_read.py` prepares a development library under the external
build root. It is **not wired into normal APK builds or acquisition routes**.
Initially the script garbage-collects only referenced primitive sections from
the pinned Android static object distributed by curl-impersonate. This reuses
its compiled BoringSSL/nghttp2/compression code, not its request orchestration.
The symbol audit rejects curl, QUIC/HTTP3 protocol, socket and DNS functions. The
combined upstream object retains two dead map-resize helpers and four local
allocator wrappers through shared section names; the audit records exactly
those permitted memory helpers rather than treating them as a network stack. Its
matching pinned source headers and upstream notices are retained beside the
build. Source-only primitive builds can replace that intermediate distribution
without changing the engine boundary. Do not mistake this qualification path
for the finished production runtime packaging or Gateway integration.

The compatibility distribution pins nghttp2 1.63.0, predating
[CVE-2026-27135's fix](https://github.com/nghttp2/nghttp2/security/advisories/GHSA-6933-cjhr-5qg6).
The described paths require explicitly enabled extension processing, which this
engine does not enable. Nevertheless, replace or patch the primitive build and
review all dependency advisories before any production admission. Successful
owned-fixture tests do not qualify an old dependency set for hostile web input.

Development qualification:

```sh
python3 scripts/build_public_read.py --fixture-driver
# Run the printed native/public_read/fixtures driver through owned emulator
# loopback endpoints; use an external development venv with h2==4.3.0.
python native/public_read/fixtures/check_wire.py --serial emulator-5570 --output /run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/byte-read
python3 scripts/build_public_read.py --abi x86_64 --fixture-driver --sanitize
python native/public_read/fixtures/check_wire.py --serial emulator-5570 --native-dir /run/media/red/RedtopStorage/DarwinBuild/LiberBuild/public-read-native-asan/x86_64 --sanitizer --output /run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/byte-read-asan
```

The fixture driver alone owns test sockets and temporary certificate files;
the byte library has neither. Sanitizers instrument first-party C++ and the
driver; the reused primitive archive is not itself sanitizer-instrumented.
These tests do not substitute for an actual APK/Gateway integration test.

The initial engine opens one exchange per owner-created connection and has no
TLS-session or HTTP/2 connection pool. It belongs in a selective routing path
until matched cold/warm Gateway measurements establish its access benefit and
latency, CPU and battery cost. Broad application must earn its place against
the existing reusable connections, not against a fresh connection baseline.
