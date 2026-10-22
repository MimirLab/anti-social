# Behind this post

The Info action opens a native drawer and automatically inspects available media.
Existing findings appear first; additional results arrive without moving the feed.
This feature works with AI disabled and makes no language-model calls.

## Work scheduling

Normal media delivery records only the final URL, media type, exact SHA-256 and
explicit `Link: rel=c2pa-manifest` references. Receipts are queued for coalesced
background persistence. No credential host or detection service is contacted by
this passive step. The existing bounded local container reader supplies immediate
declarations. Ordinary PNG/JPEG/WebP files without credential carriers or references
skip native certificate validation.

Opening Info starts one serialized inspection, limited to 25 seconds (45 with Tor),
64 media references, four acquisition requests including redirects, and 24 MiB of
network response bodies. Already cached complete video files may be inspected;
Info does not download entire videos. Video previews are separate image records.
Supplementary acquisition yields to foreground reading and feed refresh. Pixel
decoding waits while text inference or existing media tools are working. Closing
the drawer cancels its job. Native credential validation retains its eight-second
cancellation limit.

## Qualified first-pass capabilities

* Structured PNG text recipes: WebUI/A1111, connected ComfyUI sampling paths,
  InvokeAI, Easy Diffusion and NovelAI. A software name, arbitrary caption, unused
  workflow node or isolated `prompt` property does not establish generation.
* EXIF UserComment recipes in JPEG/WebP/TIFF; supported ASCII and Unicode records
  and NovelAI's JSON envelope. Format recognition remains bounded and conservative.
* NovelAI hidden alpha payloads and fixed-provider-key signatures in noninterlaced
  eight-bit RGB/RGBA PNG. Pixel decoding is capped at 4,194,304 pixels and 12 MiB
  encoded input. Animation is excluded. No repair, re-encoding or file mutation.
  The signed pixel data and Comment record are verified; other unsigned fields do
  not gain signature coverage. PNG text and hidden records remain distinguishable
  when their content differs; identical records share a presentation.
* External C2PA references supplied in the media response or top-level XMP. The
  application downloads bounded bytes through NetworkGateway, then the SDK checks
  them using an explicitly offline context. Embedded credentials retain precedence.
  Signature/file integrity and signer trust remain separate findings. Android 9+
  is required for this native validator; older supported Android keeps declarations.

Prompt details and verification explanations are expandable. A file-declared
recipe is distinct from a verified provider signature. Missing metadata never
establishes human origin, intentional scrubbing, or authenticity.

## Storage and reuse

Ordinary receipt/report storage is limited to 3 MiB, 256 acquisition receipts,
128 exact-byte analyses and seven days. Completed work and online misses are reused
for six hours. Offline inspection does not count as online recovery; changing the
network policy permits a fresh attempt. The app build code versions the analysis
cache. Byte hashes prevent URL changes and similar images inheriting a verdict.
Cache clearing invalidates in-flight publication. Empty delayed persistence cannot
recreate cleared evidence. No extra media scratch files are created.

Saved copies retain readable captured findings. Export/import preserves recipes
and downgrades signature authority; available local bytes can be checked again.
The offline library never follows credential references or downloads missing media.

## Deliberate boundaries

This pass does not include general camera/GPS extraction, guessed `.c2pa` companion
requests, WebP pixel verification, interlaced/high-bit-depth PNG pixel verification,
reverse-image/hash search, TrustMark models, statistical AI-image classifiers, or
external image uploads. No such detector or model is bundled. Those routes require
their own coverage, privacy, package-size and latency qualification.

Builds compile a small JNI adapter against the pinned SDK's C ABI with the existing
NDK; they do not rebuild or duplicate the C2PA engine. References, exact source
hashes and upstream licenses are in [media-info-provenance.md](../licenses/media-info-provenance.md).
