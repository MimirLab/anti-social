# Feed performance qualification

## 0.38.82: keep display enrichment out of source-analysis invalidation

A cache-only restart of a current three-platform album shows that the released
0.38.81 already combines it on its first inspected card. The small replay takes
about 1.6–1.9 seconds. With a larger retained window, the first card takes about
6.4 seconds and still contains the complete family. The delay is not a missing
match: a sampled trace attributes 3.819 seconds of thread CPU to initial grouping
and 0.810 seconds to subsequent reading-alias preparation. Picture observations
arrive during that work. Profiling overhead separates these component figures
from ordinary frame-delivery measurements.

The native input stages 907 originals with recorded subscription bindings; the
original research corpus also contains 55 unbound records, which remain in the
separate 959-original JVM comparison. A native feed fixture must not invent
memberships to import those records. No image fingerprints are supplied: the
shipping loader rebuilds them from retained source files while selected-unready
Tor prevents network fallback. The target card's actual text and painted picture
were inspected, not inferred from a group count.

### Display labels are not source bindings

The Room query already normalizes subscription display names in its input key.
The full-feed edition matcher still used the unnormalized subscription list.
Updating only `Follow.name` and `personName` therefore discarded its completed
source preparation. On the exact published APK, a native control reproduces one
unnecessary matching job lasting **2,997 ms**, with unchanged posts, targets,
memberships and picture evidence. The control reaches its one-second quiet
checkpoint **4,413 ms** after the edit.

The matcher, preparation source and readiness comparison now share the existing
normalized subscription inputs. Initialization remains nullable; enabled state,
target, account-group identity and every other policy field remain part of that
input. The raw subscriptions still drive display. On the candidate, the same
native control records **zero matching jobs**, reaches its quiet checkpoint in
**1,356 ms**, and displays the changed labels in Subscriptions. A subsequent
account-group change with the same pictures starts a fresh matching job. The
complete album remains readable through both edits and navigation back to All.
The quiet-check timings include test settling; they are not UI response times.

### Compile the fixed social-host declarations

The shared social-URL classifier previously scanned every declared host on each
lookup. Its fixed exact-host index and ordered wildcard list preserve declaration
priority, including an earlier wildcard preceding a later exact rule. Existing
URI, port, credential, subdomain, short-link and federated-origin rules remain
unchanged. This stores the fixed declarations, not observations about visited
URLs. No matching threshold, web request, cache database or model is added.

An untraced API 35 comparison restores the same 53.5 MB disposable-device state
before each fresh-process run and verifies every restored file hash. It uses the
same retained window, actual picture files and four-run ABBA order:

| Observation | Published 0.38.81 median | Fixed-host index median |
| --- | ---: | ---: |
| Open → first inspected complete card | 6,461 ms | 6,138.5 ms |
| Open → subsequent complete-family checkpoint | 6,761 ms | 6,436 ms |
| Warm reentry → complete card | 925 ms | 898 ms |

Every first inspected target card already has all three originals, its common
caption once and three distinct pictures. Both candidate cold observations beat
both controls. The approximately 0.32-second cold difference is a modest gain,
not instant startup. The image and publisher-continuation paths issue no new
requests. The frame observation includes a 100 ms compositor allowance after
native semantic readiness. These are emulator observations, not phone p95 or
battery measurements.

A second prototype reuses parsed URIs within the same classifier operation.
Its separate ABBA comparison improves the first-card median only from 6,415 to
6,358 ms. That does not establish a useful additional gain, so those runtime
edits are removed. Its regression cases for escaped share paths remain. The
kept classifier preserves all 959 original group memberships, content-observation
signatures and the measured URL results against the published implementation.
The full release regression and artifact record is in
[release qualification](RELEASE_QUALIFICATION.md).

**Remaining work:** initial full-window analysis still costs several seconds on
this large cold replay. This increment avoids an unnecessary repeat after name
enrichment and trims URL dispatch; it does not remove that first-window cost or
qualify every live-source acquisition delay.

## Post-0.38.76: distinguish delayed video qualification from a caption veto

The October 30 investigation replays a retained five-edition video family on
the 0.38.76 runtime, using the actual native feed, Gateway range sampler and
isolated decoder. The shorter caption initially occupies a separate card. The
existing planner selects the correct two recordings, and acquired footage
collects all five editions without a new matcher or inferred account binding.

| Private replay scope | First-visible checkpoint | Full-family checkpoint | Complete adopted caption |
| --- | ---: | ---: | ---: |
| Five captured originals, 25 subscriptions | 1,178 ms | 9,062 ms | 10,049 ms |
| 904 retained originals with captured follow memberships, 25 subscriptions | 6,166 ms | 10,575 ms | 11,630 ms |

Each row is **one observation**, not a median, p95, controlled comparison or
physical-phone measurement. The first-visible checkpoint includes the native
probe's idle wait; it is not a frame-presentation timestamp. Both reads acquire
four scenes from each recording, transfer 2,240,374 range bytes across ten blocks,
and record zero samples outside the target family. The larger input excludes
55 retained records without captured follow bindings rather than inventing
memberships. An initial malformed input retained those records and was correctly
rejected before opening the feed; it supplies no performance result.
Both replays import already captured posts and clear derived video observations;
they do not time fresh timeline acquisition or reset every ordinary media cache.

This establishes successful eventual collection for these captured inputs.
It also leaves a substantial cold-delivery delay to investigate. The smaller
46-post warm-URL replay from 0.38.76 cannot establish startup performance for a
substantially accumulated database.

A separate retained image-gallery replay fails after all eleven exact image
URLs have been acquired and sandbox-decoded: two cards, six attachments and the
same heading twice. Its opening reminder encounters the caption-negation guard.
That reproducible admission defect is distinct from the video's acquisition and
adoption delay; strengthening one path does not qualify the other.

The 0.38.77 gallery change is also compared on the same frozen 959-original
input, augmented only with the eleven actually acquired image observations.
All source facts are checked unchanged. Rows fall from 768 to 767; all originals
survive, no prior family splits, and only the intended three-original gallery
becomes one collection.

| Local desktop JVM pass | 0.38.76 | 0.38.77 |
| --- | ---: | ---: |
| First | 2,609 ms | 2,433 ms |
| Second | 1,554 ms | 1,457 ms |
| Third | 1,280 ms | 1,370 ms |
| Fourth | 1,150 ms | 1,177 ms |
| Fifth | 1,097 ms | 1,144 ms |

These small sequential operation measurements do not demonstrate a consistent
speed gain, phone latency, p95 or energy behavior. The change avoids an
unnecessary request for absent thread evidence; it does not remove the separate
cold-feed delivery cost observed above.

## 0.38.76: limit counterpart planning to relevant copy cohorts

A private retained-window comparison contains 959 public-source originals across
25 subscriptions: the retained 958-post window plus one actually acquired missing
counterpart. Only already acquired image observations were added to matching
source records; their source facts were checked unchanged. The baseline produces
769 rows and splits the four focus originals into groups of three and one. The
candidate produces 768 rows with all four together. Every original is conserved,
no previously accepted family is split, and only this family changes.

A first implementation needlessly constructed witness lists across unrelated
rows and parsed headings for impossible media-free cases. The final path plans
lazily in mixed copy cohorts, preserves the existing all-rich composition path,
and rejects the impossible case before parsing. Mutually nominated comparisons
and the existing limits still determine eligibility.

Five sequential desktop-JVM grouping passes, with input parsing excluded, are
recorded below. These are small local-operation checks, not Android rendering,
source-acquisition, physical-phone, energy or p95 measurements:

| Pass | Baseline | Candidate |
| --- | ---: | ---: |
| 1 | 2,453 ms | 2,334 ms |
| 2 | 1,384 ms | 1,388 ms |
| 3 | 1,282 ms | 1,237 ms |
| 4 | 1,212 ms | 1,158 ms |
| 5 | 1,099 ms | 1,101 ms |

This sample shows the repaired collection without an obvious added grouping
cost; it does not establish a general speedup. Native checks separately exercise
initial and late extraction arrival, complete visible clauses, the retained pixel
anchor, Context and the actual Save-button path. See
[qualification](RELEASE_QUALIFICATION.md#03876-media-free-extractions-retain-their-counterparts-proof).

## 0.38.75: renewed delivery URLs reuse prior scene work

A privately retained 46-post window with four focused editions was replayed
through the actual native feed. Before opening, a prior read used the production
Gateway and isolated video sampler to establish observations; no scene hashes
were fabricated or supplied in the input. The renewed delivery URL was then
presented through ordinary feed preparation, dwell and matching. Source lookup
and initial acquisition of that recorded window are outside these timings.

On the API 35 emulator, two 0.38.74 baseline runs attempted the renewed clip once
and twice after opening (the second includes a retry). Two candidate runs made
**zero** renewed-clip sampling attempts after opening. The small repeated sample
is recorded separately from phone performance or fresh-source delivery:

| Replay | First visible card | Complete family | Complete caption |
| --- | ---: | ---: | ---: |
| Baseline 1 | 0.78 s | 4.22 s | 5.10 s |
| Baseline 2 | 1.04 s | 8.12 s | 9.02 s |
| Candidate 1 | 1.54 s | 1.73 s | 2.02 s |
| Candidate 2 | 1.13 s | 1.58 s | 1.77 s |

First-card timing varied and did not improve consistently. These observations
establish removal of redundant work in this warm-recording case; they do not
supply a median/p95 distribution, cold-video speedup, physical-phone latency or
battery measurement. Every source caption and playable edition remains available;
reusing derived scenes does not equate the recording's exact bytes or authorize
attachment removal. See the [collection boundary](ATTACHMENT_COLLECTIONS.md#reuse-renewed-video-observations-and-qualify-gallery-reply-headings-03875).

## 0.38.72 investigation: prioritize coverage and keep details adoption reliable

The visible-card lane now tries its current focused planner before a fresh
global offer when both offer platforms are already represented on that card.
An offer introducing a platform keeps its cheap direct path. The other edition
remains a fallback and can still reveal distinct content; neither its discovery
path nor the existing comparison allowance is removed.

Native replay also exposed an independent presentation failure. An explicit
captured-details action prepared one card off-thread, then rejected its result
if any unrelated source/window mapping changed. The reader's tap could therefore
do nothing while valid new details remained pending. Admission now compares the
target row, its relevant originals and group mappings. A relevant update selects
a fresh draft on the next frame, within four attempts; continuing churn produces
an explicit message. The commit uses current rows and retains the established
key, order and pixel anchor. It makes no source request.

Two real Activity-stop controls demonstrate another existing boundary problem:
changing Compose state does not guarantee that a paused recomposer immediately
cancels its effect. Video and gallery dwell now cancel their own job directly
from lifecycle/focus callbacks. A resume revision preserves a leave/return that
occurred between frames and restarts eligible work. Explicit refreshes, downloads,
the sampler's acquisition limits and foreground priority retain their policies.
The three new controls fail against the exact published 0.38.71 APK: two missing
cancellations and one coverage-priority assertion. All twelve focused checks pass
against the lean candidate, including resume, retained fallback and card-scoped
details admission.

An earlier production-shaped startup-sampling prototype was **rejected and
removed**, rather than promoted on an attractive sample-start time. It overlapped
one bounded, owned video pair with initial local feed preparation and preserved
viewport, filter, transport and lifecycle guards. In the final native comparison,
the old/candidate/old complete-family times were **21.953/21.899/21.396 seconds**
after opening; the first-card-to-family gaps were **13.102/13.047/13.257 seconds**.
All three preserved six originals, each of four required literal variants once
and the two-pixel body anchor. Starting around one second instead of nine did
not establish useful whole-feed acceleration: foreground loading interrupted
the speculative pair twice, giving six sample attempts instead of four. No
startup sampling component or extra network path ships in this increment.

The lean candidate's separate ordinary-scheduling replay passes the full native
body check at **8.291/22.564/26.320 seconds** for first card, complete family and
adopted body, with four recordings started. The missing-platform recording now
precedes the extra represented-platform recording. Its **14.273-second** visible
family gap still supplies no evidence of a general latency repair. One exact
old-APK control again finds the family but fails body adoption; the other passes.
Both controls remain part of the record rather than being dropped from a speed
comparison. The lean candidate's actual video and attributed-caption screens
are inspected separately.

The frozen 0.38.72 release candidate's own live replay also passes complete body,
original retention and the pixel-anchor check: **7.609/25.426/28.946 seconds** for
the same three stages, with four recording starts. Its **17.817-second** family
gap reinforces that source-dependent latency remains open. The separate real
foreground-preemption check passes too, retaining two completed blocks and
finishing its retry with four scenes before releasing all retained blocks.

The initial prototype comparison's candidate also failed body adoption despite
finding the complete family. Its retained native screen and failed assertion
prompted the details-admission repair above; its earlier family time is not a
successful complete-reading benchmark. Failed controls and rejected prototype
source/APKs remain in the private external build evidence. General first-card
and complete-family latency remain open; these are API 35 emulator observations,
not physical-phone p95 or energy measurements.

## Post-0.38.70 investigation: keep validated blocks through foreground preemption

Eventual collection is not sufficient reader behavior: separate copies remain
visible while proof arrives. This investigation keeps first-card, complete-family
and complete-body times separate. It does not lower the existing ownership,
caption, scene or attachment-preservation requirements to hide that interval.

The actual Gateway sampler reads four scenes through a finite descriptor and
256 KiB range blocks. A foreground request can cancel its deferred coroutine.
Previously, the next attempt constructed an empty range view and fetched those
completed blocks again. The candidate retains immutable, completed blocks only
inside that recording's existing comparison attempt. A retry first performs an
ordinary fresh prefix request, then admits old blocks only for the same exact
input/source, connection-session identity, parent allowance, final destination,
length, validator and prefix bytes. Validator-free observations still repeat
their final prefix check and remain observations, not whole-file identities.

The retry retains its normal per-attempt allowance under the unchanged shared
8 MiB/32-exchange, 45-second comparison. Copying an old view wholesale was
rejected during design review: a failed exchange could consume its remaining
request capacity and make a formerly possible retry fail. Validated seeding
keeps that capacity while avoiding repeated successful blocks. Session changes,
expiry and a different parent forbid reuse. Changed bytes or representation
start fresh; cancellation, purge and attempt completion discard the references.
There is no new disk cache, background scan, dependency, permission or decoder.

A controlled API 35 native check uses an explicitly supplied public recording,
the real Gateway, actual isolated decoder and a one-byte foreground media read.
The foreground read begins at **2.362 seconds**, the sample is preempted at
**2.370 seconds** with two blocks retained, the foreground read finishes at
**2.664 seconds**, and the retry starts at **3.365 seconds**. It finishes at
**7.760 seconds** with four sampled scenes and thirteen newly fetched blocks.
The retained block count is zero after closure. The local HTTP native controls
independently prove that a matching retry rereads its prefix but does not refetch
the other completed block. This establishes bounded recovery, not a latency SLA.

An explicitly **test-only** early-scheduling flag also overlaps one focused pair
with initial local feed preparation. Both its old-APK and candidate successful
replays preserve six originals, four literal caption variants once and the
existing two-pixel body-adoption bound. The old APK first shows a card/family/body
at **11.555/17.856/21.289 seconds** after opening; the candidate observations are
**9.523/19.257/22.623 seconds**. Both start six sample attempts, including
foreground-preempted attempts. Completed successful-sample byte totals are
**14,417,920/11,272,192**; these totals exclude partial failed traffic and must
not be reported as total transferred bytes.

The later old-APK control receives an EOF during its second recording and fails
the required-observation wait at 150 seconds. Its failed native report, events
and screen are retained with the two successful runs. This incomplete comparison
does **not** establish an end-to-end speedup. The candidate screen still has a
**9.734-second first-card-to-family gap**. Earlier intent preparation, its actual
viewport handoff and source-dependent acquisition latency remain open work.
The experimental early flag does not change shipped scheduling.

A separate candidate replay with that flag **off** also preserves the same
complete family, body and pixel anchor. It first shows the focused card at
**8.544 seconds**, the complete family at **27.361 seconds**, and the adopted
body at **30.969 seconds** after opening, with four recording starts. Its first
recording takes substantially longer than the later samples. This unpaired
ordinary-scheduling observation has an **18.817-second visible-family gap**;
it supplies no evidence that the general delay is repaired.

The source-rendition investigation also rejects an attractive shortcut. A public
post page supplies four declared rendition records; its smaller HEVC alternative
is approximately 8.7 MB versus 22.2 MB for the default H.264 file. Neither size
nor a 200 HTML page qualifies its media route: all six tested published direct
and API alternatives fail bounded sampling through the existing Gateway, from
missing valid range responses or EOF, while their declared expiry remains in
the future. No unbounded download, relaxed credentials, guessed asset identity
or new active route is introduced. Six successful existing-route samples have
matching scene evidence across fifteen pair comparisons; this does not prove
complete recording equality or authorize aliasing unsupported alternatives.

Raw inputs, receipts, screenshots, failed controls and frozen candidate artifacts
stay outside public source under `evidence/recording-renditions-20261029`.
These emulator observations do not establish physical-phone latency or energy.

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


## 0.38.73: initial video prefixes and complete short-caption collections

Video comparison can prepare one unused 256 KiB prefix for the retained reading
position while the first local deck is prepared. It uses the existing single
lane, selected Gateway session, exact source/input, 45-second parent, byte and
request allowances. Frame decoding waits for the actual readable card. A drag,
overlay, stopped Activity, changed ownership/caption/input or ordinary-cache purge
retires the work. Resource policy is checked again after waiting. Decoder retries
still revalidate their prefix; the initial handoff cannot create another budget.
Only one linked, nearby pair is prepared, never a global video crawl.

Optional metadata failure now has a separate in-memory pause from failure of the
retained playback route. Once one peer has measured scenes, the next peer need
not retain an obsolete metadata prerequisite. Exact published 0.38.72 controls
reproduce the blocked-direct-route failures; the new route regressions retain
successful peers, genuine raw failures, explicit retry, purge and expiry behavior.
A metadata-dependent initial route waits for real visibility too.

A fresh four-platform capture exposed a separate correctness gap. Two editions
already agreed, another supplied a still preview with different decoration, and
the fourth used a shorter video caption. The established-announcement collector
now admits a distinctive, complete decorative equivalent without requiring an
off-screen picture download. Weaker captions still need actual artwork. Publisher
scope, distinct-platform ambiguity, time, claim, question, destination and
regional flag checks remain separate from media equivalence. No recording is discarded by this
caption route.

The first native candidate joined those originals but still repeated their text.
That failed the stronger display assertion and was retained as a failure. Short
caption composition now collates a literal common prefix and shares continuations
among their actual source subsets. Branches keep complete coupled tails. Every
original reconstructs through its own normalized token path, including
same-platform editions; exact source records remain unchanged.
URLs retain case-sensitive identity. This is presentation after independent
admission, never another post detector. All existing formatting, passage, media,
field and quotation composers run first.
The broader native selection caught an earlier candidate placing this fallback
too early: a punctuation-only edition stopped using its existing concise display.
That candidate was rejected, and the corrected fallback order has its own native
formatting and case-sensitive inline-destination regression.
The corrected native replay displays the common heading and continuation once,
retains all four originals and three media entries, and passes the saved-copy
preservation check. Actual native caption and media-selector screens were inspected.

These changes do not eliminate source-dependent merging latency. On the final
frozen APK, the large six-original replay first displayed a card after 6.3 seconds,
formed the complete family 12.5 seconds later and adopted its body at 22.4 seconds
from opening. The smaller four-platform replay first displayed a card after
1.1 seconds, formed its family 13.3 seconds later and adopted its body at
15.3 seconds. Both preserve the two-pixel adoption bound and all required literal
variants exactly once. They are different captured windows, not a cold/warm or
physical-phone comparison. Foreground preemption and real Gateway media reads
remain visible in the private receipts. No test-only scheduling switch or
fabricated scene qualified these runs. Controlled phone latency, energy and the
remaining reported families still need their own observations.
