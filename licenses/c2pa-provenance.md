# Content Authenticity Android SDK

anti social uses the official Content Authenticity Android library 0.0.14, under its
Apache-2.0 / MIT dual license. The upstream copyright and license texts are
preserved in `c2pa-LICENSE-APACHE.txt` and `c2pa-LICENSE-MIT.txt`.

- Source: https://github.com/contentauth/c2pa-android/tree/0.0.14
- Release: https://github.com/contentauth/c2pa-android/releases/tag/0.0.14
- Artifact: https://github.com/contentauth/c2pa-android/releases/download/0.0.14/c2pa-release.aar
- Artifact SHA-256: `34a48ca041dabd641c40c9540f3674ea46f3c2ddecfd48dc2ccfbbb965c1ec95`
- Downloaded archive size: 36,813,049 bytes. Binaries: arm64-v8a, armeabi-v7a,
  x86 and x86_64; the release embeds the Rust SDK 0.91.2 and the Android NDK C++
  runtime. Rust SDK source: https://github.com/contentauth/c2pa-rs
- C++ runtime license: Apache-2.0 with LLVM exception;
  https://github.com/llvm/llvm-project/blob/main/libcxx/LICENSE.TXT

`scripts/fetch_c2pa.py` downloads this exact artifact to the external build
directory and checks its byte count and SHA-256. The SDK is unmodified. anti social’s
own adapter always supplies isolated offline settings and a denying HTTP resolver;
no upstream default network client, identity resolver, signer or hardware signing
feature is used. The unused biometric permission is removed from the merged
manifest. The optional native validator is guarded to Android API 28+; anti social’s
other features retain API 26 support.

`kotlinx-serialization-json` retains its Apache-2.0 license:
https://github.com/Kotlin/kotlinx.serialization

Test fixtures are original anti social test artwork and local test certificates, made
with the official c2patool. They are only in the instrumentation APK and contain
no user's media or private signing key. Their provenance is recorded beside the
fixtures in `app/src/androidTest/assets/provenance/README.txt`.
