# Native local text runtime

AntiSocial uses one pinned QVAC/ggml core, with a narrow Prism donor patch. This code
only reads a verified private model file and generates text. Downloads, network
policy, feature consent, job persistence and process isolation belong to Android
application code. There is no localhost server, inference app, Python runtime,
model training or network access in this bridge.

The packet's QVAC revision `bc47470138d892a45fedb037c2615cc12c54cded` was tested
against its pinned Bonsai 2 4B PQ2_0 file. It rejects the actual file with
`unsupported prism.hadamard.version: 2`. The model has a tied embedding/output
table. Merely accepting the version would silently omit the output activation
transform and produce incorrect inference.

`qvac-bonsai-v2.patch` ports the complete narrow tied-output metadata and binding
contract from Prism revision `11809184898113482c2f510fd13da669aaba1052`. It adds
version/tied-output consistency checks, requires the latent embedding, registers
the matching forward transform for the output projection, and binds forward and
inverse transforms to the actual output/embedding tensors. QVAC's existing
graph, kernels and allocation rules remain the core. Patch and source archive
hashes are recorded in each build manifest.

The application default remains **F16 reference attention cache**, preserving
the model's separate hybrid recurrent state. `qvac-online-kv-v2.patch` adds
online PQ4/PQ3 storage, direct mixed compressed/exact CPU and Vulkan attention,
and a bounded initial/recent F16 region. Planar/Iso and signed Hadamard rotations
are alternative recipes, with an explicit inverse-V transform and immutable
basis/calibration fingerprints. Availability is separate from device admission;
the private JNI profile argument is used by opted-in Android qualification.
KVarN tiles, allocation paging, OpenCL and Hexagon remain unfinished work.
`qvac-state-recipe-v3.patch` checks resolved RoPE/YaRN settings, partial/multi-axis
rotary geometry and the cache descriptor before reading hybrid state. A context
override no longer changes the loaded model's native-window metadata. Matching
checkpoints can replay; changed recipes fail before changing live memory.
This guard does not make arbitrary damaged tensor payloads transactional: clear
state after a failed restore. Verified pack identity remains a separate boundary.
The default template explicitly disables thinking using the bundled model's
Jinja template and uses the vendor's non-thinking reference sampling profile.

Reproduce the host reference or packaged Android libraries from the repository:

```sh
python3 scripts/build_local_ai.py --host --reference-model
python3 scripts/build_local_ai.py --host --vulkan --kv-probes
python3 scripts/build_local_ai.py --android x86_64
python3 scripts/build_local_ai.py --android arm64-v8a --vulkan
```

Host Vulkan builds require development headers, Vulkan-Hpp and the
`SPIRV-Headers` CMake package. Supply a host SDK through `VULKAN_SDK` when they
are outside system paths. The qualified local build uses the same header pins
as the Android build under LiberBuild; its SDK file receipt is
`ai-foundation/vulkan-host-sdk-v1/antisocial-header-receipt.json`. Host-only builds
retain notices in their own output directory.

Use `--no-package` to compile an Android candidate without replacing staged
application libraries. Each cache-aware build has its own source/build directory,
leaving the earlier qualified runtime intact. Frozen release APKs are separate.
The composed source recipe records ordered patch hashes and final postimages;
repeated builds verify those rather than trying to reverse overlapping patches.

Host CMake targets `antisocial-kv-{attention,basis,logits,calibrate,state,position}-reference`
provide independent store/attention math, rotation identities, held-out cache-only
comparisons, development calibration and actual-model checkpoint tests. The
fictional source and calibration corpora and predeclared gates are in `fixtures/`.
The calibration probe selects post-RoPE keys by operation as well as tensor name;
the graph also names its flattened pre-normalization projection `Kcur`.
The fixed diagonal is one shared positive channel transform, not the complete
KVarN tile algorithm. Python comparison drivers verify its model SHA256 binding
before invoking the development binary. Numerical gates do not replace source
fidelity, whole-job memory, lifecycle and actual-device qualification.

`antisocial-kv-quality-reference` reuses the production template, sampler and JSON
constraint. `scripts/check_local_ai_quality.py` replays the unchanged frozen
24-case Context contribution-selection suite. Its predeclared positional
quality-improvement gate requires correcting at least one baseline error without
adding a new error. That gate is separate from qualifying an equivalent-fidelity
recipe for a genuine memory or complete-job wall-time improvement; neither
establishes unrestricted summary quality. Model/binary/source hashes
and actual selected recipes are recorded, and partial evidence cannot become a
completed baseline.

The C++ test interface can request `yarn2`; Android's JNI callers use `native`.
The experiment doubles the model's own positional range, independently of the
active allocation, and retains its partial/multi-axis rotary geometry. The logits
probe rejects different positional references unless explicitly asked to compare
them. No YaRN feature is enabled in the app. Retain the experiment only if actual
Context quality improves over the native settings at equal input length.

The first frozen 2B CPU/2K passage-selection comparison scored 15/24 for both
native positioning and 2× YaRN: one correction and one regression. It failed
that predeclared quality-improvement gate and is not promoted. This does not
answer the separate 4B DFlash2 complete-job wall-time question.

`antisocial-kv-speculative-reference` and `scripts/check_local_ai_speculation.py`
are bounded host development controls using the model's non-thinking template.
They compare greedy replay, all attempted proposals, verification/replay work,
process wall time and memory across proposal depths and recurrent-state versus
checkpoint rollback. A declared observation ceiling can reject a result;
passing it cannot qualify a phone. These controls are not the complete
ContextEngine workload or an Android speculative worker. Acceptance is only a
diagnostic. Choose the high end of the useful complete-job throughput plateau
among configurations that preserve fidelity and fit the target device.

Phone CPU and GPU allocations must share one physical-memory budget rather
than treating advertised RAM and GPU heaps as independent pools. Android
`AiMemoryHeadroom` reserves the system low-memory threshold plus reader growth
before opening a plain target or growing its instruction cache. Its target-file
plus scratch estimate remains provisional; it does not admit a drafter.
Separately account for both models, both caches, recurrent rollback slots,
snapshots, captured features, prefill/verification graphs, the embedding
companion, translation and reader graphics. Check the GPU's changing allocation
budget when exposed. See [Android MemoryInfo](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo)
and [Vulkan memory budgets](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_memory_budget.html).
The pinned automatic fitter can fail to size DFlash without `ctx_other`, then
continue without counting the extra model; paired controls therefore use
explicit allocations and `--fit off`. No physical phone qualification follows
from that fitter's success message or a laptop run.

Real Context source review also remains necessary after numerical cache checks.
An actual 2B holdout inverted quoted uncertainty in its generated paraphrase.
The phone finding layer now binds selected passage IDs to coherent source prose
and typed source attribution, with plan/version invalidation of older live
receipts. Concise positively screened replies keep their complete qualification.
An exact attributed ledger that already fits the counted output allowance needs
no model rewrite; larger ledgers still use compaction. Bounded source-grounding
checks passed on the host and isolated Android worker; unrestricted summary
fidelity and complete-job speculative qualification remain pending.

`scripts/check_context_pipeline.py` freezes the complete JVM classpath, native
runner, recipe and synthetic fixtures before real ContextEngine jobs. It records
initial collection inputs, identical/noise refreshes, later developments and
compaction receipts, including raw synthetic model exchanges for semantic review.
Resume currently reuses typed receipts in memory; Android persistence and full
collection/translation need their separate tests. Process-tree PSS observations
exclude Android and other apps and can miss short peaks. Completion alone does
not establish semantic fidelity, phone fit or useful speed. Preserve failed
baseline evidence, including source-background copying and speaker mixing.

The host quality runner also offers development-only `context_greedy_v1` JSON
sampling. It is not an Android setting or an admitted improvement. Android keeps
the vendor non-thinking reference sampler. An isolated greedy fixture still
failed source review; no sampler promotion follows from adding a control.

All fetched sources, tools and build outputs live under the required external
build root. The build script keeps compiled JNI libraries under the external
`native-android` directory and links the app's native-library path there. It
copies exact upstream license notices into the app's assets. Model weights remain separately acquired
optional data. Include the pinned source archive and this donor patch in source
deliveries, alongside the app's GPL notices.

Vulkan builds use a current host shader compiler because the NDK's 2022 compiler
silently disables modern packed integer-dot and cooperative-matrix kernels. The
script pins a small official Arch shaderc package for this development host;
other Linux hosts can provide a matching Vulkan SDK compiler in `ANTISOCIAL_GLSLC`.
`qvac-bounded-shader-build.patch` limits nested shader compiler pools so the
outer four-job build remains bounded. It changes build scheduling, not shaders.

`qvac-android-vulkan-dispatch.patch` routes three feature queries through the
already loaded Vulkan dispatcher. Directly linking their Vulkan 1.1 symbols
would make the library fail to load on Android API 26/27. The backend still
requires the actual device's Vulkan features before admitting a GPU route.

`qvac-pq2-ssse3.patch` adds a packed 128-bit integer-dot path for Android x86_64
devices and virtual CPUs without AVX2. Android's x86_64 ABI includes SSSE3;
AVX is optional ([Android ABI documentation](https://developer.android.com/ndk/guides/abis#x86-64)).
It preserves coefficient order, integer dot products, and every block scale.
The host kernel test compares the actual kernel with the independent scalar
decoder over 5,000 randomized cases, with zero numerical difference. This
establishes kernel parity; emulator and physical-phone performance remain
separate measurements.

The host executable accepts `--self-test /path/to/verified.gguf`. Its cases
exercise corrected numbers, negation, attribution, Unicode, bounded input,
cancellation, reset and closed handles using the actual model. These foundation
checks do not establish summary quality on an unrestricted public feed.

JNI is `app.antisocial.android.LocalTextNative`: `capabilities`, `open`, `generate`,
`diagnostics`, `cancel`, `reset`, `close`. There is one active model/job.
Cancellation is sticky until reset; close is idempotent. Every request clears
both attention and recurrent memory before and after execution. CPU cancellation
uses the runtime abort callback; GPU execution checks cancellation at decode and
32-token prefill boundaries because a running driver kernel cannot be forcibly
interrupted safely. Input is rejected rather than silently truncated.

Device enumeration reports actual registered CPU/Vulkan devices. An explicit
unavailable Vulkan request fails; automatic placement can choose CPU. Selecting
a GPU does not certify every operator ran there. Diagnostics state that CPU
operator fallback is possible. Physical Android GPU speed, memory, thermals and
driver reliability must be measured separately from laptop/emulator results.
