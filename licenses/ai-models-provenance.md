# Optional AI model and runtime provenance

anti social’s implementation remains GPL-3.0-or-later. Darwin is the rebuilt and
expanded routing donor. The optional model files and native dependencies retain
their own upstream licenses. This file records the selected foundation; it does
not grant ownership of upstream model names or imply author endorsement.

## Published text weights

All three selected files are published PQ2_0 GGUFs. They are downloaded unchanged;
no training, generic requantization, vision projector, draft or audio file is
part of the normal pack.

| Model repository | Immutable revision | File | Bytes | SHA256 |
| --- | --- | --- | ---: | --- |
| `prism-ml/Ternary-Bonsai-2-2B-gguf` | `683f7be2629342a50987627b40881e54761a2430` | `Ternary-Bonsai-2-2B-PQ2_0.gguf` | 517,312,992 | `d5f01a4ecaf49239653308ee732ad1a0c5f802c0ddb997549fc7b694c8b45928` |
| `prism-ml/Ternary-Bonsai-2-4B-gguf` | `8d6557168168f91ea6697a4a49ed2ef622b53adb` | `Ternary-Bonsai-2-4B-PQ2_0.gguf` | 1,146,465,120 | `d406b1e912c0d2a4c124ad10bf38a76ae662fdc4d24b176451403dc7b7f30409` |
| `prism-ml/Ternary-Bonsai-2-9B-gguf` | `5b2abb02362efbed46bbe204014b02837778b2c2` | `Ternary-Bonsai-2-9B-PQ2_0.gguf` | 2,416,851,840 | `7a89ab48f5038c704480f60781f9b8d4035351f5f6beb7d11fad80001fde23f9` |

The repositories publish Apache License 2.0 and distinct NOTICE files. The
unchanged license bytes are identical across these three revisions. They are
preserved in [Bonsai-model-APACHE-2.0.txt](ai/Bonsai-model-APACHE-2.0.txt), with
the exact [2B](ai/Bonsai-2-2B-NOTICE.txt), [4B](ai/Bonsai-2-4B-NOTICE.txt) and
[9B](ai/Bonsai-2-9B-NOTICE.txt) notices. These notices retain Prism ML, Inc.,
Alibaba Cloud/Qwen3.5 and the upstream optional DFlash donor attributions.
Created using Bonsai by Prism ML.

Primary source locations:

- [2B license at the pinned revision](https://huggingface.co/prism-ml/Ternary-Bonsai-2-2B-gguf/blob/683f7be2629342a50987627b40881e54761a2430/LICENSE)
- [4B notice at the pinned revision](https://huggingface.co/prism-ml/Ternary-Bonsai-2-4B-gguf/blob/8d6557168168f91ea6697a4a49ed2ef622b53adb/NOTICE.txt)
- [9B notice at the pinned revision](https://huggingface.co/prism-ml/Ternary-Bonsai-2-9B-gguf/blob/5b2abb02362efbed46bbe204014b02837778b2c2/NOTICE.txt)

The repository's apparent 0.8B metadata describes an auxiliary DFlash drafter,
not the 4B main text model. Its notice remains preserved even though anti social’s
normal download excludes that draft file. PTQ1_0 and other optional files are
not selected by this foundation's application catalog.

## Published English embedding pack

Repository:
[RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en](https://huggingface.co/RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en/tree/48de1fc899574177a7c2d0d4ae90bb902190935c).
All five files use revision `48de1fc899574177a7c2d0d4ae90bb902190935c`.

| Published file | Bytes | SHA256 |
| --- | ---: | --- |
| `embedding.q4_k_m.bin` | 7,935,720 | `af7f5fa63246dbf4b73aef4d6d28b8e3cae82f7476edaf7417f0219061158447` |
| `model_rest.safetensors` | 6,360 | `67f1caf874ce4621b8ed58e74b154623d83b6642a3bb958fcfaa83967182034b` |
| `tokenizer.json` | 711,396 | `d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66` |
| `config_sentence_transformers.json` | 296 | `a2c4f9036851e1800f7586652019ebb3ee614c1e970f887d7b2bdc26fa60bdad` |
| `modules.json` | 97 | `ae26f0648b6560f6b91a7445962504d88bfd090ffc3b822ce9dfaac4bbf44c4f` |

Total: 8,653,869 bytes. anti social preserves these bytes without training, rotating,
distilling, requantizing or creating a replacement model. The scalar native
reader, safetensors validation and JNI integration are anti social implementations.
The custom packed-row table is not GGUF/llama.cpp Q4_K_M. All 512 dimensions,
the learned head and the exact independent tokenizer are retained. Language
coverage is English; multilingual tokenization parity is not a claim of
multilingual retrieval quality.

The pinned model card declares `license: apache-2.0` and credits Rikka Botan.
There is no separate LICENSE file in that repository revision. The complete
unchanged card is preserved in [SSE-published-model-card.md](ai/SSE-published-model-card.md)
(SHA256 `a902c16dda0287d387889a2a16afa1deb60b9ba8506044750d2499f211dd93ae`),
and the packaged notice supplies the declared Apache 2.0 terms. Its benchmark
descriptions remain upstream claims, not anti social’s independent measurements.

The separate [published Python numerical reference](https://huggingface.co/RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en/blob/48de1fc899574177a7c2d0d4ae90bb902190935c/SSE_quantize.py)
is 6,041 bytes, SHA256
`fe749e0659aee48d874b308d89aef37239b8baf03e42e02dba0299f1dbc95752`.
Its exact initial source header is preserved in
[SSE-reference-header.txt](ai/SSE-reference-header.txt): Copyright 2026 Rikka
Botan, MIT License. The reference is used only on the development host after
hash verification. It is neither a runtime dependency nor downloaded executable
code on a user's phone. Do not conflate the model card's Apache declaration with
this separate source header's MIT declaration.

## Native runtime and donor changes

| Component | Source identity | License / local change |
| --- | --- | --- |
| QVAC Fabric LLM / llama.cpp / ggml | `tetherto/qvac-fabric-llm.cpp` at `bc47470138d892a45fedb037c2615cc12c54cded`; source archive SHA256 `c538dc1a88395e5a3bd623ec4acdc68485e78b12bc15fd3b2f955809093764ec` | MIT; one coherent generator core |
| PrismML llama.cpp donor | `PrismML-Eng/llama.cpp` at `11809184898113482c2f510fd13da669aaba1052` | MIT; narrow tied embedding/output Hadamard-v2 metadata and activation binding port in `native/local_ai/qvac-bonsai-v2.patch` |
| Bounded shader compilation | `native/local_ai/qvac-bounded-shader-build.patch` over the pinned QVAC source | Build-resource control; not a new model/cache quantizer |
| Hugging Face Rust tokenizers | crate `tokenizers` 0.22.1, crate SHA256 `6475a27088c98ea96d00b39a9ddfb63780d1ad4cceb6f48374349a96ab2b7842` | Apache-2.0; HTTP/default features disabled; exact published tokenizer JSON; parallelism disabled |
| SSE native CPU reader and bindings | `native/embedding`, `core/.../SseEmbedding.kt` | anti social GPL-3.0-or-later implementation of the published format, not new weights |
| Android controller / provider transport | `AiModelManager`, `LocalAiService`, `AiPreferences`, `AiRemoteProviders`, `NetworkGateway` | anti social GPL-3.0-or-later; optional, local-first control and scoped transport |

QVAC and the checked Prism donor contain identical MIT license bytes, preserved
in [QVAC-Prism-MIT.txt](ai/QVAC-Prism-MIT.txt). The unmodified QVAC pin rejects
the published model's Hadamard version 2. The donor patch adds the corresponding
metadata validation and actual output/embedding transform bindings, rather than
merely widening a version check. Existing packed-weight transforms are kept
separate from future attention-cache codec experiments.

Tokenizers retains upstream package-author attribution to Anthony MOI and
Nicolas Patry, and its exact
[Apache 2.0 license](ai/tokenizers-0.22.1-APACHE-2.0.txt). Other downloaded
Rust build/host/Android dependencies retain exact available LICENSE, COPYRIGHT,
COPYING and NOTICE files under the packaged
`ai-runtime-notices/embedding/` directory. Its `INDEX.json` records Cargo
versions/checksums, declared licenses, public attribution and notice hashes.
It is deliberately a conservative superset; undownloaded other-target lock
entries are identified separately. Two crates that publish declarations without
standalone license files retain their package declarations/source copyright;
the standard Apache/MIT texts are also packaged. The existing native build
script preserves QVAC vendor/runtime notices under `ai-runtime-notices/runtime/`.

The exact primary notice file hashes and original source URLs are recorded in
[source-notices-manifest.json](ai/source-notices-manifest.json). The application's
default `ai-model-notices.txt` asset includes the model notices and full primary
license texts, so attribution does not depend on downloading a model first.

## Verification and distribution boundary

`app/src/main/ai-catalog/catalog.json` pins the selected declarative model data;
`scripts/sign_ai_catalog.py` signs the generated packaged catalog with the actual
APK signer. Upstream SHA256s provide exact-byte identity and do not replace the
application release signature. Preview and production signer details are
documented in [AI_FOUNDATION.md](../docs/AI_FOUNDATION.md).

Native code updates ship through app updates. Source delivery must retain the
GPL implementation, Cargo lockfile, exact source pins, donor patches, build
scripts and upstream notices; the model weights are separately published
optional data. No downloaded Python/shell source executes on the phone. No
Mimir/Bifrost bridge is used. Downloadability, a compiled backend or a plausible
answer does not by itself establish phone performance or semantic fidelity.
Completed checks and concrete pending work are kept in
[AI_FOUNDATION_LEDGER.md](../docs/AI_FOUNDATION_LEDGER.md).
