# Feed performance qualification

## Post-0.38.70 investigation: reject an unhelpful pattern cache

The current released APK's cold cached-gallery trace separates local preparation
from source delivery. The first grouping consumes **3.931 seconds of thread CPU**
and **5.475 seconds of wall time** under sampling; reading-alias preparation uses
**996 ms of thread CPU**. Repeated Nitter mention and outward-link pattern
compilation account for approximately **105/89 ms** of sampled CPU. These traced
figures identify work to investigate; profiling overhead makes them unsuitable
as ordinary first-card latency measurements.

A generic mention-scanner prototype is rejected before promotion. Android ICU
can match the literal `ss` against `ß` and `ffi` against `ﬃ`; a generic handle
character class followed by string equality does not preserve that behavior.
The [ICU case-insensitive matching documentation](https://unicode-org.github.io/icu/userguide/strings/regexp.html#case-insensitive-matching)
explains the distinction. An ordinary-shell Android runtime probe demonstrates
it, then the full-APK native regression independently rejects the prototype.
The existing 0.38.70 literal expression passes the same strengthened check.

A second prototype preserves the exact literal expressions, reusing up to 128
patterns inside the existing operation and its shared accounting allowance.
It also hoists three constant Nitter patterns. The complete official 0.38.70 →
candidate → official 0.38.70 comparison produces these untraced observations:

| Observation | Official before | Candidate | Official after |
| --- | ---: | ---: | ---: |
| Open → first complete focused card | 9.130 s | 9.448 s | 9.887 s |
| Open → confirmed complete family | 9.463 s | 9.740 s | 10.221 s |
| Warm reentry → complete card | 1.667 s | 1.717 s | 1.614 s |
| Separate local grouping operation | 4.147 s | 4.151 s | 3.950 s |

Every first focused card already contains its complete four-original family and
six distinct pictures, with zero child-continuation reads. Ordered grouping
identities and all 991 content-observation digests agree across the separate
local-stage replays. Both new native contract methods pass on all three APKs.

This experiment **does not establish a useful whole-feed speed gain**. The
literal cache and constant-hoisting runtime edits are therefore removed too.
The retained change is regression coverage for exact mention boundaries,
Unicode case folding, source/destination scope, original-link identity and
independent readers. No app version, matching rule, cache allowance, network
behavior or release artifact changes as a result of this investigation.
These emulator observations do not establish phone latency or energy results.

The retained test-only change passes a fresh full JVM run: **2,626 cases, 2,622
passes, four optional skips and zero failures/errors**. Both new contract methods
also pass against the unmodified official APK on API 35 before and after the
candidate, then on minimum API 26 in **33.457 seconds** with **two actual passes,
zero skips and zero failures**. Returned method identities are checked separately
from the runner footer. The native reader starts afterward without a fatal
startup error, and its actual screen is inspected. The API 26 engineering session
uses ordinary shell authority and stops only its own diagnostic emulator.

A separate production-Gateway observation qualifies the already installed
TikWM post route for one explicitly supplied public recording: one JSON response,
one accepted post, **1.648 seconds** elapsed. Its declared plain/watermarked
renditions are **22,196,836/20,530,484 bytes**. The current parser already prefers
the latter; switching by the field name alone would choose the larger file in
this case. These are provider-declared full-file sizes, not measured sampler
latencies or proof that the alternate bytes belong to a retained rendition.
Further acquisition work must qualify the recording relationship and measure
the actual bounded sampler. This observation activates no new route or ordering.

## 0.38.70: current video offers without removing discovery paths

A precomputed global video offer can become stale while new scene observations
arrive and the feed's grouping worker is still finishing. The reader previously
consumed that offer before consulting its existing focused planner. Native
negative controls reproduce the resulting premature next-recording read.

The global offer now carries the minimum video-bank revision observed before its
projection starts. A current offer retains its cheap direct path; a stale offer
uses the existing focused planner over current observations. It does not wait
for the whole feed to finish grouping. Catalog evidence alone does not cancel
an active pair's second recording. Existing input/viewport/privacy cancellation,
foreground/playback preemption and resource-admission checks remain in place.

When no current request is available, the coroutine suspends on the actual raw
offer, resolver and revision. Filtering out a stale offer must not make a nonnull
raw offer look like a perpetual update. A revision-only acknowledgement can wake
the reader and nominate that same recording again. The controls check both idle
lookup stability and this retained acquisition capacity.

The investigation rejects a broader platform-subset pruning prototype. Another
edition on a platform already represented in a family can still supply unique
content. Existing global nominations, original keys, comparison allowances and
all source paths therefore remain intact. No sampling threshold, range protocol,
decoder authority, network concurrency or byte budget changes.

An untraced official 0.38.69 → candidate → official 0.38.69 native comparison uses
the same 991-post captured followed window. All three runs verify the complete
six-original family, all four required literal caption variants once, and the
existing two-pixel body-adoption bound. Each continues observing the same card
for three seconds after adopting its body.

| Native observation | 0.38.69 before | Candidate | 0.38.69 after |
| --- | ---: | ---: | ---: |
| Open → first focused card | 8.949 s | 8.644 s | 8.310 s |
| Open → complete focused family | 30.163 s | 22.194 s | 21.918 s |
| First card → complete family | 21.214 s | 13.550 s | 13.608 s |
| Open → complete adopted body | 33.548 s | 25.503 s | 24.566 s |
| Recordings started | 4 | 4 | 4 |

This comparison does **not establish an overall speedup**. The later control is
about as fast as the candidate; the earlier control spends substantially longer
acquiring its first recording. All four recording candidates remain available.
Source delivery and the approximately thirteen-second late-family gap remain
open work. These three emulator observations are not phone p95 or energy results.

A temporary native read probe records 20/31/31/45 decoder callbacks for the four
recordings. Three have no callback containing multiple missing cache blocks;
the fourth has only one. Blind coalescing therefore offers little in this case.
The measured 128 KiB maximum agrees with Android 15's
[FUSE callback buffer](https://android.googlesource.com/platform/frameworks/base/+/android-15.0.0_r36/core/java/com/android/internal/os/FuseAppLoop.java#146).
A simple offline replay of larger 512 KiB cache blocks also flags additional
failed reads under the existing 4 MiB allowance. Neither protocol experiment is
implemented. The temporary probe is removed from production source; its APK,
raw callbacks, source and rejected prototypes remain external under
`evidence/video-proof-latency-20261029`.

## 0.38.69: less allocation during literal matching

Native sampling of the 0.38.68 cold cached-file feed identifies caption
matching as a substantial part of the initial wait. In this replay, retained
pictures finish decoding before the first grouping pass completes; adding
network reads would not address that wait.

The pinned [Kotlin 2.2.21 implementation](https://github.com/JetBrains/kotlin/blob/v2.2.21/libraries/stdlib/jvm/src/kotlin/text/regex/Regex.kt#L356)
creates another native matcher when advancing each `findAll` result. Caption
tokenization now advances one call-local matcher instead. Numeric whitespace
replacement also reuses one matcher within the call. The same change covers
fusion word preparation, comparison context and decorative-copy preparation.
Compiled expressions, Unicode rules, token limits, UTF-16 offsets, numeric
operators, case handling and all admission guards stay unchanged. No mutable
matcher or source text is shared between readers or retained after the call.
There is no additional fetch, model, dependency or persistent cache.

An untraced control → candidate → control comparison uses the same captured
991-post followed window, exact memberships and retained picture bytes on the
API 35 emulator. Each application replay starts a new process, opens the actual
native feed, waits for the focused family and returns through Subscriptions →
Following. A separate process measures local stages. Owned compiler daemons are
stopped before these serialized measurements to reduce host-memory interference.

| Measured stage | 0.38.68 before | Candidate | 0.38.68 after |
| --- | ---: | ---: | ---: |
| Local grouping | 4.604 s | 4.167 s | 4.657 s |
| Open → first visible focused card | 10.255 s | 9.712 s | 10.479 s |
| Open → complete focused family | 10.572 s | 10.011 s | 10.838 s |
| Warm return → complete focused family | 1.617 s | 1.469 s | 1.651 s |

Every first visible focused card already contains the four-original family.
The replay retains six distinct pictures and performs zero publisher-continuation
reads. All three local-stage results have identical ordered membership digests
for 822 groups and identical content-observation digests for all 991 originals.
Existing request planning also stays at 12 video and zero gallery requests;
those counts describe planning, not new network operations by this change.

These three observations show a modest improvement in this workload: local
grouping is about 9.5–10.5% lower and complete-card observation is 0.56–0.83 seconds
earlier than the surrounding controls. They are not a latency distribution,
physical-phone result, exact frame-paint measurement or proof of instant merging.
The approximately ten-second cold wait and source-dependent video-proof delay
remain open work. Earlier whitespace-scan and parse-cache prototypes are rejected
because their controlled replays show no consistent complete-card benefit.

The native gallery engineering check additionally offers an opt-in
`antisocialFeedWorkTrace` flag for matching-job and image/video revision events.
It defaults off and is excluded from the timing comparison. Captured inputs,
screenshots, raw timings, traces and rejected prototypes remain in the private
external build area under `evidence/feed-first-card-20261029`.

## 0.38.54: scoped image updates and reusable comparison records

Native sampling of the released 0.38.53 cached-picture reader found substantial
repeated `VisualFrames.decode` work during background matching. The global
image revision also invalidated the whole-feed matcher for unrelated decoded
pictures. An avatar or business image can update that bank without changing any
image used by the feed's candidate posts.

Counterpart matching now observes the effective fingerprints of its candidate
media. Equal state is suppressed after projection, using the pinned coroutine
library's [equality and conflation contract](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/flow/StateFlow.kt).
Relevant observations, contradictory aliases, expiry, eviction and clearing still
update that projection. Source, follow and membership changes retain their own
existing cancellation boundaries. No detached queue or additional polling is
introduced.

Each pass also uses one frozen observation set for both captured post fields and
its comparison callback. A live-bank update cannot change half of a running
pass's evidence. Only bounded bank references are copied under the image-store
monitor; URL resolution and comparison run outside it on the worker dispatcher.
The next pass receives the latest relevant state.

Decoded comparison records reuse the existing operation-local fusion workspace.
Exact input strings and coarse/detailed modes have separate keys. A parsed header
cannot certify its detailed raster. Null results are reusable too. At most 512
decoded entries share the existing estimated 8 MiB allowance with text and URL
preparation, and are discarded on return, failure or cancellation. Comparison
thresholds, source editions and pixel-conflict guards are unchanged. No bitmap,
persistent cache, model, native library, permission or network call is added.

Three process-restarted, untraced samples per APK used the same private 765-post,
25-source pool on the same API 35 emulator, serially without a compiler or another
native batch. Sixty-one attachment observations on 44 posts came from actual
earlier decodes, transferred only by exact source URL. The diagnostic imports
nothing into Room and enforces an unavailable Tor policy during the measured
local stages. It performs no image download or invented pixel match.

| Local grouping stage | 0.38.53 | Candidate |
| --- | ---: | ---: |
| Three samples | 4.484 / 4.204 / 4.414 s | 4.264 / 4.174 / 4.207 s |
| Median | 4.414 s | 4.207 s |
| Complete ordered groups | 617 | 617 |

All six complete membership/order and content-observation digests match, and
every original remains represented. The observed median decrease is about 4.7%;
the sample ranges overlap. This is a small local CPU comparison, not a measured
full-reader or physical-phone speedup. The scoped-update controls independently
establish which unnecessary matching passes can be omitted. The exploratory
opening/scroll traces include tracing overhead; their frame samples do not
establish improved phone FPS. Android also distinguishes
[first-draw frames and frame deadlines](https://developer.android.com/reference/android/view/FrameMetrics).

Separate native replays preserve the preceding 16 captured announcement families
and 49 originals, including one reading card, one checked shared passage, every
distinct attachment and complete source text/media in Library saves. Inputs,
screens, rejected fixture expectations and raw results remain external under
`evidence/routing-profile-20261028`; private reader material is not bundled.

## 0.38.48: initial delivery and collection-cost control

An empty source window now publishes its first coherent Room rows and follow
memberships before a bounded initial coverage pass. Four opt-in native refreshes
against existing public subscriptions delivered their first nonempty snapshot in
0.637–0.808 seconds after ordinary-cache purge. Full source checks took roughly
50–90 seconds, including unavailable routes. These are emulator Room-observation
times, not first paint, physical-phone latency or guaranteed provider performance.
The first two candidates still stopped before a required counterpart; the later
five-page Facebook bound recovered it. All intermediate evidence is retained.

The gallery/ownership fixes were also compared with 0.38.47 using the same private
738-post, 25-source snapshot and exact memberships. Three process-restarted,
untraced samples per APK ran serially on the same API 35 emulator, without a
concurrent compiler. No posts were imported into Room or fetched by this CPU
diagnostic; every original remained represented.

| Local grouping stage | 0.38.47 | Candidate |
| --- | ---: | ---: |
| Three samples | 10.860 / 10.796 / 10.834 s | 11.209 / 11.332 / 10.834 s |
| Median | 10.834 s | 11.209 s |
| Complete groups | 619 | 618 |

This workload costs about 3.5% more grouping time and collects an additional
announcement. It does not establish faster large-pool preparation or scrolling.
The new delivery path addresses acquisition blocking independently of that
existing local CPU cost. It adds no database, model or network call to individual
post comparisons. Raw measurements and source-review captures stay external under
`evidence/cold-feed-recovery-20261027`.

## 0.38.28: faster reading-signature preparation with identical results

The current 0.38.27 observation-stage trace still attributes most sampled work
to text preparation, with another substantial part in per-byte formatted hex.
The replacement scans whitespace directly and writes the SHA-256 hex digits
directly. It retains normalization, trimming, content framing, source references,
media identity and all matching/contradiction rules. It adds no retained text
cache, network request, model, dependency or native runtime.

Compatibility includes the runtime's Unicode behavior. Android's regex character
classes are Unicode-aware by default, unlike desktop Java's defaults. A one-time
NEXT LINE check preserves that distinction; `Character.isWhitespace` alone would
also consume other controls the previous expression preserves. The helper keeps
the old `[\s\p{Z}]+` replacement semantics, including supplementary and malformed
UTF-16 boundaries. See [Android's Pattern contract](https://developer.android.com/reference/java/util/regex/Pattern#UNICODE_CHARACTER_CLASS).

Direct JVM comparison covers all 1,114,112 codepoint values and mixed/control
boundaries. Native API 35 checks compare the complete existing signature recipe
over the same generated Unicode range with the old runtime regex as oracle.
Fixed signature vectors retain leading zero bytes, embedded controls and Unicode
normalization. The Android check confirms NEXT LINE is whitespace there.

Three process-restarted untraced samples per APK used the same private 517-post,
24-source snapshot on the same API 35 emulator, without a concurrent compiler.
All six retain 468 ordered groups, the same group membership/order digest, and
the same digest of all 517 observation keys, signatures and source timestamps.

| Local CPU stage | 0.38.27 | Candidate |
| --- | ---: | ---: |
| Observations, three samples | 1.245 / 1.227 / 1.242 s | 0.737 / 0.742 / 0.746 s |
| Observation median | 1.242 s | 0.742 s |
| Grouping median | 6.140 s | 5.768 s |

Observation preparation takes 40.3% less time in this workload. A separate single
paired native 240-post benchmark retains the same 120 cards, initial card and
cards after eight swipes. Opening changes from 3.980 to 3.572 seconds; the
eight-swipe wall time remains similar at 4.916 / 4.966 seconds. The 24-frame samples
still have stalls, with p95 350 / 367 ms. This run does not demonstrate improved
scrolling or phone FPS. It is a warm authored fixture; neither measurement is a
complete cold-launch or live-source latency claim.

Further exploratory native pairs use 100 authored posts each in English,
Chinese, Arabic, emoji and mixed text. Each pair retains its complete group and
observation digests and takes less observation-stage time with the candidate.
A later original-snapshot control returns to 1.226 seconds on the baseline and
0.710 seconds on the versioned candidate, retaining the same output digests.
These controls support the measured mechanism; they are not broad language or
physical-device benchmarks.

Raw traces, frozen APKs, the private external fixture, exact-output checks and
native screens are retained under `evidence/reading-preparation-20261025`.
No private post material is bundled or published. The opt-in diagnostic now
records an observation digest and can trace one selected stage without tracing
the others. Those diagnostic flags default off.

## 0.38.26: reuse URL normalization during one complete grouping

Repeated source, caption and media URLs now reuse their normalization result
inside the existing operation-local fusion workspace. The exact input string is
the key, including case and parameters; the normalization rules are unchanged.
Null results are reusable too. The new map shares the existing estimated 8 MiB
workspace budget, has a 2,048-entry cap, and is discarded on return, cancellation
or failure. It adds no persistent browsing cache or network request. Independent
threads retain independent workspaces and nested work shares the current one.

Three process-restarted untraced stage measurements per APK used the same private
517-post snapshot, 24 sources and API 35 emulator, with no concurrent compiler.
Every run produced the same 468 ordered groups and membership/order digest.

| Grouping stage | 0.38.25 | Candidate |
| --- | ---: | ---: |
| Three samples | 6.859 / 6.800 / 6.841 s | 6.116 / 6.214 / 6.066 s |
| Median | 6.841 s | 6.116 s |

The measured stage takes 10.6% less time in this workload. No match threshold,
source edition, contradiction guard or image-identity requirement changed.
These measurements do not establish physical-phone, live-provider, actual-paint
or complete cold-launch performance. The remaining reading-preparation and
image-revision work remain separate targets. Raw results and the private fixture
stay external under `evidence/feed-url-work-20261025`.

## 0.38.24: skip waits on missing cache files and reject distant galleries early

The cold image-comparison rebuild previously delayed 40 ms after every unknown
media URL, even when its file was absent. It now delays only after actual
cached-file work and still yields between URLs. Its 2,048-URL bound, local-only
retrieval, two-decoder limit, downsampling, cancellation and matching rules are
unchanged. The existing native case scans 171 URLs with only the last two files
available: both versions recover that late pair. Whole instrumentation-case
time changed from 9.604 to 1.957 seconds on the same API 35 emulator. This is a
single case comparison, including its setup, rather than a phone or full-reader
startup measurement.

Native CPU sampling of a private 517-post snapshot also found repeated original
identity parsing during gallery comparisons, mostly for posts outside the
gallery's time window. The existing time and media exclusions now precede the
existing identity proofs. All proofs and thresholds remain mandatory.

Three process-restarted, untraced diagnostic runs per APK used the same snapshot,
24 sources and memberships on the same API 35 emulator, without a concurrent
compiler or another instrumentation batch:

| Grouping stage | 0.38.23 | 0.38.24 |
| --- | ---: | ---: |
| Three samples | 8.655 / 8.143 / 8.155 s | 6.847 / 6.732 / 6.599 s |
| Median | 8.155 s | 6.732 s |
| Complete ordered groups | 468 | 468 |

The group-membership/order digest matches in all six native runs and both frozen
desktop-core runs. Desktop content-signature digests also match. No private post
text, reading history or captured fixture is bundled or published. The opt-in
`CachedFeedProfileTest` requires an external fixture on an explicitly disposable
installation; it neither imports posts into Room nor makes acquisition calls.
Its initial offline-policy assertion caught a posted Tor-stop race in the test
setup; the corrected setup completes that stop on the main thread before
installing and checking the offline policy.

These measurements isolate local CPU stages. Text projection and content
observations still take about two seconds in the initial stage samples, and
image-revision cancellation can restart work. They do not establish that the
complete cold-reader delay is solved. The earlier hypothesis that inherited
reading-history comparisons dominated this snapshot was not supported: they
took about 22 ms with its captured read keys. Raw traces, intermediate failures,
APK snapshots and comparisons remain external under
`evidence/cached-feed-profile-20261025`.

## 0.38.23: reject conflicts before repeated shared-text analysis

Native method sampling found repeated ICU word scans while comparing nearly
identical captions that were subsequently rejected for a changed bulletin
number. Alignment now checks the existing unmatched-context conflicts before
scanning the shared passage's lexical richness. Both checks remain mandatory.
No matching threshold, source window, candidate limit, original edition or
contradiction guard was removed. It adds no cache, network request or model call.
Readers still receive one completely checked collection.

The same opt-in 240-record X/Facebook fixture ran on the same isolated API 35
x86_64 KVM/SwiftShader emulator, serialized without a concurrent compiler.
Both runs produced the same **120 merged cards**, the same initial card and the
same cards after eight scripted swipes.

| Native measurement | 0.38.22 | 0.38.23 |
| --- | ---: | ---: |
| Core grouping, median of three | 6.830 s | 2.710 s |
| Opening until first merged card | 8.765 s | 4.196 s |
| Eight swipes with test synchronization | 4.830 s | 4.966 s |
| Sampled frame duration, median | 147 ms | 150 ms |

Opening improved in this workload; scrolling did not improve in this comparison.
The window frame sample contains only 24 frames/run and still includes stalls.
These are authored stress-fixture measurements with a warm runtime, not cold
process-start latency, live-provider latency, phone FPS or battery measurements.
The richer real cached-image startup path remains a separate profiling target.

Desktop group/original-URL SHA digests also match at 80, 240 and 800 records.
The 800-record workload remains constrained by the existing broad-candidate
safety bound; it gains no speed in this comparison. Raw evidence is retained
externally under `evidence/feed-startup-20261025`, including the unchanged
baseline APK/core snapshot, native traces, untraced results and actual screens.

The full JVM run completed 2,159 cases with zero failures/errors and one optional
model-weight skip. The Android batch covered 122 named checks: 120 passed, two
opt-in live-Tor/NASA-neighborhood checks skipped, zero failures. It includes
reading anchors, frozen editions, source failures and cancellation, filters,
source-window invalidation, privacy and native public comment/history controls.
The opt-in benchmark passed separately. Release assembly and lint pass.

The existing benchmark now has optional `antisocialTraceFeedPreparation` and
`antisocialTraceFeedOpening` flags for native method sampling. They default off;
traced runs are diagnostic and excluded from the timing comparison above.

## Earlier 0.36.0-rc5 qualification

This change removes repeated local work from feed opening and scrolling. It adds
no network requests, language-model calls, or new reader controls. Darwin routing,
progressive account discovery, late image/source enrichment, privacy protection,
and the existing matching thresholds remain in place.

### Work moved off the reading path

- One combined-feed Room snapshot supplies both the reader and its cross-platform
  counterparts. Scoped feeds still retain their own window and source memberships.
- Access-time updates are compared before JSON decoding. Reading dwell no longer
  reparses and republishes an unchanged thousand-record window. Payload changes
  and membership-only changes still reach the reader.
- Cross-post grouping reuses pure caption preparation, comparison fragments and
  presentation results within one operation. The workspace has an estimated
  shared 8 MiB budget and a 2,048-entry limit per map; it is discarded in a
  `finally` block. It does not retain another browsing-history cache.
- Original-post identity is computed once per immutable record. Its transient
  field does not change saved/exported JSON, equality, or copied-post identity.
  URL patterns are compiled once rather than repeatedly during card rendering.
- A newer source/image snapshot cancels obsolete grouping at cooperative
  checkpoints. Readers receive a completely checked collection, never partial
  merge results.
- Source and fused-caption change detection runs in the background when source
  material changes. Viewport movement only controls when those changes may be
  adopted; the visible card and reading anchor stay frozen.
- Saved-copy verification starts after the reader chooses Save, on a background
  dispatcher. The open sheet retains that captured post, replies and completed
  proof even when another source arrives. Stable link lists,
  filter decisions and read-key sets are reused across scroll recompositions.

### Reproducible workload and limits

`FeedPerformanceTest` is opt-in and requires both
`antisocialIsolatedFixtureDevice=true` and `antisocialFeedBenchmark=true`. Use a
throwaway test installation, never a personal reader. It supplies 240 authored,
long-caption X/Facebook posts with no media requests. Each pair is one bulletin;
changed bulletin numbers must remain separate. Both versions must produce 120
cards with the same originals. The test opens the native combined feed, performs
eight identical touch swipes, records Android window frame metrics, and captures
the actual screen. It restores preferences and removes its own fixture rows.

After installing the debug app and Android test APK on an isolated emulator:

```sh
adb shell am instrument -w -r \
  -e antisocialIsolatedFixtureDevice true \
  -e antisocialFeedBenchmark true \
  -e antisocialBenchmarkLabel measurement \
  -e class app.antisocial.android.FeedPerformanceTest \
  app.antisocial.android.test/androidx.test.runner.AndroidJUnitRunner
```

JSON and PNG evidence is written under the app's external `files/validation/`
directory, in `feed-performance`. This test is excluded from ordinary runs by
its two opt-in flags.

The native comparison uses the original `1776e30` debug APK and the optimized
debug APK on the same API 35 x86_64 emulator (KVM, four cores, 3 GiB,
SwiftShader). Timed runs are serialized without a concurrent build. These are
stress-fixture wall times, not expected ordinary-feed times or phone FPS. The
workload deliberately puts many nearly identical long captions through the
changed-number safeguards. Cold emulator/JIT and host scheduling affect results.

| Native stress-fixture measurement | Original | Optimized |
| --- | ---: | ---: |
| Core grouping, median of three | 73.33 s | 7.10 s |
| Open until first merged card is visible | 78.17 s | 8.52 s |
| Eight-swipe sequence, including gesture/test synchronization | 29.26 s | 5.10 s |
| Sampled window frame duration, median | 994 ms | 151 ms |
| Sampled window frame duration, 95th percentile | 2,483 ms | 367 ms |

Both complete benchmark runs passed, including fixture cleanup. The remaining
frame times still show stalls on this software-rendered emulator; these gains
are not a claim of a consistently smooth frame rate. The access-notification
wall-time probe is not an end-to-end decode measurement and is not used as a
speed claim. `FeedWindowTest` directly verifies its emission/decoding behavior.

The complete native and desktop measurements, profiling traces, screenshots,
build logs and test receipts live outside the repository under
`DarwinBuild/LiberBuild/evidence/feed-performance` on the development storage.
The desktop workload also compares exact group/original-URL SHA-256 digests for
80, 240 and 800 posts. All three match the original output. The 800-post case
already hits an existing candidate safety bound and shows no speed improvement;
this change does not alter that bound to manufacture a better benchmark.

### Validation

The full core suite, Android feed/reading/filter/save/source/privacy regressions,
release lint and debug/release assembly are required before preview promotion.
New focused tests cover bounded operation-local analysis, exception cleanup,
parallel-reader isolation, canceled grouping, unchanged export serialization,
access-only updates, changed payloads and membership-only updates.

Recorded qualification: 1,778 core tests, zero failures, one optional native
embedding-dependency skip; 186 selected Android cases, 184 passed and two
explicit live-search opt-ins skipped. After the final save-capture adjustment,
all 45 cross-post, fusion and saved-library cases passed again, as did the final
native performance test. Release lint and debug/release assemblies passed.
Both native comparison screens were inspected. These fixture results do not
substitute for the skipped live-network checks.

Physical-phone speed, sustained battery/thermal behavior, and live-source latency
are separate qualifications. This pass does not claim them. The signed laptop
preview is updated only after backing up and comparing its protected local state;
the official Latest release retains its existing physical-phone gate.
