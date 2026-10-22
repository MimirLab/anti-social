# Building anti social

The supported bootstrap host is Linux x86_64. Android Studio, a social account,
model weights and cloud credentials are not prerequisites. A local debug signing
key is generated for a fresh build; it cannot update an APK signed by somebody else.

Install host prerequisites: Python 3 with venv/pip, a C/C++ compiler, pkg-config,
Git, Bash, curl, tar, zstd, patch and flock. On Debian-family systems these come
from `python3-venv python3-pip build-essential pkg-config git curl zstd patch util-linux`.
The ARM Vulkan build also needs a compatible modern shader compiler. The builder
tries its checksum-pinned shaderc package; on an incompatible host, set
`ANTISOCIAL_GLSLC` to a working Vulkan SDK `glslc`. Its version is captured by the
native build receipt.

## One entrypoint

Use a fresh checkout and an external filesystem with room for toolchains, native
compilation and APKs. Preserve at least 15 GiB free on the system filesystem.

```sh
git clone https://github.com/MimirLab/anti-social.git
cd anti-social
export ANTISOCIAL_BUILD_ROOT=/path/on/external-disk/AntiSocialBuild
python3 scripts/build.py
```

The entrypoint prepares JDK/SDK/NDK/Rust/Gradle, protection and embedding libraries,
the customized inference runtime, Context media and translation libraries, then
runs JVM tests, assembles debug/release APKs and Android test APKs, runs lint, and
packages corresponding source and checksums. Native prerequisites are deliberately
sequential. The English OCR data is verified and bundled during APK preparation.
No text, embedding or speech model weights are downloaded.

`--target x86_64` builds an emulator candidate; `--target arm64-v8a` builds a
64-bit phone candidate. The default builds both complete native stacks and the
ordinary 32-bit reader/protection support. Optional local AI/media/translation
features need a supported 64-bit ABI.

Useful options:

```sh
python3 scripts/build.py --check-only
python3 scripts/build.py --target x86_64 --variant debug
python3 scripts/build.py --target arm64-v8a --variant release
python3 scripts/build.py --skip-bootstrap
```

Use `--skip-bootstrap` only after preparing tools in that same build root. The
ignored `jniLibs` link binds a checkout to one external root. For an independently
clean build, use another fresh checkout and an empty root; pointing an existing
checkout at another root is rejected rather than moving its native products.

Artifacts appear in `$ANTISOCIAL_BUILD_ROOT/artifacts`, build receipts in
`clean-build/receipt.json`, and Gradle reports in `outputs/`. Native scripts retain
source hashes, patches and configuration receipts beside their build products.
Downloads are checked against their source/catalog hashes. A source rebuild does
not imply byte-identical APK signatures, generated keys or ZIP timestamps.

## Android tests and preview

Run the full instrumentation command on a disposable owned test device or an
isolated emulator. Use the non-destructive manual journeys for a personal phone:

```sh
ANDROID_SERIAL=emulator-5570 python3 scripts/build.py --skip-bootstrap --instrumentation
```

Use `scripts/gradle.sh` for individual tasks so shared compiler outputs remain
serialized. Fixture tests that clear graph/cache state require the explicit
`antisocialIsolatedFixtureDevice=true` runner argument. Do not run those fixtures
against a personal reader installation. Live-source and model-download tests have
separate opt-ins; ordinary unit tests do not establish provider availability.

For the laptop preview, run `python3 scripts/bootstrap.py` without `--no-emulator`,
then `python3 scripts/preview.py`. KVM acceleration is recommended. Read the
preview script's `--help` and `--doctor` output before changing its hardware profile.

## Official signing and releases

Release maintainers supply the existing signing identity through environment
variables documented in [publishing](PUBLISHING.md). Never commit keys, tokens,
private reader databases, model weights or build caches. The public release must
contain the versioned APK, corresponding source, checksums and `anti-social.apk`.
An assembled release variant is a candidate until its qualification gates pass.

## Current validation record

The dependency-ordered entrypoint completed an all-ABI debug/release build from
an independent checkout and an initially empty external root on 2026-10-22.
It downloaded and verified the tools, built every native prerequisite, passed JVM
tests and both lint variants, and packaged corresponding source. The complete run
took 3,290 seconds on this development laptop. That establishes the build path;
final candidate tests and physical-phone qualification are recorded separately in
[release qualification](RELEASE_QUALIFICATION.md).

Optional host Vulkan inference audits also require Vulkan development headers and
a loader discoverable through CMake (`CMAKE_PREFIX_PATH` may point at a Vulkan SDK).
They are not prerequisites for the Android build above.
