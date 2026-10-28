# Content boundaries in anti social

This guide details the content-processing layer of the wider protection system.
Start with [architecture](ARCHITECTURE.md) for the **Tor for all traffic** control,
separate Tor-required searches, gateway filters, source routing and how these
layers interact. [Reviewing and evidence](REVIEWING.md) maps claims to code and
dated tests, including user-reported device use. The protections below operate
on content retrieved through that shared gateway in either connection mode.

This pass contains hostile content without replacing the reader, source routing,
or extraction algorithms. It requires stock Android, without root or a companion
installation. NetworkGateway still owns all app networking, including redirects,
media, updates and Tor policy. Original downloaded bytes remain unchanged.

## Workers and authority

| Work | Boundary |
| --- | --- |
| Ordinary picture decoding and PDF display | `ContentSandboxService`, an unexported Android isolated service with no permissions of its own |
| Article extraction, PDF text, and picture/PDF preparation for OCR | A second isolated service, `DocumentSandboxService`, separating ordinary background document work from the visible picture queue |
| HEIF/AVIF image containers | `PlatformImageService`, a separate ordinary process using Android's existing media decoder path; it shares the app UID and is **not** a permission sandbox |
| Video-scene sampling | `PlatformVideoService`, a descriptor-only ordinary app process with an independent watchdog; **not** permissionless. [Scheduling, acquisition limits and qualification](VIDEO_FOOTAGE.md). Settled visible cards can request bounded sampling; no global scan. |
| Local inference, translation, OCR recognition and audio processing | Existing private workers and verified model packs; these are not newly claimed to be permissionless |

Android denies isolated UIDs access to `media.player`. On the tested Android 15
image, an AVIF decoded by the original reader failed inside an isolated service
because the platform's HEIF decoder could not obtain MediaMetadataRetriever.
Dropping this format would be a regression. The compatibility path recognizes
ISO-BMFF image brands from bounded file headers, receives a read-only descriptor,
and supports only image operations. It receives no URL, filesystem path, repository
or credentials. It does not activate in response to an arbitrary decoding error.
The platform's codec availability remains device dependent.

Trusted code opens one read-only input descriptor. Workers return bounded pixels
or text through a pipe, with no writable cache descriptor. The parent validates
dimensions, stride, byte count, color parameters, text size, JSON nesting, required
field types and enum values before accepting the result. This matters because
Gson can bypass Kotlin constructors and deserialize an unknown enum as null.

An independent watchdog can terminate a stuck decoder. Cancellation also requests
termination on a Binder thread and drops the binding; coroutine cancellation is
not treated as proof that native parsing stopped. A subsequent read can bind a
fresh worker. Controlled article rejections retain their source failure status
and reuse a healthy worker for the next public-copy attempt.

Workers expire after a minute of inactivity. Cached decoded pictures bypass IPC.
For a visible uncached picture, binding can overlap its existing gateway download;
warming does not open content, connect to a site or start an AI model.

## Preserve the picture and the document

The picture path retains the original stream decoder, sampling, alpha state,
premultiplication, density, raw pixels and color space. Parameterized custom RGB
profiles cross the boundary as bounded numeric data. Unrepresentable transfer
functions are converted to a named linear float space, rather than relabelled as
sRGB. Android 8 can expose an indexed GIF without a public Bitmap.Config; the
worker expands its palette to ARGB while preserving interpreted pixels.

Bitmap.sameAs also compares native color types and color-space representations.
These can differ despite equivalent public bitmap properties. Qualification
therefore checks raw pixel storage and interpreted ARGB pixels as well as public
properties; custom color coordinates are checked with a floating-point tolerance.
Saved source files and cryptographic provenance checks use the original bytes.

Readability still sees a complete document. A quote-, comment- and raw-text-aware
admission scan runs before the relevant DOM allocations: native articles, shared
HTML/feed extraction, ordinary RSS/Atom, and local structured/web extraction.
This is an admission check, not a new text extractor or HTML renderer. Existing
paragraphs, lists, tables, citations, attribution and archive identity checks remain.
Not every small fragment parser or source-specific adapter is moved into a worker.

Limits include 4.2 million returned pixels, 4 MiB of JSON output with nesting at
most 32, and existing article text limits. HTML admission limits extreme nesting,
tag counts and input size. Oversized or pathological material is rejected explicitly;
it is not reported as a healthy empty source.

Ordinary menu PDF text uses the same PDFBox extraction and total scratch allowance
as before, with its 8 MiB scratch now in the disposable process. Model recognition
gets decoded pixels instead of an untrusted compressed picture or PDF. Legacy
compressed-input model-worker commands are disabled in release builds.

Article transfer files are private, deleted in finally blocks, and limited to two
staged inputs. Cleanup expires orphans after 15 minutes and bounds that directory
to 32 MiB, accounting for active reservations. Clearing ordinary cache removes
inactive transfer files without touching saved records or explicit offline downloads.

## Following a link is a decision

`FetchPurpose` distinguishes supplemental reading, deliberate opening, media and
declared API operations. Supplemental reading screens both the supplied URL and
each cleaned, rewritten or redirected destination before another connection.
Recognized unsubscribe, password-reset, confirmation, account-removal and invitation
acceptance endpoints wait for a deliberate open. Duplicate action parameters,
encoded path separators and common endpoint suffixes are considered.

This policy is used for Context article/comment follow-ups and generic local
publisher/menu retrieval. Ordinary guides and articles discussing an action are
not rejected merely because their prose contains that word. The source links
remain available. Known API operations and signed media retain their specific
rules; deliberate opens still pass through filters, origin checks and selected Tor.
This recognizes known action shapes, not every possible side effect on the web.

## Cleaning without a slow reader

RE2/J is used for the qualified ASCII subset of the Darwin ClearURLs rules.
Compilation alone does not establish equivalence: Java's Unicode case folding
and end-of-line behavior differ. Unicode/control-containing inputs and unsupported
expressions retain Java matching with bounded input and character-access work.
Network cleaning fails closed on a work-limit failure instead of silently skipping
protection. URLs exceeding 64 KiB are outside the cleaning allowance.

A necessary-literal precheck avoids regex work on impossible rules. It derives a
required substring from supported expression syntax, ignores groups/classes,
accounts for optional/repeated atoms, and disables pruning for root alternations
or unfamiliar escapes. It does not infer a rule's scope from its name or guessed
domain. Full regex matching still decides whether the rule applies.

All bundled expressions are qualified in tests. Differential tests compare complete
cleaning results against Java, including redirects, exceptions, signatures, Unicode
keys and final newlines. Hostile repetition and unsupported-pattern limits have
dedicated regressions. Signed media parameters remain intact.

## Quiet presentation protections

Account labels make directional overrides visible and can flag mixed Latin letters
with Greek/Cyrillic lookalikes. This is a small, pinned Unicode 18 data subset, not
a comprehensive identity detector or ownership judgment. Normal RTL text, combining
marks, joiners and emoji remain readable. Stored names, evidence and exports retain
the original text. Destination labels expose the ASCII hostname, including punycode.

Credential dialogs temporarily hide untrusted overlays on supported Android versions.
Consequential credential, install and private portability controls reject obscured
touches. Ordinary reader windows retain their normal floating-tool behavior, and
accessibility actions remain usable. Copied signed or credential-bearing URLs are
marked sensitive for supported clipboard previews, preserving the copied value and
paste behavior. This flag does not make a clipboard private from other software.

## Qualification and practical limits

Validation covers normal and malformed images/PDFs, exact image pixels and extraction
output, actual isolated UID/file/permission boundaries, forced stalls, cancellation,
recovery, hostile returned fields, cache cleanup, native screens, and the existing
feed, Library, Context, discovery, privacy and optional-AI regression journeys.
Android 8 and Android 15 emulator checks distinguish platform format support from
app regressions. Performance comparisons use the same authored feed and gestures
and a fixed URL corpus; emulator measurements are not physical-phone FPS or battery
claims. Detailed final counts and measurements are retained in the release evidence.

This is not isolation of every native component. The HEIF/AVIF compatibility worker,
platform playback/audio paths, model workers and existing credential-validation SDK
retain their documented authority. Remote provider availability and OEM decoder
behavior require their own qualification. These protections reduce specific attack
surfaces without promising that arbitrary public content is harmless.

## Primary references and notices

- [Android isolated services](https://developer.android.com/guide/topics/manifest/service-element) and [PDF rendering guidance](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer).
- [RE2/J behavior and limitations](https://github.com/google/re2j/tree/re2j-1.8).
- [Unicode security mechanisms](https://www.unicode.org/reports/tr39/) and [confusables data](https://unicode.org/Public/security/latest/confusables.txt). The generated subset records its source digest.
- [HTTP safe methods and automated retrieval](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.1).
- [Android tapjacking guidance](https://developer.android.com/privacy-and-security/risks/tapjacking) and [clipboard handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling).

RE2/J and Unicode notices ship in `assets/hardening-notices`. Darwin and existing
GPL and dependency notices are preserved.


### Recorded performance comparison

With builds/tests otherwise idle, the same desktop JVM corpus (1,000 URLs,
5,000 warm-up calls, seven measured batches) produced identical output digests.
Median cleaning time was **116.6 ms before / 52.7 ms after**. This measures the
bundled cleaner, not web latency or a phone-wide speedup.

The same API 35 authored feed (240 original posts collected into 120 cards,
eight scroll gestures, no media or live lookups) measured **8.35 s / 8.08 s** to
open and **4.73 s / 4.61 s** for the gesture sequence. Frame p95 was **333 / 342 ms**;
this heavy emulator fixture remains slow in both builds and does not establish
physical-phone smoothness. It provides no indication of a new feed-path slowdown.

A separate alternating original/worker image decode fixture measured approximately
**6.9 ms / 10.4 ms** warm median, with **695 ms** for its first cold worker decode.
Binding overlaps existing visible-image downloads where possible; decoded cache
hits bypass the worker. These startup and IPC costs are real and are retained in
the evidence rather than described as free isolation.
