# Optional AI foundation

anti social remains a native, accountless social reader when AI is off. Reading,
ordinary search, subscriptions, saved copies, routing, name resolution and
cross-post collection do not require a model, API key or AI service. Existing
content-provenance/AI-declaration metadata also works without an inference model. Darwin is
the rebuilt and expanded routing donor; its source provenance and GPL notices
remain intact.

The foundation establishes model acquisition, native inference, provider access
and settings. The separately documented [Context reader](CONTEXT.md) now uses
these capabilities for optional post and discussion briefs. Semantic search,
AI deduplication and per-post generative feed filtering are outside this
foundation. Optional tools appear only when the reader enables the relevant
capability; existing reader mechanisms work independently of them.

## Using it

Open Settings → AI models. AI is off by default. Enable it, then choose
**Download & activate** for the local text model and its small embedding
companion. There is no account, payment, API key, Python, Termux, inference
server, root access, ADB setup or separate runtime app in this installation path.
Downloads show their size, progress and a pause action. Partial files can resume.
The text engine itself is installed on demand. ARM64 setup borrows pinned,
inert upstream bytes and applies the app's compact reconstruction patch; the
finished library must match the exact custom runtime hash in the signed APK.
The smaller emulator engine uses a compressed local installer. See
[runtime delivery](LOCAL_RUNTIME_DELIVERY.md) for storage and source details.

Text and embeddings have separate settings and can be installed or removed
independently. Switching AI off cancels optional work and releases the worker;
downloaded model data remains available for the next explicit activation.
Explicit removal frees model storage without deleting subscriptions, saved posts
or offline media. Model files are protected explicit downloads, outside the
ordinary bounded reader cache.

Users can keep several providers saved independently for each role. Standard
slots cover **OpenAI · ChatGPT**, **Anthropic · Claude**, **DeepSeek**, and **Z.ai**.
**Your local server** and multiple named **Other provider** slots support
additional compatible services. No cloud provider is selected on a fresh install.
Bring your own API key and choose a model available to that account. Consumer
chat subscriptions and API access/billing may be separate.

Each role has three request choices:

- **Local first** (default): use an installed device model. If no local model has
  been selected, use the explicitly selected, enabled provider. A pending local
  download or failed local request does not trigger a cloud upload.
- **Device only**: require an installed device model, with no remote inference.
- **Use selected provider**: use that provider even while local models remain
  downloaded. Users can choose cloud text and local embeddings, or change back
  without deleting anything.

Selecting a provider does not make a request. Forgetting the selected provider
leaves the slot unselected rather than silently switching to another service.
**Test selected provider** is an explicit small connection check containing no
reader material; provider charges may apply. AI remains off by default, and
opening, editing or saving settings never initiates inference.

### Provider protocols and scope

OpenAI uses Chat Completions with `max_completion_tokens`, no forced temperature,
and `store:false`; embeddings use its separate float embedding endpoint. Claude
uses Anthropic Messages with a top-level system instruction, version header and
optional workspace ID; only returned text blocks become an answer. DeepSeek and
Z.ai use their documented compatible chat endpoints. Short utility requests
request non-thinking answers from DeepSeek and the documented optional-thinking
GLM families. Model IDs are editable, and arbitrary model compatibility is not
guaranteed. Mandatory-thinking models can exhaust a short output cap; this is
reported instead of exposing hidden reasoning as an answer.

The Z.ai preset uses its **standard API**, not a Coding Plan endpoint. Users can
configure another permitted endpoint under Other provider according to their
plan's rules. Text providers without a documented embedding endpoint are not
advertised as embedding providers. OpenAI and compatible custom embedding
services remain available, independently of the text choice.

Custom slots support OpenAI-compatible chat, OpenAI chat and Anthropic Messages
formats. They do not implement every vendor's proprietary protocol (for example,
AWS request signing or Gemini's native generateContent API). Choose an available
compatible endpoint for those services. There is no automatic cloud-provider
failover, OAuth login, account purchase or inference-based model discovery.
Authorized Context work can read capability metadata from the selected endpoint
without sending reader content or generating a completion. Opening settings
does not initiate that probe.

Checked official protocol sources on 2026-10-18:
[OpenAI chat](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create),
[OpenAI embeddings](https://developers.openai.com/api/reference/resources/embeddings/methods/create),
[Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create),
[Anthropic authentication](https://platform.claude.com/docs/en/api/overview),
[DeepSeek](https://api-docs.deepseek.com/quick_start),
[DeepSeek thinking](https://api-docs.deepseek.com/guides/thinking_mode/),
[Z.ai](https://docs.z.ai/guides/overview/quick-start), and
[Z.ai chat parameters](https://docs.z.ai/api-reference/llm/chat-completion).
Named-provider wire contracts are tested with local fixtures. Live availability,
billing, quotas and account-specific model permissions depend on the selected
service and supplied credentials; consult the recorded verification evidence
for endpoints actually exercised.

## Models and device guidance

The local text choices are published standalone Bonsai 2 models from PrismML:

| Choice | Packed text download | With the unchanged SSE companion | Guidance |
| --- | ---: | ---: | --- |
| 2B | 517,312,992 bytes | 525,966,861 bytes | Smaller-device option |
| 4B | 1,146,465,120 bytes | 1,155,118,989 bytes | Standard model in the research packet |
| 9B | 2,416,851,840 bytes | 2,425,505,709 bytes | Larger alternative when resources permit |

These are PQ2_0 payload sizes, not process-memory measurements. The app estimates
a starting choice using ABI, current free memory, total memory and storage. The
current conservative thresholds are 3/6/12 GiB total memory, approximately
1.25/2.5/5 GiB available memory, and 1/2/4 GiB free storage for 2B/4B/9B. They are
engineering admission estimates, visibly described as hardware estimates, rather
than phone speed or fidelity certifications. Users can choose another profile.
The installed model is checked on the actual device after download.

The selected native text route supports 64-bit ARM and x86_64. ARM64 builds
include the Vulkan route; the runtime reports the selected device and possible
CPU operator fallback. A registered GPU or successful compilation does not prove
that every model operation runs on it. Physical phone performance, peak
reader-plus-AI memory, energy, driver reliability and sustained thermals require
device measurements. The x86_64 emulator/reference route uses CPU. No NPU,
OpenCL, Hexagon or universal GPU-speed claim is made.

The Hugging Face 4B repository's apparent **0.8B** listing refers to its auxiliary
DFlash draft artifact: the observed API reports architecture `dflash`,
782,004,736 parameters and 1,575,278,208 bytes, matching
`Ternary-Bonsai-2-4B-DFlash2-BF16.gguf`. It is not a standalone 0.8B replacement
for the approximately 4.21B-parameter main text model. No DFlash file is part of
the normal download. Speculative drafting remains a separate optional
experiment that must justify its extra download, memory and verification cost.
The captured source/API evidence is listed in the implementation ledger.

## Native execution

Inference runs inside the app's private `:local_ai` process. This process does
not initialize the reader repository, open an inference socket or acquire
network/action tools. A native or driver failure is isolated from the reader
process. Binder carries bounded control messages and small results. There is one
app-owned inference lane, explicit cancellation/reset/close, idle unloading and
memory-pressure release. There is no permanent background inference loop.

The generator uses pinned QVAC Fabric LLM / ggml code with a narrow PrismML
Hadamard-v2 donor patch. The unmodified QVAC pin rejects the actual published
GGUF with `unsupported prism.hadamard.version: 2`. Accepting that version number
alone would omit the tied output transform. The patch preserves the matching
tied embedding/output metadata checks and forward/inverse activation binding.
The model's weight transforms remain distinct from attention-cache codecs.

The current generator uses the **F16 reference attention cache** and preserves
the separate hybrid recurrent state. The bounded service has an active
**2,048-token window** for every local text choice and rejects oversized input
rather than silently truncating it. The model's own chat template explicitly
receives `enable_thinking=false`; sampling uses Prism's non-thinking reference
profile. Repetition, cancellation, reset and closed handles have explicit
handling.

Context counts the actual local chat template and splits evidence to fit that
window, reserving up to 512 output tokens. For a selected remote text service,
Context can adopt a verified active window from model metadata, retain a
conservative 2,048-token fallback when it is unknown, or use an explicit reader
override constrained by reported limits. Advertised training limits and model
parameter counts do not by themselves enlarge the active budget. Unknown
remote tokenizers use a conservative UTF-8-byte count plus headroom; inference
also retains independent request and output safety ceilings. See
[Context budgeting](CONTEXT.md#budgeting-and-capability-metadata) for the exact
metadata scope and limits.

Local Context generation opts into native JSON-object grammar decoding. Each
request has a fresh grammar state, with strict final parsing before a result is
returned. Incomplete output remains an error, and core validation still checks
schema, source identity and exact quotes. Ordinary free-form text requests are
unchanged. Syntax constraints do not establish semantic correctness: small
models have produced redundant or irrelevant findings despite valid quotes.
Neither a larger window nor a larger local choice is a fidelity certification.

Context avoids another synthesis call for a ledger of up to three findings when
their author-labelled paragraphs fit within 900 characters. Larger ledgers use
source-labelled compaction with reusable receipts. Invalid model reviews remain
visible as unreviewed coverage, keep their source copies and completed work, and
can be retried by a later request or refresh. These mechanisms preserve progress
and provenance; they do not make an unrestricted feed summary reliable.

Packed PQ4/PQ3 attention caches, protected-history tiles, KVarN/KPC,
PlanarQuant/IsoQuant/RotorQuant, larger local contexts, mixed execution and draft
acceleration remain research work. They are not implemented or advertised as
qualified capabilities by this foundation.

## CPU embedding companion

The five unchanged SSE files occupy 8,653,869 bytes. This is an **English**
retrieval model with 512-dimensional output. Unicode tokenization parity does not
make its retrieval quality multilingual. Its misleading `embedding.q4_k_m.bin`
filename denotes a custom uniform four-bit table, not GGUF or llama.cpp Q4_K_M.

The native reader verifies every file hash, maps the 7,935,720-byte table
read-only, and reconstructs only accessed rows into a 512-element accumulator.
It preserves high-even/low-odd nibbles, each row's scale, repeated token
occurrences, exact BERT/WordPiece tokenization, mean pooling and the learned
`beta * tanh(alpha * mean + bias)` head, then L2-normalizes. Empty token sequences
produce typed no-content results and must not be indexed. The tokenizer uses
the published JSON independently of Bonsai, with no special-token insertion,
padding or truncation. Tokenizer parallelism is disabled so it respects the
app-owned CPU lane. No full decoded matrix or Python runtime is used on Android.

An embedding-space identity includes the exact five hashes, tokenizer, pooling,
head version, dimension, normalization, prefixes and F32 index policy. Vectors
from different spaces must not be compared merely because their lengths match.
Index quantization is separate from model-table compression and is not enabled
here. Context can use the installed companion for a bounded, RAM-only bank of
English candidate findings; it makes no remote embedding request for this
purpose and falls back to lexical selection when unavailable. Similarity does
not establish equivalence, agreement, truth or permission to discard a
correction. This is not a general semantic-search or AI-deduplication feature.

## Privacy, storage and release integrity

Every application request, including model downloads and remote inference, uses
`NetworkGateway`. Model downloads retain ordinary filtering, redirect validation
and Tor policy. Inference service requests pin the exact selected endpoint,
disable redirects and transport replay, omit cookies and ordinary content
response observers, and attach credentials only within their service scope.

Cloud endpoints require HTTPS. An explicit **Your server** choice permits a
loopback/private numeric address and optional HTTP through the same gateway.
The permission cannot be reused for ordinary source traffic or a public-host
redirect. With Tor selected, private local-server requests fail closed rather
than silently connecting directly. Remote public requests use the selected
connection policy; an unavailable Tor connection pauses them.

AI preferences are local and separate from reader settings. API keys are stored
as AES-GCM ciphertext in Android's no-backup storage, with keys in Android
Keystore and associated data identifying both role and saved provider slot.
Changing an endpoint/protocol requires re-entering or explicitly removing its
existing key. The old single custom configuration and encrypted key remain
usable when upgrading, without being assigned to a newly added cloud provider. Keys, private
endpoints and inference consent are not included in reader-state exports or
subscription packs. No source ZIP contains credentials or signing keys.

Ordinary Context evidence uses the bounded reader-cache policy. Intermediate
media files are removed after completion or eviction, and idle temporary work
expires after 24 hours during cleanup. Committed reading checkpoints and saved
evidence are separate from those temporary files; saved Context baselines and
explicit model/media downloads remain protected. See [Context storage](CONTEXT.md#local-storage-and-saved-copies).

A declarative catalog pins source revisions, sizes/hashes, schemas, runtime/ABI,
template identities and minimum app version. The build signs it with the APK's
actual signer; the installed app verifies that certificate. The current preview
uses its existing Android debug signer. This signature implementation requires
an RSA APK signer. Production builds must supply their own APK/catalog release
signer through `ANTISOCIAL_SIGNING_STORE`, `ANTISOCIAL_SIGNING_ALIAS`
and the corresponding password environment variables. The source acquisition
manifest is a source snapshot, not itself a signed application release.

Downloads use private staging, storage admission, range checks, hash/structure
validation, complete immutable directories and atomic active pointers. A retained
previous signed pack supports rollback; independent text changes reuse the
unchanged embedding pack. Model packs never contain scripts or control runtime
selection. The separately APK-pinned text installer restores only its exact
supported native postimage; it does not run downloaded upstream code or compile
source on the phone. Source/license notices are preserved in
[AI model provenance](../licenses/ai-models-provenance.md) and the packaged
`ai-model-notices.txt` asset. No Mimir/Bifrost server bridge is used.

## Reproducing the checks

Tools, caches, model data, emulator state, builds and evidence belong under
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild`.
These are developer commands, not consumer setup requirements.

```sh
export ANTISOCIAL_BUILD_ROOT=/run/media/red/RedtopStorage/DarwinBuild/LiberBuild
python3 scripts/build_local_ai.py --host --reference-model
"$ANTISOCIAL_BUILD_ROOT/ai-foundation/native-build/host-cpu-antisocial-kv-v3/antisocial-text-reference" \
  --self-test "$ANTISOCIAL_BUILD_ROOT/ai-foundation/models/Ternary-Bonsai-2-4B-PQ2_0.gguf"
python3 scripts/build_local_ai.py --android x86_64
python3 scripts/build_local_ai.py --android arm64-v8a --vulkan
python3 scripts/fetch_sse_reference.py
bash scripts/embedding_native.sh android
```

The SSE host reference requires an external development venv containing pinned
`torch==2.9.0+cpu`, `numpy==2.3.4`, `tokenizers==0.22.1`,
`safetensors==0.6.2`, `transformers==4.57.1` and
`sentence-transformers==5.1.1`. With the five pinned files and reference Python
source acquired by `scripts/fetch_sse_reference.py` under
`$ANTISOCIAL_BUILD_ROOT/ai-foundation/sse-q4`, run
`$ANTISOCIAL_BUILD_ROOT/ai-foundation/reference-venv/bin/python scripts/sse_reference.py`.
That script verifies the reference source hash before running the published
`SSEQ.load` on the development host. It never runs on a phone.

Keep `PYTHONPYCACHEPREFIX` and Python/pip caches on the external build volume.
For the native parity run:

```sh
export RUSTUP_HOME="$ANTISOCIAL_BUILD_ROOT/rustup"
export CARGO_HOME="$ANTISOCIAL_BUILD_ROOT/cargo"
export CARGO_TARGET_DIR="$ANTISOCIAL_BUILD_ROOT/rust-target"
export TMPDIR="$ANTISOCIAL_BUILD_ROOT/java-tmp"
export PATH="$CARGO_HOME/bin:$PATH"
export ANTISOCIAL_SSE_TEST_PACK="$ANTISOCIAL_BUILD_ROOT/ai-foundation/sse-q4"
export ANTISOCIAL_SSE_REFERENCE="$ANTISOCIAL_BUILD_ROOT/ai-foundation/sse-reference.json"
export ANTISOCIAL_SSE_PARITY_REPORT="$ANTISOCIAL_BUILD_ROOT/ai-foundation/sse-native-parity.json"
cargo test --locked --release --manifest-path native/embedding/Cargo.toml
```

JNI core tests additionally use
`-Dantisocial.embedding.native.path=$ANTISOCIAL_BUILD_ROOT/native-host/libantisocial_embedding.so`.

Use `bash scripts/gradle.sh :core:test :app:assembleDebug
:app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug` for the app's
checks/builds. The acquired-model Android class is `SseNativeParityTest`, with
`-e antisocialLocalAiModels true`, a private test pack and the published-reference JSON
staged on the isolated emulator. The default private paths are
`files/local-ai/test/sse-q4` and `files/local-ai/test/sse-reference.json`;
`antisocialSsePack` and `antisocialSseReference` instrumentation arguments can override
them. Its report is `files/local-ai/test/sse-android-parity.json`.

Actual completed checks and remaining device prerequisites are recorded in
[the implementation ledger](AI_FOUNDATION_LEDGER.md). Compiled libraries, model
downloads and a plausible answer are each weaker evidence than successful
execution/parity tests, and are recorded separately.
