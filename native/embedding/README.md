AntiSocial optional CPU embedding runtime
===================================

This is a native reader for the unchanged English SSE pack published by
RikkaBotan at revision `48de1fc899574177a7c2d0d4ae90bb902190935c`:
https://huggingface.co/RikkaBotan/quantized-stable-static-embedding-fast-retrieval-mrl-en

The `.q4_k_m.bin` filename does not designate GGUF or llama.cpp Q4_K_M. The
read-only mapping contains 30,522 rows of 256 packed bytes, followed by
little-endian per-row F32 scales. High nibbles precede low nibbles. Only accessed
rows are decoded into a single 512-element accumulator. Occurrences are preserved
for mean pooling, the complete learned DyT head runs, then the result is L2
normalized. Empty token sequences return typed no-content results.

Hugging Face Rust tokenizers 0.22.1 reads the exact supplied tokenizer JSON;
network/hub features are disabled. No Latin-only tokenizer, Python runtime,
training, downloaded executable code or expanded full matrix is used on Android.
This first reference path is portable scalar CPU code. It does not claim NEON
optimization, multilingual retrieval quality, or equivalence of embeddings to facts.

The provider belongs in the app's optional private AI worker. Model acquisition
and all remote inference belong to the application's NetworkGateway. This crate
performs no network operations. It has one active encoder operation per handle,
cooperative cancellation, a 1 MiB per-item input bound, and explicit close.
Weights must be immutable: updates activate a different complete app-private
directory atomically, never truncate active mapped files.

Build host and three stock Android ABIs:

    bash scripts/embedding_native.sh android

Development reference (external build-root venv containing pinned dependencies):

    reference-venv/bin/python scripts/sse_reference.py
    ANTISOCIAL_SSE_TEST_PACK=.../ai-foundation/sse-q4 \
      ANTISOCIAL_SSE_REFERENCE=.../ai-foundation/sse-reference.json \
      cargo test --locked --release --manifest-path native/embedding/Cargo.toml

The host-only script verifies the pinned Python reference SHA256, then runs its
published SSEQ.load, tokenizer, EmbeddingBag and DyT operations. Native parity
covers exact token IDs, empty handling, all coordinates, repeated tokens,
normalization, control characters, accents, Unicode/CJK, numbers, negation and
long input. This runtime emits 512 F32 dimensions; index compression is a separate
future measured policy with a different embedding-space identity.

Native implementation: GPL-3.0-or-later. Tokenizers: Apache-2.0. The model card
declares Apache-2.0; the reference Python source separately declares MIT and its
copyright. Preserve both upstream notices in the application model/source notices.
