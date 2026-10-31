# Feed performance qualification

## 0.38.88: coalesce image notifications before preparing another bank

The image stream prepared whole-window snapshots upstream of the feed's existing
conflation point. While one coherent matching pass was busy, many snapshots were
prepared even though only the newest waiting input could be consumed. A fresh
trace of published 0.38.87 exposed this repeated work before the first card.

The feed now combines cheap image, footage and gallery revisions first. Only
when its consumer is ready does it capture current image evidence on the worker.
This uses the sequential/downstream behavior of the existing
[pinned coroutine operators](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/flow/operators/Context.kt).
Source preparation's later current-bank check remains intact. Unrelated image
updates stay quiet only when the matcher confirms that its completed base uses
that bank. Footage and gallery changes still flow with identical image evidence.
There is no new cache, model, network request, permission or database migration.

Comparing only with the earlier queued image is unsafe: source preparation can
use a newer bank, followed by withdrawal back to that queued state. The first
unshipped prototype loses that withdrawal and fails the native regression. The
corrected check reads the matcher's actual completed snapshot under its existing
mutex. Cancellation and independent-reader boundaries remain in force. A native
probe against the exact published APK separately reproduces four preparations
while its consumer is blocked; the corrected path prepares only the initial bank
and newest pending bank, two in total. These are real app helpers, selected by
own-application reflection for cross-version testing, not a JVM transcription.

An untraced API 35 ABBA comparison uses the exact published 0.38.87 and final
frozen 0.38.88 candidate, two emulator cores, 2,048 MiB RAM, 896 source originals
and the same hash-restored 53.5 MB app-state snapshot before every run. It uses
real cached picture files and stages a third album original after the first
card is visible. OS/ART state and host load are not reset by that app-state copy.

| Checkpoint | Published 0.38.87 | Final candidate 0.38.88 |
| --- | ---: | ---: |
| Open → first visible card, individual runs | 9,773 / 9,392 ms | 8,918 / 8,696 ms |
| Late source commit → current picture credit | 4,281 / 4,309 ms | 4,348 / 4,310 ms |
| First-card median | 9,582.5 ms | 8,807 ms |
| Late-source median | 4,295 ms | 4,329 ms |

The first-card median improves by **775.5 ms (8.1%)** in this small comparison.
Late-source adoption is approximately unchanged, with a 34 ms higher median;
several-second joins remain open. All four journeys conserve originals, three
distinct pictures, read marks, source credit and the pixel anchor, with zero
continuation reads. Each picture is scrolled into view and its actual frame is
inspected. A preceding prototype ABBA also improves first-card timing, but its
numbers are kept separate from this final-artifact comparison.

The separate 2 ms sampled diagnostic attributes 761 ms to image-bank preparation
before the baseline first card and 94 ms before the prototype first card, with
43 versus five observed bank segments. Segments are not exact invocation counts,
inclusive attribution is not additive, and these are not untraced timings or
whole-app CPU/energy measurements. The prototype and final candidate differ in
version metadata and comments; the final candidate has its own comparison above.

Qualification includes 2,761 JVM passes and four optional skips; all 312 selected
API 35 identities complete with 303 passes and nine explicit skips. An initial
large batch loses its emulator process alongside graphics diagnostics, without
an app assertion failure before disconnection. That incomplete batch is retained
and not counted as successful. The interrupted gallery case passes on a fresh
owned emulator, then the complete selection passes in bounded app-process batches.
This does not qualify an uninterrupted long-lived host-GPU session.

Minimum API 26 has 78 passes and one opt-in skip across 79 named checks, including
the new scheduling cases and current/withdrawn/conflicting image-bank controls.
Its separate 896-original album replay retains one card, three pictures, source
credit, read marks and the anchor; late adoption is 8,539 ms in this single
compatibility observation. It is not a paired speed claim. Both API levels keep
the captured reference-difference caption once and preserve every saved original.
Twelve retained packets also pass 24 announcement-family replays containing 81
source-edition instances. Actual native caption and gallery frames are reviewed.
These are emulator/cached-source qualifications, not physical-phone p95, live
publisher acquisition, battery or sustained thermal measurements.

## Post-0.38.87 investigation: reject repeated-link answer caching

A fresh API 35 trace of the exact published 0.38.87 APK confirms remaining
cold-deck preparation work. The retained 896-source journey passes with three
originals, three distinct pictures, preserved read marks and source credit, and
zero continuation reads. Under 2 ms method sampling, first visible delivery is
9,955 ms and late-source adoption is 4,444 ms. These are diagnostic observations,
not ordinary latency estimates. Repeated native regex compilation and image-URL
parsing remain leads, not automatically justified caches.

The earlier compiled-pattern cache was already rejected. This distinct prototype
reuses the complete source-qualified mention-link answer for an exact immutable
post and exact URL inside one synchronous operation. It keeps the literal native
regex, all source/destination gates, the shared 8 MiB allowance and a 2,048-answer
cap. Revised posts, independent operations and readers cannot inherit answers.
The desktop corpus counter records 535 computations and 345 hits; this is not a
speed measurement. Shared accounting is already full in that counter, so reduced
work in one cache cannot be assumed to improve the complete operation.

All 21 focused JVM methods pass. The first native harness finds both the real
classifier and its generated static lambda and fails three method lookups before
their assertions; the two caption controls pass. Excluding static methods fixes
the harness without changing the candidate app bytes. All five selected native
checks then pass, including literal Unicode case folding, source gates and
faithful caption presentation/saving.

The untraced API 35 ABBA comparison uses two cores, 2,048 MiB RAM and the same
hash-restored app state and captured sources before every run:

| Checkpoint | Published 0.38.87 | Answer-cache prototype |
| --- | ---: | ---: |
| Open → first visible card, individual runs | 9,974 / 10,469 ms | 12,155 / 9,735 ms |
| Late source commit → current picture credit | 4,209 / 4,558 ms | 4,809 / 5,313 ms |
| First-card median | 10,221.5 ms | 10,945 ms |
| Late-source median | 4,383.5 ms | 5,061 ms |

All four journeys pass the original, picture, read-state and pixel-anchor checks;
the actual native frames are inspected. This small comparison establishes **no
useful end-to-end speed gain**. It does not establish a general regression rate
or its cause. The prototype runtime changes and prototype-only tests are removed;
their exact patch, frozen artifacts, failures and receipts remain in private
external evidence. No new APK, cache, matcher rule or release is introduced.
First-card and join latency remain open, independently of the released caption
correction below. Physical-phone p95, energy and live acquisition are unmeasured
by this investigation.

## 0.38.87: share compatible prose around literal reference differences

A captured three-source collection was correctly grouped, but every compact
composer declined its captions. A readable domain in two editions became an
opaque inline URL in the third, alongside another trailing URL and paragraph
differences. The native fallback repeated the common paragraph **three times in
one card**. Original and saved-source preservation already passed.

A final presentation route now aligns those literal editions before the faithful
whole-source fallback. Common prose is read once; differing references retain
their platform and original identities. It preserves the shortened URLs rather
than guessing their destinations. URL path/query case and Unicode spelling remain
literal, while ordinary prose keeps its existing normalization. Every original's
ordered normalized caption path must reconstruct, including its references. The ordinary
detector and saved-manifest admission do not use this new presentation route.

The route checks source length, preview and parent bounds before its visible-
reference gate. An unrelated declared-link inventory cannot activate it. It
retains the existing 24–256-word, eight-version, ordered-overlap and edit-run
limits. Changed numeric claims, negation and unqualified named subjects retain
the faithful fallback. A readable shared word borrowed by an insertion is not
mistaken for a changed name. No URL expansion, acquisition request, dependency,
permission, database migration or new persistent cache is added.

The final captured API 35 replay shows one card and **one** occurrence of each
required common passage, with all three originals and their faithful saved copy.
Its actual native caption is inspected. This text replay has no cached media
bytes or injected pixel observations; it is not a new video-equivalence result.
The synthetic native control separately decodes real fixture pictures, checks
one picture and attributed reference alternatives in both themes, and saves every
original. The older non-reference inline composer and cache-handoff controls pass
in the same seven-method focus batch.

A separate untraced API 35 ABBA guard compares the exact published 0.38.86 and
the final frozen candidate on the same hash-restored 53.5 MB app state, with two
cores, 2,048 MiB RAM and 896 originals. It stages a third album edition after its
counterparts are visible. All four runs preserve one card, three unique pictures,
source payloads, read marks, source credit and the pixel anchor; continuation
reads stay zero. Each picture is scrolled into view. The existing compositor
allowance remains in the checkpoints.

| Checkpoint | Published 0.38.86 | Candidate 0.38.87 |
| --- | ---: | ---: |
| Open → first visible card, individual runs | 10,000 / 10,143 ms | 9,755 / 11,011 ms |
| Late source commit → combined card/current picture credit | 4,643 / 4,711 ms | 4,743 / 4,772 ms |
| First-card median | 10,071.5 ms | 10,383 ms |
| Late-source median | 4,677 ms | 4,757.5 ms |

This small guard records a 311.5 ms (3.1%) higher first-card median and an 80.5 ms
(1.7%) higher late-source median. It does not establish their cause or a general
latency distribution. **No speed improvement is claimed for this text fix.**
These are cached-source emulator journeys, not phone p95, live acquisition or
energy measurements; several-second joins and initial-delivery work remain open.

The first prototype passed ten focused methods and failed one: a reference
inserted before an unchanged capitalized sentence triggered the borrowed-word
guard. Its failure is retained. The corrected focused suite passes all eleven
methods. The final bounded version passes all 2,765 JVM cases with 2,761 passes,
four optional skips and zero failures/errors. [Release qualification](RELEASE_QUALIFICATION.md)
records broader native, retained-source and release checks separately.

## 0.38.86: reuse unchanged source analysis when an edition arrives

A private API 35 sampling trace of published 0.38.85 showed repeated caption
features, decorative-text preparation and candidate-key construction during a
late source join. The immutable-window matcher discarded this pure analysis
whenever its source list changed, even when almost every original was unchanged.
The trace identified work to investigate; its sampled timings are not used as
untraced latency measurements.

Adjacent matchers can now hand over at most 8 MiB of completed source analysis.
The full publisher/subscription context must match, each original's membership
must match, and its current source fields are checked before reuse. Changed text,
links, provenance, dates, identities and media shape require fresh analysis.
Pixels, admitted pairs, collections and earlier grouping decisions are never
transferred. Nomination and admission still run against the current window and
current image observations. This applies the dependency-aware reuse principle
described in the [Rust compiler's incremental-query design](https://rustc-dev-guide.rust-lang.org/queries/incremental-compilation-in-detail.html),
without incorporating compiler code or adding a dependency.

The handoff copies bounded references to immutable facts rather than picture
bytes or strings. Its charged-entry bound is not a whole-heap measurement.
Receiving entry/byte limits, memory pressure, cancellation, missing source scope
and the direct activity-stop release remain authoritative. No network requests,
persistent database, saved-record changes or new permissions are involved.

An untraced API 35 ABBA comparison restored and hash-verified the same 53.5 MB
app-state snapshot before every run. It used two emulator cores, 2,048 MiB RAM,
896 captured originals and actual cached picture files. A third edition was
committed only after the two-source card was visible. All four native journeys
kept one card, three distinct pictures, the common caption once, all originals,
prior read marks and the pixel anchor. Every picture was scrolled into view and
its actual frame inspected. No fingerprint was injected and continuation reads
remained zero.

| Checkpoint | Published 0.38.85 | Candidate 0.38.86 |
| --- | ---: | ---: |
| Open → first visible card, individual runs | 11,210 / 10,112 ms | 9,779 / 9,286 ms |
| Late source commit → combined card/current picture credit | 5,142 / 5,913 ms | 4,394 / 4,610 ms |
| Late-source median | 5,527.5 ms | 4,502 ms |

The late-source median improves by 1,025.5 ms (18.6%) in this small comparison.
Cold-card observations are reported separately; they do not establish a general
initial-delivery gain. The existing 100 ms compositor allowance applies. These
are staged local-source joins, not live acquisition, physical-phone p95, energy
or operating-system-cache measurements. Several seconds still remain before the
late edition becomes visible; acquisition and later presentation work remain open.

The first prototype's six new JVM controls failed because an empty map builder
shadowed the donor cache's entries. That prototype could not transfer analysis;
it was corrected before native comparison or preview promotion. The failure and
artifacts are retained privately. [Release qualification](RELEASE_QUALIFICATION.md)
records the final regression and publication checks.

## 0.38.85: late media credit preserves the reading selection

The delayed-source native control now starts with two captured editions visible,
then commits their third platform edition. On published 0.38.84 the collection
header updates, but the existing picture label still lacks the new source after
a 60-second wait: its credit is incorrectly tied to the frozen caption body.
The correction updates proven copy attribution separately from media selection
and caption adoption. It adds no requests or decodes; unchanged-source cards
reuse their existing attachment list.

An initial candidate inserted new credit rows and failed the older-counterpart
pixel-anchor check. A published-control/candidate countercheck reproduces that
regression. The corrected version retains existing label visibility and one-line
height; it passes both the focused anchor check and the full native batch. Picture
and player layout continue to use the frozen selection, not the expanded credits.

The corrected API 35 and 26 private journeys preserve original payloads, prior
read marks, one card, three pictures and the pixel anchor, then scroll through
all three native pictures. Their source-commit-to-visible-credit checkpoints are
5,694 and 8,886 ms. These measure staged publication of already captured source
records and actual cached bytes. They are not initial-card, live-fetch, physical-
phone percentile or energy measurements, and do not establish that delayed joins
have disappeared. [Release qualification](RELEASE_QUALIFICATION.md#03885-credit-late-picture-copies-without-moving-the-reading-card)
records exact method totals, the prior failed candidate and the retained older-
Android video-test timeout.

## Post-0.38.84 investigation: reject a weak URL-prefilter gain

A bounded immutable multi-literal automaton was prototyped to nominate the
shared URL cleaner’s providers in one ASCII scan. It used only mandatory literals
already extracted by `UrlPattern`; original regexes, exceptions, signatures,
rewrites and provider order still decided every transformation. Unknown literals
remained eligible, non-ASCII/control inputs retained the existing path, and every
rewrite refreshed nomination. The actual 206-provider plan used 368,644 bytes
of primitive arrays; this was not a total-heap measurement.

The mechanism followed the conservative prefilter contract described in
[regex-automata’s documentation](https://docs.rs/regex-automata/latest/regex_automata/util/prefilter/struct.Prefilter.html)
and the multi-literal design discussed in
[Aho–Corasick’s implementation notes](https://github.com/BurntSushi/aho-corasick/blob/master/DESIGN.md).
It was implemented independently, without adding a dependency or network request.
The prototype and its research evidence remain in the private external build
area. **The runtime change was rejected and is not part of the released app.**

Two untraced API 35 ABBA comparisons restored and hash-verified the same 53.5 MB
app-state snapshot before every run. Each native feed had 907 retained originals,
25 subscription bindings and actual cached picture bytes, with no injected
fingerprints or publisher-continuation requests. The checks required a focused
reader, preserved originals, the common heading once and three distinct pictures.
First/warm screenshots were also inspected. The existing 100 ms compositor
allowance applied; these small emulator samples do not establish phone p95,
battery use or operating-system cache invariance.

| Observation, medians | Initial published control | Initial prototype | Final published control | Final prototype |
| --- | ---: | ---: | ---: | ---: |
| Open → first complete card | 8,123.5 ms | 7,652 ms | 8,088.5 ms | 7,924.5 ms |
| Open → complete-family checkpoint | 8,421.5 ms | 7,952 ms | 8,411 ms | 8,248.5 ms |
| Warm reentry → complete card | 1,342.5 ms | 1,201.5 ms | 1,301 ms | 1,436 ms |

The initially promising 0.47-second first-card difference shrank to 0.16 seconds
(2.0%) on the final repeat; warm reentry became 0.14 seconds (10.4%) slower.
Functional parity did not establish a sufficiently consistent user-visible gain
to justify this additional runtime machinery. Version 0.38.84’s qualified source
reuse and lifecycle limits remain in place; no new APK was published for this
experiment.

The final prototype passed 2,762 JVM cases: 2,758 passes, four optional skips and
zero failures/errors. Its frozen API 35 batch had 297 named methods: 289 passes,
eight opt-in skips and zero failures. API 26 had 61 named methods: 60 passes,
one opt-in skip and zero failures, plus a full-window journey with two actual
late-decoded pictures, preserved read marks, originals and pixel anchor. Twelve
retained packets covered 24 announcement-family replays and 81 source-edition
instances, with complete media and faithful saves. A separate JVM diagnostic
preserved cleaning output for 2,616 distinct retained URLs. These are prototype
qualification results, not new-release claims.

An earlier comparison completed its native test but lost the owned diagnostic
emulator before required artifacts were copied. Its runner failure and recovered
files are retained privately; that incomplete run is excluded from both tables.
The laptop reader was restarted independently of diagnostic cleanup.

The retained `UrlCleanerUnicodeNativeTest` instead qualifies the existing
cleaner’s ordered redirects, signed queries and real Android ICU folding against
its legacy path. It adds no runtime change. A separate harmless native probe
confirmed the character-budget gap described in
[hardening](HARDENING.md#cleaning-without-a-slow-reader); the rejected prefilter
did not repair that gap. Late live acquisition/adoption can still delay a join
even when its captured announcement merges correctly; that remains an open
user-visible investigation.

## 0.38.84: reuse later source analysis within the reader’s memory allowance

Late picture observations still require fresh matching against source text,
account bindings and current pixels. The reader previously retained at most
8 MiB of reusable source analysis even when a larger active window repeatedly
needed the entries that did not fit.

The initial source pass retains the existing 8 MiB allowance. Subsequent real
source/image comparisons may retain up to 16 MiB on a visible, non-low-RAM
reader with sufficient managed-heap and system headroom. At the next source/image
comparison, low-memory conditions release that optional analysis. Separate expansion/recovery margins reduce
repeated shrinking and regrowth near the boundary. The accounting limit is not
an exact heap or PSS measurement. No larger cache is preallocated.

A direct activity lifecycle observer releases retained source analysis on stop
or disposal, including while Compose is paused. An obsolete memory query cannot
refill the released cache, including across a stop/resume round trip. Completed
visible groups, source records, reading position and protected saves are not
removed. The next eligible comparison can rebuild disposable analysis. Immutable
local references already used by an in-flight operation live until that work
returns; release does not claim to free every byte immediately.

The resource check uses `ActivityManager.MemoryInfo`, managed-heap headroom and
`isLowRamDevice`. Android documents that legacy running-pressure trim callbacks
are no longer delivered from API 34 onward; this policy therefore does not rely
on those callbacks. Available system RAM is a snapshot rather than an allocation
guarantee. See [Android’s callback contract](https://developer.android.com/reference/android/content/ComponentCallbacks2)
and [memory snapshot fields](https://developer.android.com/reference/android/app/ActivityManager.MemoryInfo).

### Actual late-picture delivery

An API 35 ABBA comparison restores and hash-verifies the same 53.5 MB disposable
app-state snapshot before each run. It opens the native feed with 907 retained
originals and 25 subscription bindings. After its complete three-source,
three-picture target card is visible, two genuine retained image files arrive
separately through the shipping decoder and identity bank. No fingerprint is
injected. All original payloads, target read marks and the pixel anchor remain
intact after each fresh comparison. The target heading appears once.

| Observation | Published 0.38.83 control 1 | Staged candidate 1 | Staged candidate 2 | Published control 2 |
| --- | ---: | ---: | ---: | ---: |
| Open → first complete card | 7,763 ms | 7,617 ms | 7,913 ms | 7,572 ms |
| First late-image matching work | 2,008 ms | 1,415 ms | 1,474 ms | 2,049 ms |
| Second late-image matching work | 1,954 ms | 1,401 ms | 1,469 ms | 1,998 ms |

The first late-comparison medians are **2,028.5 and 1,444.5 ms**, about 29%
apart; the second medians are **1,976 and 1,435 ms**, about 27% apart. There is
no established cold-card improvement: first-card medians are **7,667.5 and
7,765 ms**, with overlapping observations. Earlier comparisons before the first
visible checkpoint may already populate the permitted working set.

Each arrival requires the actual card’s Updating → Ready transition, before
an independent fresh-group conservation check. Settled-ready checkpoints include
a one-second quiet period and a 100 ms compositor allowance, so they are not
exact busy-indicator intervals. Their first/second medians fall from
3,286/3,234.5 ms to 2,702.5/2,690 ms. Publisher-continuation reads stay at zero.
This qualifies fresh full-window comparisons, not a previously missing original
joining the target as a result of the unrelated late image.

All four native invocations pass with zero skips/failures. The minimum-API-26
replay also passes both actual image arrivals with sources, read marks and anchor
preserved. Twelve focused named native checks pass separately on each API,
including a real activity stop/resume, stale resource-query rejection,
cancellation, fresh image/source admission and notification-shade rejection.
Screenshots are inspected in addition to the focused-window assertions. These
are owned-emulator observations, not phone p95, peak-PSS or battery figures.

No pair/group decision, admission threshold, source identity, cached image
validation, video equivalence rule, routing allowance, permission, model or
persistent schema changes. The separate synchronous matching workspace keeps
its existing 8 MiB bound. Analysis remains disposable and local.

## Post-0.38.83 investigation: verify foreground delivery and reject an ineffective shortcut

An experimental ASCII-only shortcut avoids entity/Unicode preparation when its
output is provably unchanged. The exact released core and candidate agree on
**20,074 cleanup inputs**, including all ASCII pairs, controls, entities,
malformed UTF-16 and captured source fields. A separate comparison preserves
all 959 originals, their 767 groups and content signatures, and 1,100 URL cases.
The candidate's fresh JVM run covers **2,745 cases: 2,741 passes, four optional
skips and zero failures/errors**.

Four untraced native local-stage runs retain identical ordered group and
observation digests. Their grouping medians are **3,589 ms for the release and
3,548 ms for the candidate**, a small operation difference. It does not establish
useful delivery improvement.

### A semantic card behind System UI is not visible delivery

Inspection rejects the first cold-feed comparison: an engineering wake command
opened the notification shade. The Compose nodes existed behind it, so the old
semantic assertions passed while the screenshots showed System UI or a covered
screen. Those four cold/warm measurements are retained privately as invalid
delivery evidence. Their timings support no performance claim.

The private gallery and video testers now require an interactive display, an
unlocked keyguard and the reader's shown, focused window before recording
visible-card milestones. A separate native control opens the actual notification
shade, requires rejection, closes it, and requires acceptance again. That control
passes on **API 35 and minimum API 26: one actual pass each, zero skips and zero
failures**. Assertions execute on the instrumentation thread so a rejected
measurement reports an assertion without crashing the app's main thread.
These checks belong to the test APK. Screenshots still require inspection;
window focus alone does not prove that every intended pixel has painted.

The corrected full-feed ABBA comparison uses the same 907-original captured
window. Before every run, the owned emulator restores and hash-verifies its
53.5 MB app-state snapshot. Actual retained picture files supply observations,
and publisher-continuation reads remain zero. The display is awakened and System
UI collapsed before launch; the new assertions also guard each measured milestone.

| Observation | Release control 1 | ASCII candidate 1 | ASCII candidate 2 | Release control 2 |
| --- | ---: | ---: | ---: | ---: |
| Open → first inspected complete card | 7,959 ms | 7,858 ms | 8,180 ms | 7,879 ms |
| Open → complete-family checkpoint | 8,242 ms | 8,176 ms | 8,480 ms | 8,179 ms |
| Warm reentry → complete card | 1,269 ms | 1,253 ms | 1,285 ms | 1,321 ms |

All four corrected native invocations pass with no skips or failures. The first
target card contains every expected original, three distinct pictures and its
shared heading once. All eight first/warm screenshots are inspected. First-card
medians are **7,919 and 8,019 ms**; observations overlap, and the shortcut is
removed. These observations include the existing 100 ms compositor allowance.
They describe this two-core API 35 emulator, without a phone p95, energy or
general latency claim. They must not be combined with earlier comparisons to
infer a release regression or gain across different host sessions.

### Measure the existing source-analysis allowance before adding storage

The existing opt-in source-projection diagnostic compares six fresh/retained
grouping passes over 959 originals, 25 source bindings and 767 groups. Each pass
receives newly copied post/media objects; current admission still runs. A first
default-allowance replay passes, followed by two controlled allowance experiments:

| Local diagnostic | 8 MiB allowance | 16 MiB diagnostic allowance |
| --- | ---: | ---: |
| Retained source entries | 561 / 959 | 959 / 959 |
| Accounted retained bytes | 8,387,078 | 16,084,586 |
| Repeated retained grouping, two samples | 2,523 / 2,361 ms | 1,404 / 1,338 ms |
| Interleaved fresh grouping, two samples | 2,714 / 2,721 ms | 2,708 / 2,749 ms |
| Approximate managed heap released after owner collection | 3.40 MiB | 6.51 MiB |

All three diagnostic invocations pass, with zero skips/failures and identical
ordered grouping digests across every stage. Both explicit heap experiments
confirm collection of the projection owner. Their before/after managed-heap
differences are approximate measurements, separate from the accounting allowance,
native memory, peak PSS or phone memory pressure.

The repeated-grouping medians are **2,442 and 1,371 ms**, about 1.07 seconds apart.
This establishes a useful capacity lead for later comparisons of an active
window. It does not establish faster cold startup or painted-card delivery.
The diagnostic accepts an explicit allowance from 256 KiB to 16 MiB; production
keeps its existing **8 MiB** source-analysis limit. Persistent analysis storage,
an increased runtime limit and a new app release remain unimplemented here.
Further work needs an actual subsequent-image/video-evidence journey, memory
admission and recovery checks before changing the reader.

The retained changes are qualification guards, the bounded diagnostic override
and this evidence record. Runtime behavior remains the published 0.38.83.

## 0.38.83: reuse an unchanged source's presentation during matching

The source matcher and decorative-copy parser previously created temporary post
objects even when decoding or cleaning left both title and body unchanged.
The existing operation-local presentation cache keys its entries by the exact
post object. Those equal copies therefore repeated presentation work that the
same operation had already completed for the original record.

Both call sites now keep the supplied post when those two projected fields are
unchanged. A changed title or body still receives its own projected copy. Every
other source field stays intact. The existing synchronous workspace retains its
8 MiB accounting allowance and is discarded on return, failure or cancellation.
No cache, persistent state, model, request, matching threshold or permission is
added. Identical words on different originals do not share an object identity.

A separate native local-stage comparison uses 959 retained source originals.
Grouping takes **3,694 ms on the published 0.38.82 control, 3,558 ms on the
candidate and 3,699 ms on the final control**. All three produce the same ordered
grouping and content-observation digests, video/gallery request counts and
captured-read inheritance count. This diagnostic initializes its read set empty and
also evaluates all-read and unread controls; it does not infer owner read marks.
The isolated grouping improvement is about 3.7%; that operation alone does not
establish faster card delivery.

Before the additional scheduling repair below, an untraced API 35 ABBA
comparison separately opens the actual native feed in
four fresh processes. Before every run, it restores and hash-verifies the same
53.5 MB disposable app-state snapshot. The window contains 907 originals with
their captured subscription bindings. Actual retained picture files supply the
image observations; no fingerprints are injected and network fallback is blocked.

| Observation | 0.38.82 control 1 | Candidate 1 | Candidate 2 | 0.38.82 control 2 |
| --- | ---: | ---: | ---: | ---: |
| Open → first inspected complete card | 6,518 ms | 5,598 ms | 5,718 ms | 5,957 ms |
| Open → complete-family checkpoint | 6,885 ms | 5,847 ms | 5,984 ms | 6,208 ms |
| Warm reentry → complete card | 917 ms | 849 ms | 949 ms | 900 ms |

In that initial experiment, both candidate cold observations precede both
controls. The first-complete-card
median falls from **6,237.5 to 5,658 ms**, about **0.58 seconds or 9.3%**. Warm
observations overlap and do not establish a useful warm-speed gain. Every first
inspected target card already contains all three originals, its common heading
once and three distinct pictures; publisher-continuation reads stay at zero.
The actual painted candidate cards were inspected. Frame observation includes
the existing 100 ms compositor allowance after semantic readiness.

These small emulator comparisons do not establish phone p95, energy use or a
general latency distribution. The isolated source-stage and full-feed figures
measure different paths; their differences must not be added together. Initial
large-window preparation still takes several seconds, and live acquisition and
late counterpart delivery remain active work. See
[release qualification](RELEASE_QUALIFICATION.md) for the exact release checks.

The final-code repeat uses the same restored app-state snapshot and actual
picture files, with both qualified fixes present:

| Observation | 0.38.82 control 1 | Final 0.38.83 candidate 1 | Final candidate 2 | 0.38.82 control 2 |
| --- | ---: | ---: | ---: | ---: |
| Open → first inspected complete card | 5,820 ms | 5,911 ms | 5,767 ms | 5,885 ms |
| Open → complete-family checkpoint | 6,070 ms | 6,161 ms | 6,034 ms | 6,135 ms |
| Warm reentry → complete card | 901 ms | 900 ms | 917 ms | 884 ms |

First-card medians are **5,852.5 and 5,839 ms**. The overlapping observations
**do not establish a useful full-feed delivery gain**; the initial 0.58-second
difference is not a release speed claim. All first target cards remain complete.
A separate final-code local-stage control → candidate → control comparison
records grouping at **3,736, 3,531 and 3,661 ms**, with identical group, revision,
request-planning and initialized-unread inheritance results. The small source-object reuse is
retained for this measured reduction in local preparation, with no added cache
or component. That operation improvement does not resolve the first-card wait.

### Prefer the visible recording when equivalent preparation choices tie

The first candidate's native release run exposes a separate preparation issue:
after a repeat filter is cleared, the prepared pair can use another recording
from the visible card's caption group. The visible-card lane subsequently needs
its own recording too. The old positive control sometimes observes three sampler
calls instead of two; its count-only assertion can also pass before the extra
work begins.

A new deterministic planner regression reproduces the wrong representative on
the retained official 0.38.82 core as well. Focus membership now breaks ties
after the existing measured-evidence, sampling-rendition and metadata preferences.
An already measured alternative or an unusable focused recording keeps its
previous priority. Empty-focus global planning retains its original ordering;
every original key and alternate recording remains available. This changes
scheduling, not recording equality or collection admission.

The strengthened native control checks the exact sampled URLs, completion of the
prepared lane and footage observations for the actual visible recording and its
peer. Its negative repeat-filter check still requires zero speculative reads.
The initial failed qualification remains in the external evidence record and is
not treated as a successful release pass.

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
