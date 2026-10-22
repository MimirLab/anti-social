# On-demand local text engine

The ordinary reader, remote AI and CPU embeddings do not install or load the
local text engine. Enabling AI alone does not install it. Choosing local text
performs installation as part of the existing single download action. No root,
ADB, Termux, compiler, local server or additional application is required.

## Reusing upstream bytes

The phone library contains a large Vulkan shader bank. Compatibility of an
upstream executable is not the relevant test: its reusable bytes can supply
part of an entirely different output through a binary reconstruction patch.

ARM64 uses QVAC's public, pinned b7349 Vulkan release as an **inert donor**:

- Archive: `llama-b7349-bin-ubuntu-vulkan-x64.tar.gz`, 56,234,405 bytes.
- Archive SHA256: `fb42fb4f4b3ae623ba606f7bbbaebe620be4966e04a95a30b0fb6f9b670ce12a`.
- Selected regular member: `./libqvac-ggml-vulkan.so`, 70,496,480 bytes.
- Donor SHA256: `02e380579c34253c0807d4a427ba7011718162499ada2c0d4dbad149f51aab7a`.
- Packaged BSDIFF40 patch: 24,552,339 bytes.
- Custom Android postimage: 100,067,688 bytes.
- Postimage SHA256: `2a7672223b9abb10bb67cddec7817fce5d4251623cd7160de2a470cfa99e767f`.

The upstream Linux library is never loaded or executed. Its ABI need not match:
the installer only copies/adds bytes according to bounded BSDIFF40 instructions.
The final ELF must have the selected Android architecture and the exact pinned
postimage hash. This preserves the existing Bonsai graph, JNI bridge, Vulkan,
CPU fallback and custom KV implementation byte for byte. It does not promote
experimental KV profiles, YaRN or speculative decoding; existing qualification
and defaults remain in force.

This reduces the phone engine's packaged payload by 75.46% against its previous
uncompressed native entry. The patch is not a 23 MiB executable engine: local
installation still occupies about 95.4 MiB. The first explicit phone setup
downloads the 53.6 MiB donor archive. The x86_64 emulator has no large Vulkan
bank; its 9,978,600-byte engine instead uses a 3,636,383-byte compressed installer,
without a donor request. These are separate strategies chosen by device ABI.

## Integrity, lifecycle and storage

The manifest and patches live inside the signed APK. A remote document cannot
change URLs, hashes, architecture, engine version or executable instructions.
The archive, extracted member, packaged patch and final output each have pinned
sizes and SHA256 hashes. Downloads use NetworkGateway's existing resumable,
exact-size transport and filters. Selected Tor fails closed. There is no direct
network fallback or inference-provider fallback on installation failure.

Only the named regular archive member is copied to a fixed private filename.
No archive paths or symlinks are created. BSDIFF reconstruction uses three
bounded bzip2 streams, fixed 64 KiB buffers and bounded operation counts, with
no complete source/target byte arrays on Android. Cancellation is checked
through extraction and reconstruction. Storage admission includes the archive,
donor, patch, output and 32 MiB reserve, about 285 MB for ARM64 at this revision.
This is temporary working storage, separate from model weights.

The output inode is marked read-only immediately after opening its writing FD.
It is synced and verified before atomic publication into private no-backup
storage. A fresh worker hashes it again before loading. Incomplete outputs,
donor extraction and patch scratch are removed; only one bounded resumable
network partial can survive a pause. Successful installation removes the donor
archive too. Re-entering installation cleans scratch left by a crash after
publication. Explicit removal deletes all installed and partial runtime files.

“Remove engine · keep models” first cancels AI work, unbinds and stops only the
app UID's `:local_ai` process, then removes its native files. Stopping that
process also releases dlopen mappings; an in-use library is not left consuming
unlinked storage. The reader process and protected saved material survive.
Explicit removal prevents background Context work from reinstalling the engine.
Users can restore it explicitly without downloading their model weights again.
Turning AI off instead keeps downloads for reuse, as before.
Overlapping removals serialize, and a cancelled settings action still clears
its busy state before releasing the storage-mutation lane.

The compact patch is part of the signed app and remains after engine removal.
Other native libraries now use compressed APK packaging and Android's normal
ABI-specific extraction. The privacy filter snapshot retains its direct
uncompressed loading path; rules and protection are unchanged.

## Build and distribution

`scripts/prepare_local_runtime.py` produces the manifest/installers from the
existing externally staged native products. It obtains a fixed upstream donor,
uses the BSD-licensed bsdiff4 1.3.1 development tool, verifies a complete
round trip, and caches the patch under a donor/target/patch hash receipt.
Nothing is compiled or patched by an AI on the phone. The ordinary Gradle build
runs this preparation and excludes `libantisocial_local_ai.so` from APK native
entries. AAPT treats `.gz` specially, so compressed opaque installer assets must
use the `.packed` extension to preserve their exact payload.

The corresponding QVAC source archive, app-native code, ordered custom patches,
build scripts and notices remain in the versioned source package even though
the full runtime no longer appears as an APK native entry. Apache and BSD
notices are under `assets/native-runtime-notices`; existing QVAC/Vulkan notices
remain available. The donor release is
[QVAC b7349](https://github.com/tetherto/qvac-fabric-llm.cpp/releases/tag/b7349).

This is a direct-distribution installer, not Google Play Feature Delivery. A
Play distribution would need its native feature delivered through Play's
supported mechanism. Availability of the pinned public donor is an external
dependency; its failure does not impair ordinary reading or remote AI.

Deterministic tests cover manifest admission, changed/truncated payloads,
architecture, donor integrity and regular-file selection, low storage,
cancellation, stale scratch recovery, corruption repair, exact output, removal
and Tor fail-closed behavior. Android qualification additionally reconstructs
the actual ARM postimage without loading it in the x86 emulator, and installs,
loads, terminates, removes and reinstalls the x86 worker. Physical-phone GPU
execution and phone installation timing require separate hardware testing.

The first Android 35/x86_64 emulator reconstruction of the actual ARM postimage
took 47.4 seconds using a pre-staged donor, while the development compiler was
also active. This is a one-time installation observation, excludes network time,
and is not a physical-phone performance claim. The engine worker also passed
remove/reinstall with a new PID and retained model-directory sentinel.
The subsequent cold production-Gateway download plus reconstruction completed
in 46.8 seconds on that emulator. These observations include neither a model
weight download nor physical ARM execution.
