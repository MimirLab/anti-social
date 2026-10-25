# Byte-only public reading engine (experimental)

This is anti social's first-party single-hop TLS/HTTP engine. It constructs an
exchange from cryptographic, HTTP/2 and compression primitives, invoking no
scraper, browser, curl request function, subprocess or existing HTTP client.
The library has no sockets, DNS, files, proxy, redirects, retry logic, cookie
jar or connection pool. NetworkGateway owns all network operations and policy.

The development APK bridge is selected with `-PantisocialPublicRead=true`.
**The default is false. This library is absent from normal APKs.** Working TLS
and successful requests are insufficient admission: it must recover useful
content beyond the existing routes and a cheaper header-only request. See the
[actual comparison and its limits](../../docs/PUBLIC_PREVIEW_QUALIFICATION.md).

## Source-only build

`scripts/build_public_read.py` builds unmodified, hash-pinned upstream sources
listed in `sources.json`, then links the first-party engine and JNI bridge.
Tools, downloads and outputs stay on the external build volume. The earlier
compatibility-archive prototype remains in Git history; it is no longer an
input. No curl, QUIC/HTTP3 or allocator audit exceptions remain.

The October 24 source set is BoringSSL `8f77af2e983b73df4f018e2a141450d005f5587b`,
nghttp2 1.70.0, Brotli 1.2.1, zlib 1.3.2 and Zstandard 1.5.7. This replaces the
prototype's older nghttp2/Brotli versions. Review the
[nghttp2 advisory](https://github.com/nghttp2/nghttp2/security/advisories/GHSA-6933-cjhr-5qg6),
[Brotli release](https://github.com/google/brotli/releases/tag/v1.2.1) and pins
when upgrading. Matching licenses and input/output hashes accompany builds.

Current stripped libraries are 2,269,080 bytes for arm64-v8a and 2,513,096 bytes
for x86_64: raw ELF sizes, not measured release APK growth. They use static C++,
16 KiB alignment and audited exports. Socket, DNS and existing request-stack
symbols are rejected.

## Boundaries

A mandatory synchronous callback receives the real DER chain, TLS protocol,
cipher, OCSP and SCT data. Gateway uses its platform extended trust manager
with an HTTPS socket context, then its existing hostname verifier. HTTP is
not sent before verification. JNI uses bounded opaque handles and thread
ownership, never caller-supplied native pointers.

Gateway supplies a coupled header/TLS/HTTP2 profile. This does not simulate
JavaScript, fonts, GPU behavior or an entire browser. Both measured profiles
require actual AES hardware for their cipher ordering. Unsupported hardware
retains ordinary routes. HTTP/1 ALPN is advertised as in the observed profiles,
but negotiation fails before HTTP. Only one bounded GET is supported.

Identity, gzip, deflate, Brotli and Zstandard responses have independent wire,
decoded, header, frame, certificate and window limits. Multiple encodings,
concatenated streams and truncated/reset responses fail explicitly. The owner
accounts decoded chunks before retaining them and removes compressed
Content-Encoding/Content-Length when wrapping decoded data.

Gateway still owns filtering, same-origin redirects, current privacy sessions,
cancellation, budgets and original-site opt-out. Owned SOCKS5 uses domain
addressing; selected Tor never falls back to direct. Temporary cookies are
bounded, memory-only and scoped to the exact source, target, origin, profile
and issuing session. Expiry is absolute; mode round trips cannot revive state.
This does not prove that session renewal restores a site's preview allowance.

## Development qualification

```sh
python3 scripts/build_public_read.py --fixture-driver
# Use an external development venv with h2==4.3.0.
python native/public_read/fixtures/check_wire.py --serial emulator-5570 --output /run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/byte-read
python3 scripts/build_public_read.py --abi x86_64 --fixture-driver --sanitize
python native/public_read/fixtures/check_wire.py --serial emulator-5570 --native-dir /run/media/red/RedtopStorage/DarwinBuild/LiberBuild/public-read-source-native-asan/x86_64 --sanitizer --output /run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/byte-read-asan
bash scripts/gradle.sh :core:test
bash scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest -PantisocialTargetAbi=x86_64 -PantisocialPublicRead=true
```

Sanitizers cover primitive C/C++ plus the engine and fixture driver, excluding
assembly and the Android UBSan vptr check. The pinned runtime reports a false
vptr failure on a standalone valid Base/Derived control, matching
[NDK issue 2065](https://github.com/android/ndk/issues/2065). That gap is explicit
in build records. Valid-inheritance, intentional heap-overflow and intentional
signed-overflow controls check that the remaining instrumentation is active.
The sanitizer fixture uses a shared C++ runtime preloaded after ASan per
[NDK issue 988](https://github.com/android/ndk/wiki/Changelog-r26#known-issues).
Normal builds retain their isolated static runtime; sanitizer dependencies are
never staged into an APK. The fixture driver alone owns temporary certificate
keys. Android tests exercise actual Gateway/JNI requests. Public-source tests
are explicit opt-in; their observations and source/input hashes stay external.

There is no TLS/H2 reuse pool. Incremental access, cold/warm performance, source
capacity policy and physical-phone qualification must earn production inclusion.
A working duplicate path is not proof of additional accessible information.
