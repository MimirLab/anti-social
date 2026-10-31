# Release qualification

This is a dated engineering record for released increments and their candidates.
The owner also uses the app on an Android phone and reports reader defects;
those reports have driven fixes across feeds, search, discovery and Context.
References below to missing physical-phone qualification mean the specified
controlled performance, energy, GPU or hardware matrix has not been measured.
They do not mean the app has never run on a phone. Likewise, a local artifact
named `preview` can contain the same non-debuggable APK as an official release.
See [reviewing and evidence](REVIEWING.md) for how these evidence types relate.

## 0.38.81: complete albums with short headings

A current three-platform capture reproduces two reading cards, six displayed
pictures and two copies of the common heading on the published 0.38.80 APK.
Both editions carry the same three-picture album, but one has only a two-word
heading while the others prepend a separate question. Those captions fall below
the existing gallery-excerpt limits. The new
[complete-album route](ATTACHMENT_COLLECTIONS.md#complete-albums-with-short-headings-03881)
requires an unambiguous match for every distinct picture before aligning the
short literal captions. The question keeps its actual source attribution.

Feed admission, caption composition and portable saved-copy validation share
that proof. Partial albums, contrary detailed observations, repeated ambiguous
slots, incompatible introductions and changed shared questions remain separate.
An early candidate also intercepted an existing long-gallery ambiguity; the
complete-album path is now restricted to collections with a genuinely short
caption, and the older assertion remains unchanged. No network request, decoder,
model, library, permission, database migration or persistent index is added.

Qualification on October 31, 2026 (UTC):

- Full JVM: **2,740 cases, 2,736 passes, four optional skips, zero failures or
  errors**. Sixteen additional methods cover complete and partial sets, source
  order, short literal alignment, attributed questions, contrary/ambiguous pixel
  evidence, independent save proof, competing roots, six-hour limits and crowded
  historical/relevant windows.
- API 35 frozen-release instrumentation: **282 named methods, 277 passes,
  five opt-in skips, zero failures**. API 26: **41 cases, 40 passes, one live
  opt-in skip, zero failures**, plus ordinary native startup. The owned older-API
  diagnostic is stopped afterwards. These are emulator checks.
- Nine actual image responses were obtained and decoded through the existing
  Android Gateway/decoder path without failures. The same real three-platform
  packet that fails on 0.38.80 now displays **one card, three pictures, one shared
  heading and one attributed question**, preserving all three original sources
  in its protected save. Its actual native caption and scrolling evidence were
  inspected. Captured source records, images and screenshots stay private.
- A separate synthetic native journey uses nine independently decoded copies of
  three pictures at different delivery URLs, including reversed source order.
  It checks the visible compact caption, three pictures, Context and the actual
  Save action. All three raw originals and nine source enclosures survive.
  Assertions accept the existing compact literal weave as well as paragraph
  presentation; a paragraph-only test tag is not evidence of missing text.
- Twelve retained-source packet runs cover **24 announcement-family replays
  and 81 source-edition instances**. Each expected family keeps one card, one
  common passage, its expected distinct media and all originals in the save.
  The separate **959-original** corpus retains exactly the same 767-row partition
  as published 0.38.80, with no missing/repeated original or split older family.
- An idle desktop-JVM ABBA comparison uses two fresh processes per runtime,
  five passes each. Pooled last-three warm medians are **1,144 ms for 0.38.80
  and 1,205 ms for 0.38.81** on that corpus. This is a small additional local
  grouping cost, not zero-cost matching or a phone/first-card latency benchmark.
  Raw runs overlap. No new web or media read is introduced by the collector.
- Release lint: **zero errors, 182 warnings and one hint**. Privacy-audit controls:
  **eight passes**; Context-retention scoring: **four passes**. The private-denylist
  source audit reports zero findings and two reviewed unchanged upstream files.
  Frozen APK-member correspondence, matching source/signer, unchanged native
  libraries and backed-up compatible preview promotion are verified before release.

**Remaining work:** this route uses already acquired picture evidence; editions
can remain separate until the ordinary reader observes their pictures. It does
not establish immediate cold-feed merging or physical-phone latency/energy.
Overfull relevant gallery windows remain explicitly unexamined, and a complete
live feed is not qualified as duplicate-free. Broader cold-feed latency and
unresolved announcement leaves remain active work.

## 0.38.80: recover a failed retained recording before comparing its current edition

A retained TikTok delivery returned HTTP 403 while the same original remained
readable through the existing public router. The returned complete source edition
had the same publisher and presented caption but a different CDN region and
object name. Treating those objects as aliases would be incorrect. The new
[recovery path](VIDEO_FOOTAGE.md#retained-tiktok-delivery-recovery-03880) conditionally
updates the existing ordinary row, then measures the current file independently.
Saved source editions and retained history are not rewritten.

Recovery is demand-driven, alternate-only and bounded by the existing comparison,
Gateway, resource and source-selection rules. It adds no request to successful
sampling. Completed parent attempts share a bounded five-minute per-post pause
across rotating URLs; explicit refresh can clear that local pause. A resource
hold or cancellation remains retryable. Changing connection selection retires
old admission, including a direct/Tor/direct round trip. TikWM's explicit server
wait also covers its POST and MEDIA uses of the same HTTP operation; an empty
metadata result remains distinct from an ordinary post failure.

Qualification on October 31, 2026 (UTC):

- Full JVM: **2,724 cases, 2,720 passes, four optional skips, zero failures or
  errors**. Twenty-three added methods cover whole-record admission, unchanged
  and foreign responses, bounded source selection, disclosure conservation,
  HTTP failure classification, connection selection and shared server deadlines.
  An early focused test reproduced the capability-specific server-pause gap.
- API 35 frozen-release instrumentation: **281 named methods, 276 passes,
  five opt-in skips, zero failures**. API 26: **40 cases, 39 passes, one live
  opt-in skip, zero failures**, plus ordinary native startup. The owned older-API
  diagnostic is stopped afterwards. These are emulator checks.
- Eleven ordinary native recovery methods exercise actual Room transactions,
  protected saves/history, disabled/removed/rebound subscriptions, missing and
  revised rows, retirement while waiting for the database, pre-commit rollback,
  purge races, resource deferral, foreground preemption and retry expiry. Three
  selected controls fail on the earlier draft and pass after correction: stopping
  the obsolete pair, leaving an unattempted resource-held recovery retryable,
  and preventing rotating failed URLs from buying repeated parent reads.
- A separate live check starts with the actual expired source record. The normal
  router obtains its whole current edition; real Gateway reads and Android
  frame decoding join it with its measured peer. The protected collection retains
  both current originals, while the former URL remains unobserved. One run takes
  **17,492 ms**, including two preempted sampling attempts. Cancellation is
  retained as cancellation, rather than a successful read or differing footage.
- Actual native feed journeys also start with the expired original and no scene
  observations. Two-source runs form one card **10,440 ms and 15,258 ms after
  opening**. The stronger run adopts the captured body through its real control,
  retains the source-specific title once and preserves the row's pixel anchor.
  A six-original, five-platform run recovers the source, measures three recording
  representatives and shows the full family **27,062 ms after opening**. Its
  complete attributed body is visible after adoption; distinct raw editions
  remain available. Screens and detailed inputs stay private.
- A separate save replay of those newly observed six originals initially fails
  the older capture's four-entry attachment expectation. The actual save already
  preserves every original. This new evidence set has no strict observed cover
  match for its Instagram still, so the inventory retains that source-marked
  preview beside four playable editions. The native card shows one player and
  one media-edition shelf. Its separate expectation preserves all five inventory
  entries; the prior capture and its four-entry assertion remain unchanged.
- Initial semantic readiness events are separate from presented pixels; early
  screenshots can precede the next drawn frame. They are not a qualified
  first-visible-card latency benchmark. The observations above are individual
  mixed-cache/live runs, not phone measurements, latency percentiles or energy
  qualification. The larger recording family still takes noticeable time.
- Twelve retained-source packet runs cover **24 announcement-family replays
  and 81 source-edition instances**. Each expected family has one row, its
  expected attachments, one common passage and all original sources saved.
  The same **959-original** corpus retains 767 rows, every original and every
  previously combined family. No new family combines in that frozen corpus;
  the expiring delivery is qualified separately through a current source read.
- Release lint: **zero errors, 182 warnings and one hint**. Privacy-guard
  controls: **eight passes**; Context-retention scoring: **four passes**.
  The private-denylist tree audit reports zero findings and two reviewed unchanged
  upstream files. Final frozen-member correspondence, source/signer verification,
  preview backup and compatible promotion are checked before release. There is
  no added model, dependency, permission or database migration.

**Remaining work:** current recordings can still be unavailable or outside the
qualified single-video source shape. General expired-playback recovery is not
added. Current recording objects never inherit old-file evidence, and protected
old copies are not silently replaced. Dense gallery-window continuation, broader
cold-feed latency and unresolved announcement leaves remain open. This increment
and its retained cases do not establish that a complete live feed has no remaining
duplicates.

## 0.38.79: unrelated history cannot disable local picture collection

A retained three-source gallery reproduced one card in isolation but two cards
and six displayed pictures when surrounded by 128 unrelated historical galleries.
The gallery and picture-only reshare passes stopped entirely when their global
candidate count exceeded 128. Both now use the existing six-hour eligibility
window to bound comparisons. Overfull local windows remain unexamined; their
potential competing roots/destinations cannot silently disappear from ambiguity
checks. No acquisition, model, dependency, permission or schema is added. See
[attachment collections](ATTACHMENT_COLLECTIONS.md#time-scoped-gallery-and-picture-reshare-admission-03879).

A separate synthetic gallery control reproduced successful feed grouping but failed
portable composition/save validation. One edition directly carrying pictures
from otherwise disjoint, complete-caption galleries now supplies a bounded
co-occurrence proof. Separate stock-picture chains do not. The same final
caption, timing, parent, destination and detailed image guards still apply to
every original. Common text reads once, distinct pictures remain attributed,
and protected saves replay that proof without a local subscription lookup.

Qualification on October 30–31, 2026 (UTC):

- Full JVM: **2,701 cases, 2,697 passes, four optional skips, zero failures or
  errors**. Twenty-one new methods cover time boundaries, overflow, ambiguous
  roots/targets, conservation, direct co-occurrence and rejected chains, changed
  claims, missing pictures, parent/date/destination constraints and contrary
  detailed pixels. Earlier source-baseline selections reproduce the global-bank
  failures and the portable composition gap before correction.
- API 35 frozen release instrumentation: **269 named methods, 265 passes,
  four opt-in skips, zero failures**. API 26: **28 passes,
  zero skips or failures**, plus normal native startup. Screens are inspected;
  the owned older-API diagnostic is stopped afterwards.
- Three focused native controls pass. The pre-portable candidate fails its
  independent proof assertion; the corrected candidate displays four actual
  decoded pictures and one heading, verifies per-image source attribution,
  Context and an actual Save-button action retaining all three raw originals.
  The captured three-source gallery with 128 unrelated historical editions
  changes from **two cards/six pictures to one card/five distinct pictures**.
  All 131 originals remain represented; the target save preserves its three
  source editions. A separate picture-only reshare control exercises a large
  surrounding history and protected save.
- A direct-group assertion in the first new native fixture omitted the exact
  membership needed for an opaque Instagram original. It was corrected to use
  the same explicit membership as fixture storage and the real feed. Portable
  save validation remains independent of that database. The shared direct
  picture-fixture writer also uses the existing lease while publishing its
  file/row pair, preventing startup orphan maintenance from racing test setup.
  Neither correction changes shipping network or cache semantics.
- Twelve retained-source packet runs cover **24 announcement-family replays
  and 81 source-edition instances**. Every expected family has one row, its
  expected attachments, one common passage and all original sources saved. The
  separate six-platform recording check initially fails because its frozen
  TikTok URL does not supply
  bounded ranges. Reacquiring the same original through the existing public
  router takes 1,338 ms; the raw returned post identifier is checked. A separate
  replay uses the entire freshly acquired edition and the unchanged other five
  originals. Existing bounded samplers obtain TikTok/X/Facebook scenes and the
  six-source family has one row, one shared passage, its differing title and all
  four supplied playable variants, with a faithful save. The old frozen-URL
  failure is retained. This does not implement automatic renewal of a stored
  TikTok URL or establish that every older recording will remain retrievable.
- The same **959 originals** have 767 rows on both 0.38.78 and this candidate:
  all originals survive, no previously collected family splits and no additional
  family combines. This retained corpus does not contain the newly constructed
  131-original crowded-gallery counterexample.
- A settled desktop ABBA comparison uses the same 959 originals, four fresh
  processes and five passes per process. Pooled last-three-pass medians are
  **1,169.5 ms on 0.38.78 and 1,174.5 ms on this candidate**, with overlapping
  runs and identical partitions. This establishes no useful speed gain. These
  are JVM observations, not physical-phone latency, p95, energy or
  sustained-browsing qualification.
- Release lint: **zero errors, 182 warnings and one hint**. Privacy-guard
  controls: **eight passes**. Context-retention scoring: **four passes**.
  Private-denylist source audit has zero findings and two reviewed unchanged
  upstream files. Final frozen-member correspondence, source/signer integrity,
  preview backup and compatible promotion are checked separately before release.

**Remaining work:** the 128-candidate limit still applies to an overfull relevant
window. The change prevents unrelated history from disabling other windows; it
does not claim all ambiguous dense windows are fully traversed. Cold feed and
recording-merge delays, stale recording URL renewal and specific unlocated
picture-only leaves remain under investigation. These checks do not establish
that every duplicate in a current complete feed has disappeared.

## 0.38.78: damaged decorations do not repeat a proven announcement

A captured three-platform announcement reproduced two reading cards, two copies
of its picture and two occurrences of its prose. One public source returned
literal replacement characters inside a trailing emoji sequence. Its intact
wording, amounts and decoded picture agreed with the other editions, but the
existing decoration projection preserved those malformed characters. The
[bounded comparison](ATTACHMENT_COLLECTIONS.md#damaged-trailing-decorations-03878)
now admits that case and displays an actual intact edition. Every raw original,
including the damaged text, survives the protected save. No source fact,
chronology, recovery preference or network allowance is changed.

Qualification on October 30, 2026 (UTC):

- Full JVM: **2,680 cases, 2,676 passes, four optional skips, zero failures or
  errors**. Nine new methods cover input order, actual-edition selection, raw
  conservation, entity decoding, damaged words and amounts, signs, destinations,
  questions, timing, avatars, conflicting pixels and video-cover ambiguity.
  The initial thirteen-case selection reproduces three baseline failures. A
  later counterexample catches empty image addresses in the preliminary draft;
  its expanded nine-method selection has one expected failure before correction.
  That draft was never installed in the live preview or published.
- API 35 release instrumentation: **266 named methods, 262 passes, four opt-in
  skips, zero failures**. API 26: **25 passes, zero skips or failures**, plus
  normal reader startup. These are emulator observations, with actual native
  screens inspected; the older API fixture is stopped afterwards.
- Two focused native controls verify decoded media, one caption, all platform
  attribution, Context and an actual Library save. The private three-source
  reproduction now has **one card, one picture and one caption**, with every
  captured source retained unchanged. The retained three-source gallery from
  0.38.77 is also rechecked independently: one card and five distinct pictures.
- Twelve additional private packet runs exercise **24 announcement-family
  replays and 84 source-edition instances**, including earlier decorative,
  gallery, wording and recording cases. Each family has one row, its expected
  distinct attachments, one common passage and a faithful protected save. A
  six-platform case retains all four distinct recordings and its differing title
  while reading the common body once. These are retained-capture correctness
  checks, not a fresh whole-feed latency benchmark or independent account count.
- The same 959-original retained input has 767 rows on both 0.38.77 and this
  candidate, with **all originals conserved, no previous family split and no
  additional family combined**. This is a conservation check under concurrent
  qualification work, not a latency comparison.
- Release lint: **zero errors, 182 warnings and one hint**. Privacy-guard unit
  checks: **eight passes**. Context-retention scoring controls: **four passes**.
  The working-tree privacy audit reports zero findings and two explicitly
  reviewed, unchanged upstream files. Corresponding-source, final APK integrity
  and compatible preview promotion are verified separately before publication.

**Remaining work:** a separate counterexample adds 128 unrelated synthetic
galleries to that retained three-source family. The previously combined family
splits again because a global gallery-anchor cap skips the entire bank. The
added galleries lie outside the family's matching time window; the result
reproduces the scope problem, not a failure of its individual picture proof.
This limit remains unchanged in 0.38.78. A bounded time-scoped scheduler needs
its own ambiguity, overflow, native and conservation qualification. It has not
yet been established as the cause of a particular current reader's full-feed
report. The earlier cold-feed/video delay also remains open; neither these
correctness checks nor a method trace establishes physical-phone p95 or energy
performance. This release does not claim that every feed duplicate is fixed.

## 0.38.77: opening reminders do not veto proven gallery pictures

A retained three-original gallery reproduced two cards and six displayed
attachments even after its eleven exact image URLs were acquired through the
Gateway and sandbox-decoded. An opening reminder encountered the caption's
negation guard. The newer thread replay had supplied extra reply evidence that
the retained copy did not contain; that earlier passing case did not establish
correctness for this one. See the
[collection contract](ATTACHMENT_COLLECTIONS.md#opening-reminders-in-proven-galleries-03877).

Qualification on October 30, 2026 (UTC):

- Full JVM: **2,671 cases, 2,667 passes, four optional skips, zero failures or
  errors**. The initial fourteen-case selection has three expected baseline
  failures and eleven passes. It exercises literal reminders, source attribution,
  changed pictures/claims, timing, whole-caption boundaries and ambiguous roots.
  The existing publisher-reply control uses factual negation to continue testing
  its separate full-edition witness requirement.
- A preliminary reminder exception incorrectly accepted nested contractions and
  “cannot.” Explicit counterexamples reproduce the defect; the expanded selection
  fails one method before the correction. The unqualified draft and failed
  controls are retained privately. It was never installed in the preview or
  published. The corrected remainder guard is also tested natively with ASCII
  and typographic apostrophes.
- API 35: **265 distinct named methods, 261 passes, four opt-in skips, zero
  failures** on the frozen application/tester. Android 8/API 26: **24 passes,
  zero skips or failures**, plus native startup and screen inspection.
- Two focused cold-gallery native controls pass, including actual local-byte
  decoding, Context, all distinct pictures and a Save-button action retaining
  both complete originals. The actual retained capture now presents **one card,
  five distinct pictures, one shared heading and one attributed introduction**;
  all three originals survive the save. Actual caption/source screens are
  inspected privately. No reply identity or missing source edition is invented.
- The preceding four-original clause-composition capture still passes: one card,
  two pictures, both complete differing clauses once and faithful saves. All
  nine earlier native capture packets also pass: **21 families, 72 originals**.
  The fresh five-original gallery replay passes separately.
- The 959-original comparison uses exactly the same source facts and acquired
  image observations on both implementations. Rows fall from 768 to 767; every
  original survives, zero prior families split, and only the intended family
  changes. [Operation timings and their limits](FEED_PERFORMANCE.md#post-03876-distinguish-delayed-video-qualification-from-a-caption-veto)
  do not establish a consistent speed gain or physical-phone behavior.
- The warm 46-post video replay passes without renewed-URL resampling. The actual
  904-post video replay also finishes as one five-original family with complete
  wording. Its checkpoints are 5,728 ms first-visible, 10,175 ms full-family and
  11,211 ms complete adopted caption. These single emulator observations retain
  a significant delivery delay; they do not qualify phone p95 or energy use.
- Privacy-guard controls: **eight passes**; Context retention controls: **four
  passes**. The latter initially used an unsuitable module-import invocation;
  running the established script directly passes all four. Release lint:
  **zero errors, 182 warnings and one hint**. Clean-commit artifact/source,
  signing, privacy/archive and preview-state checks follow
  [publishing](PUBLISHING.md).

No model, dependency, permission, schema change or new retrieval is added. Other
reported image leaves, unavailable counterpart acquisition and cold-feed delays
remain separate investigations. Topic-keyword inventory alone does not identify
a reported captionless image or establish its equivalence.

## 0.38.76: media-free extractions retain their counterpart's proof

A captured four-original announcement reproduced two cards despite matching
pictures and an already functioning attributed-clause composer. Three richer
editions combined when examined alone; adding a proven media-free Facebook copy
prevented both complete-link grouping and whole-family composition. This pass
repairs that evidence path rather than adding an organization-name matcher.
See the [collection contract](ATTACHMENT_COLLECTIONS.md#preserve-richer-copy-evidence-through-extraction-cohorts-03876)
for source, timing, scope, candidate and attribution boundaries.

Qualification on October 30, 2026 (UTC):

- Full JVM: **2,667 cases, 2,663 passes, four optional skips, zero failures or
  errors**. Six new controls exercise all input permutations, complete literal
  source paths, changed claims/links/times/authors, missing and ineligible
  witnesses, conflicting richer artwork, multiple matching witnesses and the
  same rule on another platform. The initial four-case subset has two expected
  failures on the previous implementation; its negative controls already pass.
- Published 0.38.75 independently fails the new native initial-arrival and
  late-arrival controls. The candidate passes both, including a retained pixel
  anchor within two pixels, both complete visible alternatives, Context and an
  actual Save-button action preserving all four originals. The media-free copy
  remains media-free and receives no attachment-source credit.
- Preliminary native assertions incorrectly expected raw `@` notation in
  displayed account labels; one synthetic handle also exceeded X's length
  limit. Captured screens/semantics showed the complete clauses with the honest
  identifier fallback. The fixture now uses valid handles and checks visible
  labels separately from literal source reconstruction and unchanged saved
  originals. These failed controls remain recorded; no presentation guard was
  removed to make them pass.
- The actual four-original replay uses four downloaded image inputs. Baseline:
  **two cards, four displayed attachments, two shared-heading occurrences**.
  Candidate: **one card, two distinct pictures, the shared heading once, each
  complete source-specific clause once, every original and a faithful save**.
  Both pictures match their counterparts, and the crossed pairs differ. The
  actual native card and caption/attribution screens are inspected privately.
- The retained-window comparison covers **959 originals and 25 subscriptions**.
  It adds the actually acquired missing counterpart to the retained 958-post
  window and verifies the other source facts unchanged. All originals survive;
  rows fall from 769 to 768, only this family changes, and **zero previously
  accepted families split**. The [timing record](FEED_PERFORMANCE.md#03876-limit-counterpart-planning-to-relevant-copy-cohorts)
  separates small desktop-JVM operation timings from Android/user experience.
- API 35: **264 distinct named methods, 260 passes, four opt-in skips, zero
  failures** on the frozen application/tester. The declared-recording and
  power-saver checks run separately from the 262-method main selection.
  Android 8/API 26: **22 passes, zero skips/failures**, plus native startup.
- Nine earlier native captured-packet replays pass: **21 families, 72 originals**,
  shared passages once, expected distinct attachments and faithful saves. The
  preceding five-original gallery replay also passes. The final actual warm-feed
  replay retains its complete four-original caption without renewed-URL sampling.
- Privacy-guard controls: **eight passes**; Context retention controls: **four
  passes**. Candidate release lint: **zero errors, 182 warnings and one hint**.
  Clean-commit artifact/source, signing, archive/privacy and retained-preview
  checks follow [publishing](PUBLISHING.md).

No model, dependency, permission, schema change or additional retrieval request
is introduced. This is not a claim that every reported duplicate is repaired:
older gallery records lacking the needed reply evidence, other reported image
leaves and source-dependent acquisition/adoption delays remain under
investigation. Controlled physical-phone latency, p95 and energy measurements
are not supplied by these native emulator and desktop-operation checks.

## 0.38.75: renewed video observations and qualified gallery replies

This increment repairs two reproduced paths without adding a model, dependency,
permission, database migration or automatic discovery request. The existing
video observation cache can recognize a narrowly qualified renewed TikTok object
URL; a qualified Nitter RSS reply heading is attribution rather than authored
prose. A separate same-publisher full-caption witness can corroborate the shorter
gallery heading while preserving its complete introduction. The
[collection contract](ATTACHMENT_COLLECTIONS.md#reuse-renewed-video-observations-and-qualify-gallery-reply-headings-03875)
describes the boundaries; exact-byte and attachment equality remain separate.

Qualification on October 30, 2026 (UTC):

- Full JVM: **2,661 cases, 2,657 passes, four optional skips, zero failures or
  errors**. Controls cover malformed/ambiguous signed routes, namespace and
  transformation separation, conflicting runtime hints, restored observation
  reuse, literal reply attribution, missing/old/different-publisher witnesses,
  changed artwork and source-preserving gallery composition.
- Published 0.38.74 fails both new native cache controls as expected. The initial
  focused JVM cache controls record three failures; the independent gallery
  controls record two. Candidate controls and the full suite pass. These red
  records remain in private evidence rather than being counted as passing tests.
- A production-Gateway probe downloaded both actual renewed renditions in full:
  **2,636,679 bytes each with identical SHA-256**, 26,493 ms duration and 1280×720
  dimensions. Ordinary and isolated frame decoding both succeed. This one-pair
  qualification supports the observed object form, not arbitrary CDN URLs or
  transferring file/signature validation through the derived-scene key.
- Actual warm-feed replays use a 46-post window, four focused originals and a
  prior production-sampler read. Two baselines repeat renewed-URL sampling;
  two candidates do not. A further replay on the final qualified application
  passes with complete captions and no renewed-URL sampling. See
  [timings and limits](FEED_PERFORMANCE.md#03875-renewed-delivery-urls-reuse-prior-scene-work).
- API 35: **262 distinct named methods, 258 passes, four opt-in skips, zero
  failures** on one frozen application/tester. The declared-recording and
  power-saver checks run in separate processes; the remaining 260 methods pass
  in the main selection. Android 8/API 26: **20 passes, zero skips/failures**,
  plus actual native reader startup and screen inspection.
- A five-original gallery with eight downloaded image inputs reproduces **two
  cards, seven attachments and three headline occurrences** in the baseline
  native replay. The corrected replay has **one card, five distinct pictures,
  one headline occurrence, every captured original and a faithful protected
  save**. Its caption/source screen is inspected. The generated reply wrapper
  supplies account attribution, never an invented parent-post relationship.
- Nine earlier captured-packet native replays pass: **21 families, 72 originals**,
  expected distinct attachments, shared passages once and faithful saves. The
  native caption/attribution screens and retained preview feed are inspected.
- Privacy-guard controls: **eight passes**; Context retention controls: **four
  passes**. Release lint: **zero errors, 182 warnings and one hint**. The tree
  privacy audit has zero findings with two unchanged reviewed upstream files;
  source-archive auditing and clean-commit artifact correspondence follow
  [publishing](PUBLISHING.md).

**Still open:** another reported family is independently reproduced with both
pictures matching and its text composer rejecting differently written
organization references. No name equivalence is inferred or guard weakened to
hide that failure. Older cached gallery leaves can also lack the reply evidence
supplied by a fresh response; this fresh-capture repair is not evidence that every
retained variant is fixed. Other reported families and source-dependent first
acquisition/adoption delays remain under investigation. These emulator checks do
not establish physical-phone latency, p95 or battery behavior.

## 0.38.74: declared recordings and public download retention

The reader retains an exposed public progressive download when a selected
Nitter timeline gallery has disabled HLS playback. A source-scoped provider
recording identifier can collect the original and a declared repost with
different captions, including retained preview-only records. Existing caption
families form first; the new collection validates the entire union and its
portable saved-copy proof. Exact recording renditions can share one attachment.
No new request, model, dependency, permission or database migration is introduced.
The [collection contract](ATTACHMENT_COLLECTIONS.md#collect-declared-recording-copies-after-caption-families-03874)
describes the source, identity, time and size bounds.

Qualification on October 30, 2026 (UTC):

- Full JVM: **2,655 cases, 2,651 passes, four optional skips, zero failures or
  errors**. New controls cover disabled-player downloads, retained previews,
  source/proxy scope, author versus curator, unrelated covers, multiple clips,
  time-window chaining and preserving an existing cross-platform caption family.
- The first implementation failed a new mixed-family regression: early recording
  cohorts could steal an original from its later caption counterpart. That order
  was rejected. Collection now expands frozen caption families instead.
- API 35 qualification covers **260 distinct named methods: 256 passes, four
  opt-in skips, zero remaining failures** on the same frozen application. The
  258-method regression process records 254 passes/four skips; the power-saver
  guard and new recording check run separately. The recording check uses a
  corrected tester build; other method bodies and the application are unchanged.
- The initial new native check passed its card, caption and save assertions,
  then incorrectly called an image-fixture helper requiring cached video bytes.
  Its failure remains recorded. The corrected check verifies every original
  source field and explicitly checks that Quick Save downloaded neither video.
  It passes on API 35 and in the minimum Android 8/API 26 selection: **18 passes,
  zero skips/failures**, with native reader startup checked separately.
- The captured public repost pair reproduces two cards on 0.38.73. The corrected
  native replay has **one card, both captions, one shared preview, both original
  source records and a faithful protected save**. This replay exercises retained
  source records; it is not a fresh media-acquisition or phone-latency benchmark.
- Nine further native captured-packet replays pass: **21 families, 72 originals**,
  expected distinct attachments, common passages once and faithful saved copies.
  The actual attributed-caption and media-source screens are inspected privately.
- Privacy-guard controls: **eight passes**; Context retention scoring controls:
  **four passes**. Release lint has **zero errors, 179 warnings and one hint**.
  The source-tree privacy audit has zero findings with two unchanged reviewed
  upstream exemptions; source-archive auditing is performed separately.

The additional escaped short-video edition, other reported announcement families,
and source-dependent observation/adoption delays remain open. This increment
removes a demonstrated need to wait for pixel/video evidence when an exact
qualified recording identifier is already present; it does not establish
universal or instantaneous merging. Signing, archive, privacy and promotion
checks follow [publishing](PUBLISHING.md).

## 0.38.73: prepared prefixes and complete short-caption presentation

One unused video prefix can be prepared for the retained reading position;
decoding waits for the real readable card. Session, source, input, parent budget,
foreground priority, lifecycle cancellation and resource checks remain in force.
Retries still validate their prefixes. Optional metadata failure no longer cools
a usable direct recording route after its peer becomes measured.

The established-announcement collector can attach a distinctive complete
decorative equivalent without waiting for an off-screen picture. Shorter claims
still need artwork; ambiguity, publisher, time, question, destination, protected
symbol and regional flag checks remain. A final presentation fallback shares
literal prefixes and source-subset continuations in short captions. It preserves
coupled tails and each original's normalized token path, with exact source records
and case-sensitive destinations retained. Existing composers run first. No new
dependency, permission, database migration or model call is introduced.

Qualification on October 30, 2026 (UTC):

- Full JVM: **2,647 cases, 2,643 passes, four optional skips and zero
  failures/errors**. Frozen API 35 release selection: **259 named methods,
  255 passes, four opt-in skips and zero failures**. Returned method identities
  exactly match the requested set. Android 8/API 26: **17 passes, zero skips
  or failures**, including preparation, cancellation, details, resource policy
  and actual presentation-path destination/formatter checks; native startup and
  the ordinary shell are checked separately.
- The Android power-saver transition is qualified in a fresh instrumentation
  process. Earlier mixed-suite runs stalled in Compose/Espresso idle after the
  configuration transition; they remain incomplete runs, not successes. The
  guard passes separately; the other process records 254 passes and four opt-in
  skips. Neither the assertions nor production power-saver policy are bypassed.
- An earlier broad candidate recorded **253 passes, one failure and four
  skips**. It placed the new prefix composer before an existing punctuation
  formatter. That regression was rejected. The final fallback order passes
  the original full UI regression and a new Android check preserving both
  ordinary formatting and case-sensitive inline destinations.
- Two actual full-application video replays pass through production preparation,
  Gateway and isolated sampling, without an engineering scheduling switch or
  fabricated scene. One retains six originals and four literal variants once;
  the other retains four platform editions and displays its common heading and
  continuation once. Both retain the two-pixel body-adoption bound. A separate
  fresh saved-copy replay passes with all four originals and three media entries.
  Native video-selector and attributed-caption screens are inspected privately.
- Nine additional native captured-packet replays pass: **21 announcement
  families and 72 originals**, one card and required common passage once per
  family, expected distinct attachments and faithful saved originals. Source
  inputs, acquired media and screenshots stay private outside the repository.
- Privacy-guard controls: **eight passes**; Context retention scoring controls:
  **four passes**. Release lint has **zero errors, 182 warnings and one hint**.
  The source-tree privacy audit has zero findings with two unchanged reviewed
  upstream exemptions. Source archives and signing/promotion checks follow the
  [publishing procedure](PUBLISHING.md), including frozen member comparison and
  the anonymously downloaded permanent APK.

The final large-window replay still takes **12.487 seconds from first card to
complete family**; the smaller one takes **13.283 seconds**. These are different
captured windows and dated public-media reads, not a phone p95 or a cold/warm
comparison. Other reported families and controlled phone/energy measurements
remain open. This increment does not establish instant or universal deduplication.
See [the measured feed investigation](FEED_PERFORMANCE.md#03873-initial-video-prefixes-and-complete-short-caption-collections).

## 0.38.72: coverage priority, captured details and reader-attention cancellation

The settled-card video scheduler tries current missing-platform coverage before
another fresh offer on platforms already represented by that card. The latter
remains a fallback, preserving distinct editions and the existing comparison
allowance. A current offer introducing a platform retains its cheap direct path.

An explicit captured-details tap now admits against that card's relevant source
and group snapshot instead of rejecting unrelated feed changes. Relevant churn
gets a fresh draft within four attempts, then an explicit message if necessary.
The established card key, chronological order, originals and pixel anchor remain.
Video/gallery dwell also cancels its own coroutine directly on Activity stop or
lost window focus; a resume revision restarts eligible work even when Android
paused recomposition between those events. Explicit refresh/download policy,
Gateway/Tor enforcement, source ownership and decoder/acquisition bounds remain.

Qualification on October 30, 2026 (UTC):

- Exact published 0.38.71 negative controls: **zero passes, three expected
  failures and zero skips**. The native Activity-stop video and gallery checks
  each time out waiting for cancellation; the coverage-priority assertion fails.
  The lean candidate passes all **twelve focused checks**, including resume,
  retained same-platform fallback and card-scoped details admission.
- Fresh full JVM: **2,633 cases, 2,629 passes, four optional skips and zero
  failures/errors**. Frozen API 35 release selection: **236 named methods,
  232 passes, four opt-in skips and zero failures**. Returned method identities
  exactly match the requested set, including feed, media, captions, saves,
  reading state, source/provider and privacy regressions.
- Two additional actual live methods: **two passes, zero skips/failures**.
  The full application with ordinary scheduling retains six originals, each of
  four literal variants once, and the two-pixel body-adoption bound. The actual
  video and attributed-caption screens are inspected. The real Gateway/isolated
  decoder foreground-preemption check retains two blocks, revalidates and
  completes four scenes, then releases all retained blocks.
- Minimum API 26: **five actual passes, zero skips/failures**, covering the new
  cancellation/resume, coverage/fallback and details-admission checks. The native
  reader starts afterward without a fatal startup error; its screen is inspected
  under ordinary shell authority. Engineering emulator commands add no user
  installation or privilege requirement.
- Release/test assembly and lint pass: **zero errors/fatals, 182 existing
  warnings and one hint**. Eight Python privacy controls and four retention
  scoring controls pass. No dependency, permission or database migration is added.

These are correctness and scheduling repairs, **not a claim that delayed merging
is solved**. The frozen candidate's first-card/complete-family/adopted-body times
are **7.609/25.426/28.946 seconds** after opening, with four recording starts:
the family still follows its first card by **17.817 seconds**. An earlier lean
replay has a 14.273-second gap. Source-dependent cold acquisition and general
latency remain open; no phone p95 or energy figures are inferred.

The [native investigation](FEED_PERFORMANCE.md#03872-investigation-prioritize-coverage-and-keep-details-adoption-reliable)
also retains an intermittent old-APK body-adoption failure and a rejected heavy
startup-sampling prototype. Its earlier sample start did not yield a useful
consistent whole-feed gain, so that component was removed before qualification.
Failed observations and prototype source/APKs remain private external evidence.
Promotion requires clean source-stamp, all-member frozen-artifact, signing,
corresponding-source and protected-preview-state verification; publication uses
the fixed-name APK and anonymous full-download check.

## 0.38.71: retain validated recording blocks through preempted retries

Foreground reading still preempts deferred video comparison. Its retry now
revalidates the prefix and can reuse completed immutable blocks for the same
recording, source, exact connection session and original parent allowance.
Final destination, length, validator and prefix must match. Changed content
starts fresh; a mode round trip, different parent or expired window cannot
reuse the view. Validator-free observations retain their final prefix check.
Completion, cancellation and purge release the attempt's references. There is
no persistent byte cache or new background acquisition path.

Per-attempt 4 MiB/16-exchange limits, the pair's 8 MiB/32-exchange and 45-second
bounds, three-attempt ceiling, decoder authority and foreground priority remain.
The existing collection, literal caption, distinct recording/picture and source
retention rules are unchanged. No dependency, permission or database migration
is introduced. This fixes repeated completed-block downloads; it does not make
all delayed merging invisible.

Qualification on October 30, 2026 (UTC):

- Full JVM: **2,633 cases, 2,629 passes, four optional skips and zero failures or
  errors**. The sixteen range methods include fresh-prefix reuse, changed
  representation, source/mode/parent isolation, reserved final verification,
  exhausted allowances, cancellation and cached-read session withdrawal.
- Frozen API 35 release selection: **231 named methods, 227 passes, four opt-in
  skips and zero failures**. Returned identities match the requested methods.
  It includes native feed/caption/media/save/reading-state regressions, the
  three new range-retry controls, recording-attempt cleanup, exact native mention
  semantics and the connection/provider tests. An earlier focused native set
  separately passes all 24 methods.
- Minimum API 26: **six actual passes, zero skips or failures**, repeated against
  the frozen release candidate. The native reader starts afterward without a
  fatal startup error and its actual screen is inspected under ordinary shell
  authority. Engineering device commands are not an app requirement.
- Release/test assembly and lint pass with **zero errors/fatals, 182 existing
  warnings and one hint**. Eight Python privacy controls and four retention
  scoring controls pass. The public-source audit has no findings after verifying
  the unchanged generated brand catalogue against its pinned upstream archive.

The frozen candidate then passes **two additional actual live methods, zero
skips or failures**: the full application replay with ordinary scheduling and
the controlled foreground preemption. Its complete six-original family appears
25.023 seconds after opening, 14.167 seconds after its first focused card; the
complete body appears at 28.809 seconds. Four recordings start. All four literal
variants appear once within the two-pixel position bound, and the actual caption
screen is inspected. The controlled retry retains two blocks at interruption,
revalidates its prefix, finishes with four scenes and thirteen newly fetched
blocks, then releases every retained block. These are dated emulator observations,
not evidence that the general visible-family delay has been solved.

The [acquisition investigation](FEED_PERFORMANCE.md#post-03870-investigation-keep-validated-blocks-through-foreground-preemption)
records actual Gateway/decoder preemption and recovery, successful full-feed
presentation checks, a failed old-APK source control and rejected smaller-file
routes. The test-only early-scheduling experiment preserves the family but does
not establish an end-to-end speedup. It remains disabled in ordinary scheduling.
An ordinary-scheduling candidate replay retains all six originals, each of four
required literal variants once and the two-pixel body anchor, but its visible
family still arrives 18.817 seconds after its first card. Source-dependent
acquisition and cold preparation remain open. No phone p95 or energy figures
are inferred from these emulator observations.

## 0.38.70: current video proposals as observations stream in

The visible-card scheduler now qualifies its fast global offer against the
video-bank revision used to prepare it. If observations have advanced, it uses
the existing focused planner over current footage. It does not wait for the
whole feed to finish grouping. Current offers keep their direct path; an active
pair retains its existing second-recording and cancellation behavior.

Two new native controls reproduce the old selection branch starting its next
recording from stale information. The corrected branch verifies current focused
selection, a cheap fresh offer, idle suspension and renewed eligibility of that
same held recording after its revision becomes current. A startup refresh can
legitimately invalidate a fixture's stamp; the fixture now cancels and joins its
real startup view model before mounting the synthetic catalog. The same corrected
tester passes the candidate and still reproduces both old-branch failures.

A broader platform-pruning prototype is rejected because another edition on an
already represented platform can supply unique content. The original discovery
paths and allowances remain available. The [read-probe investigation and native
comparison](FEED_PERFORMANCE.md#03870-current-video-offers-without-removing-discovery-paths)
also reject naive range coalescing and larger fixed fetch blocks. No video
threshold, decoder authority, gateway policy, network concurrency, model,
dependency, permission or database migration changes.

The fresh full JVM run covers **2,623 cases: 2,619 passes, four optional skips and
zero failures/errors**. The frozen shipping APK's native selection covers **221
named checks: 218 passes, three opt-in skips and zero failures**, with returned
method identities verified. Release/test assembly and lint pass; lint retains
182 warnings and one hint with zero errors/fatals. Eight Python privacy controls
and four retention-scoring controls pass. The focused native set separately
passes all **27** methods after removal of the rejected pruning prototype.

The two new scheduling methods also pass on the minimum API 26 emulator:
**two actual passes, zero skips and zero failures** in **7.104 seconds**. The
native reader then starts without a fatal startup error under ordinary shell
authority. An initial wrapper incorrectly accepted an `OK (2 tests)` footer
containing two assumption skips because it omitted the isolation flag. That
attempt is retained as invalid qualification; the corrected run supplies the
flag and requires the exact method-status counts before accepting its result.

Ten captured native presentation/save replays cover **22 family cases and 73
original editions**, retaining one card, one checked common passage, expected
distinct media and every saved source original. The actual source-bound preview
preservation check passes too. All 22 native card/caption cases and the actual
full-feed adopted body are inspected directly; private captures stay outside the
public source.

The official 0.38.69 → candidate → official 0.38.69 comparison preserves its
six-original family, four literal body variants, pixel anchor and four started
recordings in all three runs. It **does not establish an overall speedup**:
the later control is about as fast as the candidate, while the earlier control
spends much longer acquiring its first recording. It is evidence of preserved
behavior and the named scheduling correction, not a repaired acquisition delay.

The final shipping replay opens at **706 ms**, first shows its focused card at
**8,744 ms**, observes the three required recordings at **20,323 ms**, shows the
six-original family at **21,478 ms**, and adopts its complete body at **24,928 ms**.
All four required literal variants appear once within the existing two-pixel
position bound. These timestamps share the replay's starting clock. The remaining
**12.734-second first-card-to-family gap** remains a user-visible problem.

The final cold-gallery replay first observes its already complete four-original
family **9.298 seconds after opening**, confirms it at **9.632 seconds**, and
observes the complete card on warm reentry in **1.553 seconds**. All six pictures
remain distinct and the test performs zero child-continuation reads. These
unpaired emulator smoke observations do not establish phone p95, energy use or
sustained scrolling performance. Cold preparation and source-dependent proof
acquisition remain open work.

## 0.38.69: reduce literal-matching allocation

Caption matching, fusion word preparation and decorative-copy preparation now
advance call-local native matchers. The exact compiled expressions, Unicode
handling, word bounds, UTF-16 source offsets and comparison guards remain in
place. Numeric signs/operators, negation, destination conflicts, ownership,
separate pictures and distinct recordings retain their existing semantics.
No additional network request, model, dependency, permission, database migration
or persistent reader cache is introduced.

The [controlled performance comparison](FEED_PERFORMANCE.md#03869-less-allocation-during-literal-matching)
uses one candidate between two 0.38.68 controls over the same 991-post window.
Local grouping takes **4.167 seconds**, versus **4.604/4.657 seconds** in the
controls. Complete focused-family observation is **10.011 seconds after opening**,
versus **10.572/10.838 seconds**. All three first visible focused cards already
contain their complete four-original family and six distinct pictures, with zero
continuation reads. Ordered group membership and all 991 content-observation
digests match exactly. These are modest emulator gains from three observations,
not a phone latency distribution or a claim that delayed merging is solved.

Six new core methods compare the token contract against the frozen previous
Kotlin expression: all 1,114,112 Unicode codepoint values, seeded mixed input,
7,560 numeric-grammar combinations, long failed-prefix runs and simultaneous
readers. The two new native methods check the runtime-specific expressions and
the same Unicode range. Compiled patterns are shared; mutable matchers belong
to individual calls.

The shipping candidate also passes both token methods on the minimum API 26
emulator in **28.412 seconds**, then opens the native reader without a fatal
startup error. Its engineering session uses the ordinary shell. The independent
JVM reference retains the previous `findAll` implementation; the native stress
oracle reuses its own matchers to bound allocation while checking the same fixed
expressions and complete input coverage.

The full JVM run covers **2,623 cases: 2,619 passes, four optional skips and zero
failures/errors**. The frozen shipping APK's native selection covers **219 named
checks: 216 passes, three opt-in skips and zero failures**, with returned method
identities verified. Release/test assembly and lint pass; lint retains 182
warnings and one hint, with zero errors/fatals. Eight Python privacy controls and
four retention-scoring controls pass.

Ten captured native presentation/save replays cover **22 family cases and 73
original editions**. Each retains one card, one checked shared passage, its
expected distinct attachments and every saved source original. The actual
source-bound link-preview preservation check also passes. Native caption, card
and adopted-body screenshots are inspected directly; private captures remain
outside the source tree.

The final application video replay opens at **536 ms**, first shows its focused
card at **8,131 ms**, observes the three required recordings at **20,018 ms** and
shows the six-original family at **21,414 ms**. At **24,331 ms**, its adopted body
contains all four required literal variants once, while preserving the existing
two-pixel card-position bound. These timestamps share the replay's starting clock.
The remaining **13.283-second first-card-to-family gap** is a user-visible problem;
this local allocation increment does not repair source-dependent video evidence
acquisition. Sampling retains its unmetered/Wi-Fi resource policy.

A separate final cold-gallery replay shows its complete family **9.603 seconds
after opening**, and the Subscriptions → Following return shows the complete
card in **1.600 seconds**. It retains all six pictures and performs zero child
continuation reads. These unpaired smoke measurements do not replace the
controlled comparison or establish physical-phone p95, battery or sustained
scrolling performance. Cold preparation and late video proof remain open work.

## 0.38.68: preserve source-bound link previews

The public Nitter reader now retains an own post's declared link-preview image
and destination. Its title, excerpt and image description remain labelled
attachment metadata; authored title/body stay unchanged. This feeds the existing
media, matching, Context and Library consumers. Extraction performs no additional
network request and adds no model, dependency, permission or database migration.

The [bounded reader](ATTACHMENT_COLLECTIONS.md#retain-declared-link-previews-before-matching-03868)
requires one unambiguous own permalink agreeing with the selected original.
Quoted, nested, caption-inserted, competing-permalink and mismatched containers
cannot supply the preview. Native video cards keep their existing parser.
Nine core controls and two regular native methods cover these boundaries,
URL restrictions, ordinary galleries and retained authored content.

An actual production-Gateway request independently reproduces the missing preview
on unmodified official 0.38.67: one response supplies 24 posts in 2.38 seconds,
while the selected post loses its declared image. The old APK also fails the
new synthetic destination control. Repeating those controls against the candidate
passes; all existing fields, links and attachments in the same 24-post capture
are preserved, apart from the allowed observation timestamp. These are dated
source observations, not a claim about every Nitter deployment.

In a separate native source-image comparison, an article photograph and a larger
announcement graphic containing a small inset remain two distinct attachments.
This increment does not weaken image equality or merge separate publications by
topic. Connected author-thread markup is a further investigation lead; no
unqualified parent relationship is introduced here. Some related posts therefore
still remain separate cards.

A local parser-only candidate/control comparison uses 12 parses per invocation,
discards the first two, and records thread CPU separately from wall time. Official
0.38.67 before/after controls have median CPU times **56.04/61.21 ms**; the candidate
is **62.46 ms**, over the same 71,735-character, 24-post response. Corresponding
wall medians are **56.31/62.55 ms** and **64.13 ms**. This demonstrates a small
processing cost for recovering additional source material, not a speedup or
physical-phone latency measurement. Fetching, decoding and presentation are
outside this particular timing.

The full JVM run covers **2,617 cases: 2,613 passes, four optional skips and zero
failures/errors**. The frozen shipping-release native selection covers **217 named
checks: 214 passes, three opt-in skips and zero failures**, with returned method
identities verified. Release/test assembly and lint pass; lint retains 182 warnings
and one hint with zero errors/fatals. Eight Python privacy controls and four
retention-scoring controls pass.

The new single-original card/save replay initially fails because the engineering
helper examines only optional fused editions, overlooking the saved bookmark
root. The production save succeeds. The helper now follows the single-post or
fused save path as appropriate and verifies the persisted root plus every distinct
captured edition. The failed report is retained rather than attributed to a
production saving defect.

Ten native presentation/save replays now cover **22 families and 73 originals**:
the nine existing collection packets retain one shared passage per family,
expected distinct attachments and all 72 source originals; the added single-post
packet retains its exact caption, declared image and saved original. A separate
opt-in native replay verifies the actual source capture against its prior Page.
Native caption, media and full-feed screenshots are inspected directly.

The final 991-post application replay opens at **590 ms**, shows the focused
video card at **8,728 ms**, observes the three required recordings at **22,166 ms**
and displays its six-original family at **23,646 ms**. The complete body is adopted
at **26,441 ms**; all four required literal captions appear once and the retained
card position remains within the existing two-pixel bound. These timestamps share
the replay's starting clock. The approximately 15-second first-card-to-family gap
remains a user-visible latency problem; this extraction increment does not claim
to repair it. Sampling retains the Wi-Fi-only policy on an unmetered connection.

The cached-file cold-gallery replay first shows the focused card **9,768 ms after
opening**, and its complete four-original family at **10,038 ms**, preserving six
distinct pictures and making zero continuation reads. The actual Subscriptions →
Following return shows its complete card in **1,683 ms**. These unpaired emulator
observations qualify retained behavior, not a causal speedup, instant merging or
physical-phone p95/energy performance. Cold startup and source-dependent proof
acquisition remain open work.

## 0.38.67: later coherent image capture and cheaper literal preparation

[Matching preparation](ATTACHMENT_COLLECTIONS.md#freeze-current-image-evidence-after-text-preparation-03867)
now captures current local image evidence after source-only analysis. Pictures
which finish decoding during that work can inform the same pass. Every comparison
still uses one immutable bank; changed source facts or original membership are
rejected, and later evidence waits for another pass. The matcher retains the bank
it actually used, avoiding another full comparison for a queued notification
already covered by that snapshot. No fetch, file read, decoder, admission threshold
or video-sampling policy is added at this boundary.

Deterministic core and native controls place new image evidence at this boundary,
verify collection in that first pass and verify reuse on the queued old input.
Withdrawal, source edits, changed media identities, cancellation and conflicting
or expired image aliases retain independent controls. Current video admission is
always reapplied rather than inherited from an older complete group.

Exact raw-string normalization shares the existing operation-local allowance;
normalized results never key ownership or an admission decision. Unchanged
caption tokenization is reused. String allocation estimates count references
and actual shared instances within one retained entry, while equal-but-distinct
instances remain separate. Gap normalization uses the existing runtime-aware
whitespace routine and avoids a matcher/list/join for every token gap. One
exhaustive unit method compares all 65,536 UTF-16 code units with the previous
quote/whitespace contract, including controls and lone surrogates.

The same investigation independently reproduces two correctness failures on
unmodified official 0.38.66: U+FFFF after an HTML token can hide a changed trailing
negation in collection presentation and can make a real heading disappear as an
echo of its body. The candidate preserves complete literal fields for that
unsupported representation. Native checks preserve changed negation and quantities,
reconstruct exact case-sensitive URL source paths and retain ordinary equivalent
HTML/Unicode captions. Original source fields are unchanged.

In the same API 35 pure local experiment over 991 captured posts and 25
subscriptions, independent fresh repeat grouping takes **3,557/3,449 ms**, versus
**4,348/4,438 ms** on official 0.38.66. Retained repeats take **2,608/2,592 ms**,
versus **2,985/2,938 ms**. Every pass returns the same 822 groups and exact grouping
hash. The eight-MiB estimated source allowance retains 541 rows. The separate
forced-GC ownership experiment releases approximately 3.66 MB of managed memory;
this is not an exact heap/RSS bound or a physical-phone measurement.

In a quiet actual-application candidate → control → candidate cold-gallery
comparison, first complete display takes **10,647/10,182 ms after opening** for
the candidate and **12,021 ms** for official 0.38.66. All first screens contain
one four-original card and its required root caption; all six distinct pictures
and zero publisher-continuation reads are verified. Returning through the actual
Subscriptions → Following controls takes **1,717/1,634 ms**, versus **1,631 ms**
for the control, so this run establishes no warm-navigation improvement.
Earlier prototypes failed to establish a first-card gain and were revised;
the plain-DOM shortcut and compiled mention-pattern cache were excluded. Startup
remains substantial. These short same-emulator comparisons do not establish a
phone p95 distribution, battery improvement or instant merging.

The frozen release selection covers **214 named native checks: 212 passes, two
opt-in skips and zero failures**, with returned method identities verified.
Nine additional native presentation/save replays cover **21 families and 72
originals**: one card and shared passage per family, expected distinct attachments
and every original preserved in the protected save. Inspected native screens
include short captions, gallery continuations and the six-original video family.

The final versioned cold-gallery replay first displays its complete card
**9,678 ms after opening**, retains all six pictures with zero thread reads and
returns to the complete card in **1,746 ms** through the real navigation controls.
The separate actual-video replay opens at **485 ms**, first shows the target at
**8,979 ms**, records the three required recording observations at **19,979 ms**
and displays its six-original family at **21,234 ms**. Proof-to-card processing
is **1,255 ms**. The existing captured-details disclosure adopts the complete body
at **24,198 ms**: all four required literal captions appear once and the card's
established top moves no more than two pixels. The caption screenshot is inspected
separately. Sampling respects the Wi-Fi-only policy on an unmetered connection.
These live acquisition times have no paired network control; they are dated
journey observations rather than an attributed network speedup.

The full JVM run covers **2,608 cases: 2,604 passes, four optional skips and zero
failures/errors**. Release/test assembly and lint pass with zero errors/fatals;
lint retains 182 warnings and one hint. Eight Python privacy controls and four
retention-scoring controls pass. No model, dependency, permission, database
migration or native-runtime change is introduced. Cold startup, source-dependent
acquisition and physical-phone latency/energy qualification remain open work.

## 0.38.66: reuse unchanged preparation while checking current evidence

[Source-analysis reuse](ATTACHMENT_COLLECTIONS.md#reuse-source-analysis-without-reusing-an-admission-decision-03866)
reduces repeat work during progressive media collection. Only pure source
features, nomination keys and decorative-caption preparation survive between
passes. Complete source equality, immutable subscription/membership context,
2,048 entries and an eight-MiB estimated allowance bound this memory. Current
posts are reattached and candidate, pair, gallery, ambiguity and destination
checks still run. No accepted pair or image comparison is retained here.

The completed base matcher also survives a reading-scope change when the exact
source and effective image-bank contents agree. A per-window coroutine mutex
prevents cancelled and replacement collectors from racing that base. Ad-hoc
callbacks cannot claim this reuse. Native controls cover image expiry, changed
source claims, current pixels, cancellation, independent source windows and
withdrawn video proof. Final footage admission is always reapplied.

In an API 35 local experiment over 991 captured posts and 25 subscriptions,
repeat retained passes take **2,914/2,775 ms**, versus independent fresh passes
of **4,162/4,159 ms**. Every pass returns the identical 822 groups and grouping
hash. The selected allowance retains 524 rows; smaller four-MiB retention had
retained 280. A separate forced-GC experiment, outside every timing interval,
confirms the owner is collected and approximately **3.6 MB** of managed memory
is released. This is neither an exact heap bound nor a physical-device memory
measurement. Initial fresh/retained timings are not a cold-start comparison.

Identical displayed/source reading lists are now projected once per operation.
Changed text, links and media retain separate projections. The captured local
reading stage takes **226 ms**, versus **514 ms** for the legacy duplicate
projection, with identical output and all 991 content observations retained.
No cross-operation presentation cache, reading-baseline or saved-copy policy
changes.

The actual application also replays the same cold gallery on official 0.38.65
and the candidate. Both first screens contain one complete four-original card,
its required root caption and six distinct pictures, without supplied fingerprints
or a publisher-thread read. In the candidate, first complete display is
**12,085 ms after opening**, so startup remains substantial. After work settles,
the real Subscriptions → Following journey takes **1,567 ms** to its first
complete card, versus **2,380 ms** for the control. The recorded matching job
falls from **5,736 ms to 237 ms**. These are dated same-emulator observations,
not phone p95 figures or a universal speed guarantee.

The stronger 991-post live-video replay opens at **503 ms**, first shows its
target at **9,720 ms**, completes the three required recording observations at
**22,009 ms** and displays the six-original family at **23,436 ms**. Proof-to-card
processing is **1,427 ms**. Opening the existing captured-details disclosure
adopts the complete body at **26,090 ms**: all four distinct literal captions
appear once and the established card top moves no more than two pixels. The
inspected caption screenshot shows the actual source labels and all four variants.
Network metadata and sampling times vary; this does not establish instant merging.
The run respects Wi-Fi-only automatic sampling on an unmetered connection.

Nine additional native presentation/save replays cover **21 announcement
families and 72 originals**, with zero failures. Each family renders one card
and its shared passage once, retains its expected distinct attachments and
preserves every original in its protected save. The current six-original video
case retains all four playable recordings. These replays qualify presentation
and retention separately from the cold full-feed acquisition timings above.

The full JVM run covers **2,600 cases: 2,596 passes, four optional skips and zero
failures/errors**. The shipping-release API 35 selection covers **210 named
native checks: 208 passes, two opt-in skips and zero failures**, with returned
method identities verified. Release/test assembly and lint pass with zero
errors/fatals; lint still reports 182 warnings and one hint. Eight Python privacy
controls pass. No model, dependency, permission, database migration or native
runtime is added. Source-dependent acquisition, cold full-feed startup and
physical-phone latency/energy measurements remain open work.

## 0.38.65: process current evidence before painting collected feeds

[Current matching inputs and bounded local preparation](ATTACHMENT_COLLECTIONS.md#use-current-evidence-for-the-first-useful-card-03865)
reduce delayed collection without changing admission thresholds. A combined-flow
buffer was retaining intermediate image snapshots while a coherent comparison
ran. An independent native counterexample holds that first comparison open,
publishes three changes and reproduces the obsolete next input. Conflating the
combined output now finishes the current comparison and retains only the newest
waiting state. Native controls also preserve video/gallery-only notifications,
source-epoch cancellation, base reuse and withdrawal of footage proof.

An open feed can prepare up to 24 already-cached image/preview files around its
reading anchor, within 750 ms and the existing decoder pool. Same-account
parent/children and nearby locally linked publisher galleries get priority.
Missing files and unfinished preparation return to the ordinary paced scan.
There is no network request, whole-cache startup barrier or new identity claim.
Existing matching, source, Tor, retention and saved-copy rules remain in force.

The stronger actual-application gallery replay independently fails on official
0.38.64: its four-original source count eventually appears, but the shared root
caption remains absent after the 60-second assertion window, with no card work
still running. In the candidate control, retained files inform the initial
collection and the inspected first screen contains one four-original card with
its root caption. All six distinct pictures remain in the rendered semantics
and collection, and the already acquired child requires zero thread reads.
Neither replay injects fingerprints. The baseline overlaps test compilation and
establishes a correctness failure, rather than comparative latency.

Operation-local URI and content-identity reuse shares the existing bounded
preparation workspace. All-platform literal identity, comment/fragment/origin,
destination-policy, concurrency, oversized-input and cleanup controls pass. A
quiet native replay of the same 991 captured posts takes **6,322 ms** for baseline
grouping and **5,517 ms** with reuse. Both produce exactly the same 822 groups
and 991 content observations, with identical grouping and observation hashes.
These are local operation measurements, not complete-search or phone timings.

The full JVM run covers **2,588 cases: 2,584 passes, four optional skips and zero
failures/errors**. The API 35 shipping-release selection covers **204 named
native checks: 202 passes, two opt-in skips and zero failures**, with returned
method identities verified against the selection. Release assembly and lint
pass with zero errors/fatals. There is no new model, dependency, permission,
database migration or native-runtime change.

Nine additional private native presentation/save replays cover **21 announcement
families and 72 originals**: one card/common passage, distinct attachments,
preserved originals and faithful protected saves. In the shipping 991-post
cold-gallery replay, opening occurs at **924 ms**, its first four-original card
at **14,356 ms**, and final assertions at **14,739 ms** from harness start. Its
first screenshot already contains the root caption; six rendered picture nodes
and zero thread reads are verified. This still takes **13,432 ms after opening**.

The actual-application 991-post video run opens at **485 ms**, shows its first
target card at **11,334 ms**, completes the required footage observations at
**25,279 ms**, and shows the six-original source family at **26,855 ms**. The
proof-to-card interval is **1,576 ms**; the preceding release's recorded interval
was **8,785 ms**. This is a dated unmetered emulator replay with live-source
variability, not a controlled phone benchmark. Source-count adoption and visible
body adoption remain different observations: an already visible reading body
retains its existing captured-details/idle-adoption behavior.

A separate release-guard counterexample shows that an abbreviated archive flag
could silently select a checkout scan instead of the requested source ZIP.
The privacy scanner now rejects abbreviated flags and a ZIP prefix without its
explicit archive. All eight Python controls pass. This correction has no app
runtime cost; the prior release's actual source ZIP was separately scanned.

Full-feed startup latency and source-dependent acquisition remain open work.
No physical-device p95, sustained energy measurement or instant-merging guarantee
is inferred from these emulator and local-operation checks. Clean-source stamps,
frozen-artifact comparison, corresponding-source/privacy checks, compatible
preview promotion and anonymous permanent-download verification remain release
gates.

## 0.38.64: acquire missing gallery members before publishing them

[Demand-scoped gallery acquisition](ATTACHMENT_COLLECTIONS.md#acquire-a-missing-gallery-continuation-03864)
addresses a missing-input defect, rather than lowering similarity thresholds.
An observed four-picture X edition inside a larger linked-publisher gallery can
request its author's continuation. The bounded unrolled-thread route precedes
the existing discussion fallback. Explicit parent extraction validates the
account, source ID and destination together; unrelated authors, quoted records,
disconnected chains and contradictory declarations are excluded. Each ancestor
must still fit the same frozen gallery. Ordinary post reads remain unchanged.

The new worker decodes candidate pictures and proves gallery membership before
transactional followed-post publication. Current source inputs, enabled account
bindings, memberships and purge generation are checked again at commit. No
commenter enumeration becomes a subscription. The existing Gateway, Tor policy,
foreground priority, bounded ordinary cache and protected Library copies remain
in force. No model, dependency, permission, schema migration or native-library
change is involved.

An independent native counterexample on official 0.38.63 held a video comparison
open while its card observed no work: worker keys lacked the card's `post:`
prefix. Corrected keys now cover active and queued comparisons. A queued task
also registers before waiting for the decoder lane, so purge cancels it before
it can sample in a newer generation. Positive native controls cover both cases,
uncooperative responses, subsequent successful retry and saved-record retention.
Continuation-only misses do not contaminate ordinary POST health; a 404 followed
by a working post succeeds, while an explicit 429 still pauses subsequent reads.
Unavailable selected Tor issues no request in the narrow-route control.

The full JVM run covers **2,584 cases: 2,581 passes, three optional skips and zero
failures/errors**, including the explicit private captured-input replay. The
API 35 release selection covers **197 named native checks: 195 passes, two opt-in
skips and zero failures**. Nine private native presentation/save replays cover
**21 announcement families and 72 originals**. They check one reading card and
one shared passage per family, distinct attachments, source retention and faithful
Library saves. All source images for the newly acquired-family replay were
decoded from actual cached/Gateway or supplied genuine files, with no injected
fingerprints. Native card pixels and the full-feed before/after screens were
reviewed, alongside the assertion reports.

A separate actual-application replay starts with **990 captured followed posts**,
exact memberships and three initial gallery editions. The expected child is
absent from the input. The shipping visible-card dwell obtains that actual
same-author reply; only its author's follow membership is added. The thread
read takes **2,861 ms**, both child pictures are decoded by **3,515 ms**, and
commit occurs at **3,681 ms**, measured from read start. The original card stays
in place and finishes with four originals and six distinct pictures. First-card
delivery takes **11,443 ms after opening**; the updated source count appears
**7,806 ms after commit**. This confirms acquisition/preservation and also
identifies remaining whole-window reconciliation latency.

A second actual-application replay uses the **991-post video window**, real
memberships and the Gateway sampler. From harness start, opening occurs at
**533 ms**, the first target card at **11,464 ms**, the three required recording
observations at **25,743 ms**, and the six-original family at **34,528 ms**.
These are single dated emulator observations with a different accumulated media
cache, not a controlled comparison with 0.38.63 or phone performance percentiles.
The remaining delay during changing image evidence and cold caption preparation
is explicitly open; these checks do not establish invisible or instantaneous
merging. Physical-device latency distributions, memory/energy measurements and
unlocated captionless-source cases remain outside this increment's qualification.

The matching non-debuggable APK retains its application ID and signing identity.
Clean-source assembly, lint, frozen code/resource equivalence, corresponding
source, privacy scan, native-library equality, retained preview state and the
permanent anonymous download are checked before promotion/publication.

## 0.38.63: reduce the delay before a collected card appears

[Footage projection reuse](ATTACHMENT_COLLECTIONS.md#apply-new-footage-without-repeating-caption-matching-03863)
keeps caption/image matching separate from completed video observations. The
caller-owned base is invalidated by source/membership or effective image changes.
The final footage pass retains the existing owner, time, scene and complete-link
checks. Dotted-initial candidate ordering improves the first useful comparison;
it is not an admission proof. No model, dependency, permission, migration or
native-library change is involved.

An official 0.38.62 counterexample mounted the actual application with a private
**991-post followed window**, real memberships and six target editions. Actual
Gateway sampling completed the required observations at **28.191 s**, while the
six-original collection appeared at **40.862 s**. With projection reuse alone,
those points were **27.307 s** and **28.551 s**. The final candidate's improved
ordering observed the required recordings at **23.424 s** and displayed the
collection at **25.225 s**. First-card times were **10.884 / 9.835 / 10.778 s**;
opening started at **0.534 / 0.552 / 0.575 s**, respectively. These are single,
dated API 35 emulator replays with live source variability, unmetered networking
and the default Wi-Fi-only video-download setting. They establish neither phone
percentiles nor an improvement in every acquisition or cold-opening cost.

A separate local replay measured the 991-post caption pass at **6,370 ms**. A
caller-owned first projection took **5,399 ms**, while three footage-only revisions
took **146 / 146 / 146 ms** with unchanged admitted groups. Native guards verify
image/source changes, cancellation and withdrawal of proof. The six originals
and their different captions remain inspectable; native card pixels were reviewed.

A real short-announcement miss was independently reproduced: two source editions
collected, while a third acquired a mention-navigation URL that was treated as a
competing article after reference detachment. The existing collection rule now
classifies that navigation against the original text. A full private replay
checks raw records without waiting for picture observations. Synthetic controls
retain distinctions for changed claims/questions/numbers, in-sentence names,
literal destinations, unrelated hosts and query-bearing URLs. The optional
packet presenter was also corrected to exercise the shipped collected-edition
composer before its older fallback paths.

The full JVM run has **2,576 cases: 2,573 passes, zero failures/errors and three
optional skips**, with the explicit private captured-input case enabled.
The API 35 release selection covers **191 named native checks: 189 passes,
zero failures and two opt-in skips**, with returned method identities checked
against the selection. It retains feed position, late-content adoption, refresh,
Library, media, source, worker and connection-policy controls. Separate opt-in
full-window and image-snapshot-cache runs pass. Eight native fixture replays
cover **20 captured families and 68 originals**, with one card/common passage
per family, distinct attachments and faithful protected saves. This includes
two galleries retaining six/five pictures, the newly repaired three-edition
short announcement, and six video editions with all four selectable recordings.
The full-window latency replay is separate from those small-family checks.

Release packaging compares the final application and release-test APKs against
the frozen qualified code, resources and assets, excluding META-INF source-stamp
metadata. The source archive is checked against the clean tagged commit and
unchanged upstream archives. Preview replacement retains a fresh private backup
and checks retained-file hashes before first launch; the permanent fixed-name
APK is verified with a full anonymous download.

Two related reports remain distinct: a missing quotation-panel self-reply was
recovered through the production comments route, but was absent from the retained
feed capture. Existing gallery replay accepts it when acquired; this increment
does not add automatic thread collection. A reported captionless article-image
edition was not independently located in that capture or its root discussion.
Neither is declared fixed by this performance change.

An initial debug-test/release-target ABI mismatch and an invalid input containing
unfollowed cache rows were harness failures. The corrected release-compatible
replay uses the actual followed query; the invalid transaction committed no rows.

## 0.38.62: adopt captured details on the current card

[Captured-detail disclosure](ATTACHMENT_COLLECTIONS.md#captured-details-without-a-feed-jump-03862)
reuses the fixed card-status area. New details distinguishes already captured
wording/media from background Updating work and idle Ready. Show details updates
one admitted card in place; Later leaves its frozen body untouched. Source-only
attribution changes retain their controls without a new-material offer. Existing
off-screen adoption and Newest behavior remain available.

The action projects only that card on the worker and rechecks its row/window/group
generation after waiting for idle scrolling. It retains the key, chronological
position, pixel anchor and stopping baseline, and restarts read dwell for the
newly displayed body. It starts no discovery cycle or model task; normal Gateway
media loading still applies to newly displayed uncached references. There is no
provider, permission, dependency, database migration or native-library change.
Admission, privacy, source budgets and protected snapshots retain their contracts.

A settled native counterexample against 0.38.61 shows Ready while a captured extra
photograph remains outside the displayed body. An earlier attempt asserted during
transient Updating; the corrected wait reproduces the settled Ready state before
any production change. Three new native controls qualify Later/one-card adoption
beside a newer prefix, a source-only copy of an existing caption variant, and a
caption revision superseded again while its disclosure is open. The final action
shows the newest revision, with neither superseded wording nor repeated common
text. Pixel-anchor, unread-before-adoption, source-action and faithful-save
assertions are retained. Native disclosure, adopted-card and quiet-attribution
screens were inspected.

The fresh complete JVM run covers **2,573 cases: 2,569 passes, zero failures/errors
and four optional skips**. The API 35 release selection covers **189 named native
checks: 187 passes, zero failures and two opt-in skips**, with returned method
identities checked against the exact selection. Existing following/combined feed,
late-context/image, reading-position, refresh, Library, worker, resource-admission
and connection-policy controls remain included. An external runner setup error
stopped before instrumentation; its corrected named run is the recorded batch.

Seven separate native replays pass **19 captured families and 65 originals**:
sixteen older families/49 originals, two galleries with all ten originals and
six/five distinct pictures, and a six-original/five-platform recording family.
The recording family retains one card, four selectable recordings, each caption
form and the extra supplied title. Common wording appears once and protected
saves retain the original fields. Captured inputs remain private. One mixed
cached/live API 35 replay measured 1,267 ms to its first card and 15,805 ms to
collection; these are single emulator observations, not phone percentiles or a
performance comparison with previous runs.

Investigation of an adjacent headline/caption pair identified a separate link-only
share with a truncated publisher heading and additional information. A specific
resource/announcement relationship remains unqualified; no topic-only matcher was
added. Complete duplicate coverage, general endpoint resilience, physical-phone
p95 and energy use remain unmeasured. Source/artifact integrity, preview backup
and fixed-name publication follow the existing publishing procedure.

## 0.38.61: compact attribution, literal fields and resource-aware planning

[Repeated literal source names](ATTACHMENT_COLLECTIONS.md#repeated-labels-and-literal-field-formatting-03861)
now take one display label while retaining each distinct account and original.
Shared-name labels open the complete originals chooser; unambiguous names retain
their direct profile shortcut. Platform marks retain explicit accessible names.
Field-aware presentation reads capitalization-only prose variants once while
keeping URLs, address/identifier tokens, numeric units, paths, assignments and
code spans exact. Original posts, exports and protected saves retain raw fields.
These display operations supply no new account-ownership or pair-admission proof.

[Automatic video resource admission](VIDEO_FOOTAGE.md#resource-admission-before-demand-planning-03861)
now precedes focused lookup and is rechecked after the acquisition lane wait.
The Wi-Fi-only, power-saver, severe-thermal and low-memory thresholds are unchanged.
Cached matching and deliberate inspection retain their existing behavior. No
provider, model, dependency, permission, native binary or database migration is
added; Gateway, Tor, scene/byte limits, concurrency and cancellation remain intact.

Independent baseline counterexamples showed three repeated labels for distinct
accounts and two focused lookups while Android's actual power saver already
prevented sampling. Two JVM counterexamples exposed prose-case/title composition
and its URL boundary. The new regressions keep unrelated identities and protected
literal fields distinct. The power control records zero blocked planning calls,
two deliberate samples while held and two fresh automatic samples after release
and explicit retry. This is a synthetic scheduling control, not a network or
energy benchmark. Android's asynchronous battery/configuration transitions
required a wait for unplugging and remounting the synthetic host on two observed
activity recreations; no production activity flags changed.

The complete JVM run covers **2,573 cases: 2,569 passes, zero failures/errors and
four optional skips**. The API 35 release selection covers **186 named native
checks: 184 passes, zero failures and two opt-in skips**. Returned method identities
are checked against the full selection. An initial native run had seven failed
platform-text assertions after visual labels changed to marks. Explicit accessible
platform descriptions and corresponding provenance assertions restore that
contract; the complete corrected selection passes. Source chooser, direct-profile,
late-image/context, reading-position, refresh, Library, worker and connection
controls remain included. Native source-control screens were inspected.

Seven additional native runs replay **19 captured announcement families and 65
original editions**: the preceding sixteen families/49 originals, two galleries
with all ten originals and six/five distinct pictures, and a six-original,
five-platform video family. Every replay retains one row per family, one common
passage, expected distinct attachments and faithful protected saves. The video
card retains four selectable recordings, each distinct caption, and the extra
supplied title; its common body appears once. Native gallery and video screens
were inspected. Unmodified captured posts and actual Gateway scene observations
remain private. One mixed cached/live API 35 replay measured 1,209 ms to its first
visible card and 19,287 ms to collection. These are single emulator observations,
not physical-phone, cold-source or percentile measurements.

The preceding two-scene blob response and URL without usable bounded ranges
remain insufficient evidence, not successful footage comparisons. Physical-phone
p95/energy, general source availability and complete duplicate elimination remain
unqualified. Artifact correspondence, preview-state backup and fixed-download
verification follow the existing publishing procedure.

## 0.38.60: focused video comparisons and complete source fields

[Visible-card planning](VIDEO_FOOTAGE.md#visible-card-neighborhoods-and-source-fields-03860)
now reaches useful counterparts outside the cheap global shortlist, under the
same publisher/date scope, candidate cap, pair allowance and single decoding
lane. [Compact source aliases](ATTACHMENT_COLLECTIONS.md#compact-extraction-aliases-and-source-fields-03860)
fold before cross-platform ambiguity checks. A short shared body can retain its
extra supplied title without repeating the body. Editorial titles, changed
claims, questions, destinations and competing headings retain their rejection
controls; long bodies keep the established multi-platform anchor requirement.
No model, provider, permission, dependency, native binary or database migration
is added.

The full JVM run covers **2,566 cases: 2,562 passes, zero failures/errors and four
optional skips**. The API 35 release selection covers **176 named native checks:
174 passes, zero failures and two opt-in skips**. Named results are checked against
the complete selection. New native controls exercise focused lookup without a
global offer, catalog changes during the second recording, overlay cancellation
and reentry, renewed peer inputs, and purge followed by explicit retry. Initial
harness attempts failed activity-content setup and test-clock advancement; the
corrected harness passes against the unchanged production candidate.

A current six-original, five-platform recording family first reproduced as three
collections on the preceding release, despite successful samples for three
recordings. The corrected native replay starts from unmodified source posts,
obtains real Gateway scene observations, and displays one card with all six
originals and four selectable recordings. Both shared caption bodies appear
once, the distinct supplied title remains attributed, and a protected save keeps
all original text/media fields. Initial and caption screens were inspected.
One mixed cached/live API 35 observation was 930 ms to the first visible card and
34,359 ms to collection, including a background read yielding to foreground
content and resuming. These are single emulator observations, not cold-source,
physical-phone or percentile measurements.

Five additional native runs pass the preceding **16 captured announcement
families and 49 originals**. A further gallery run retains one card per family,
all ten originals and the six/five distinct pictures, including the previously
missing panels. These checks retain the one-passage, expected-attachment and
protected-save assertions. They are separate from the 176 named synthetic
selection. Together with the current video family, the captured sweep covers
19 families and 65 originals; this is not a claim about every post in the feed.

A separate replay against the larger retained corpus confirms the global-plan
omission and focused eligibility without additional acquisition. Replaying the
three actual observations makes all six focused plans return no further work.
That establishes demand scheduling and its stopping condition, not complete
live acquisition or end-to-end performance of the corpus.

The strict existing blob route became readable under its observation-only
policy, but its probe recovered only two scenes within four MiB/sixteen requests.
It therefore supplied no shared-footage proof. Another retained URL did not offer
usable bounded ranges. Neither response is counted as a successful comparison;
no scene threshold was lowered. Ordinary feed refresh already renews the signed
video URL, so no redundant refresh subsystem was introduced. Physical-phone p95,
energy use, arbitrary duplicate coverage and general source availability remain
unmeasured. Artifact and preview-state checks follow the publishing procedure.

## 0.38.59: gallery replies and retained video acquisition

[Gallery self-replies](ATTACHMENT_COLLECTIONS.md#gallery-self-replies-03859)
now use explicit proven parent membership and existing literal caption/detail
checks. Fuller introductions retain their source text without the former extra
length cutoff. [Video acquisition](VIDEO_FOOTAGE.md#retained-input-and-failed-peer-scheduling-03859)
keeps supplied duration/rendition fields, lets qualified older X clips request
small exact-post metadata reads, and prevents a cooling failed peer from hiding
another usable comparison. No model, dependency, permission, native binary,
provider or database migration is added. Existing gateway, Tor, resource,
concurrency and sampling bounds remain in force.

The full JVM run covers **2,551 cases: 2,547 passes, zero failures/errors and four
optional skips**. The API 35 release selection covers **172 named native checks:
170 passes, zero failures and two opt-in skips**. The exact named selection is
checked against returned results. New native controls cover metadata-before-
sampling, reuse after restart, foreign-clip rejection, purge during an
uncooperative metadata callback, changed acquisition inputs and failed-peer
eligibility/retry. Existing feed, Library, refresh, reading-position, playback,
worker and connection-policy controls remain in that selection.

Five additional private native runs pass the preceding **16 announcement
families and 49 originals**, checking one shared passage, expected distinct
attachments, original retention and protected saves. These runs are separate
from the named synthetic release selection.

Two captured gallery families first reproduced as two and three collections.
Their native replay now retains one card per family, all ten originals, six and
five distinct pictures, and faithful protected saves. Input files contain raw
posts and real pictures, with no injected fingerprints. The pictures are decoded
before presentation, so this is not a cold live-image benchmark. An initial
successful content/save run failed a screenshot locator whose three-word prefix
occurred legitimately in both an introduction and heading; the corrected unique,
card-scoped locator keeps the complete-passage assertion.

The earlier positive video fixture had a separately acquired rendition hint
missing from the ordinary retained input. Replaying that input exposed both the
missing nomination and a caption-group representative that hid its usable X
edition. An untouched four-platform native replay now uses the existing idle-card
lane to obtain real metadata and bounded scene samples, forms one card, retains
the two distinct captions and three recordings, and saves all four originals.
One API 35 mixed cached/live observation was 1,051 ms to first card and 7,940 ms
to collection. These are single emulator observations, not phone percentiles.
The same target is nominated within the existing twelve-pair allowance when
planning against the complete 911-record retained corpus; this is a local plan
observation, not complete live acquisition of that corpus.

A larger retained video replay remains incomplete: an additional older signed
video URL failed its real range read, and another text-only source alias lacked
sufficient collection evidence. Its failed read also exposed the scheduling gap
now covered by the native failed-peer control. Those additional records are not
counted as a successful complete family. Failed attempts and real captures stay
private. Physical-phone p95, energy use and general endpoint availability remain
unmeasured. Clean-build, artifact, preview-state and publication checks follow
the existing publishing procedure.

## 0.38.58: cached connections and source-bound RSS sharing

[Cached account discovery](CACHED_ACCOUNT_DISCOVERY.md) describes the bounded
subscription-cache seed, staged linked-account observations, source identity and
cleanup admission. Existing RSS routes now retain explicit repost roles and
exact legacy rendering-URL GUIDs. The generated-heading presentation also handles
a hostname truncated before its TLD only when the complete destination is
supplied in that response. Original records remain intact. No source instance,
model, dependency, permission, native binary or database migration is added.

The full JVM run covers **2,537 cases: 2,533 passes, zero failures/errors and four
optional skips**. Synthetic controls cover both GUID forms, ambiguous/foreign
identities, literal authored repost phrases, picture-only records, source
snapshots, truncated-host destinations and late cached bridge observations.

The API 35 release selection covers **167 named native checks: 165 passes,
zero failures and two opt-in skips**. Named results are checked against the
complete requested selection. This includes existing feed/Library, caption/media
fusion, reading-position, pull-refresh, cache/video and production-worker checks,
plus related-account cancellation, method order and refresh retention.

A native counterexample first reproduced an empty initial deck despite a stored
subscription repost. Its bounded cache seed and linked-platform streaming checks
now pass with zero network starts. A cache stress case then exposed an actual
asynchronous reader exception from a Gson-decoded `{}` record. The corrected
per-record feed/bookmark admission passes while retaining protected raw bytes.
Native expiry, actual-payload-size, exact enabled ownership and membership-first
query-plan assertions pass. A real cleanup held at its media lock cannot revive
an old discovery session or erase the fixture's saved record/subscription.

The focused eight-case native batch passes; synthetic initial, linked-platform
and saved-reader screens were inspected. An early modal screenshot was blank
before its compositor frame, so the screenshot helper now settles that animation;
product assertions were retained. One final cached fixture observation was
5 ms to construct its session and 177 ms to its first native card, with no network
requests. These are single emulator observations, not phone/cold-network or
percentile measurements.

Two dated native RSS observations returned 20 and 18 dated roots, preserving all
11 and nine explicit sharing roles respectively. Before the correction, two
earlier 20-root windows each supplied 12 repost headings and zero typed roles.
The windows changed between reads; all tracked content fields of the 18 and
12 overlapping originals remained unchanged. Their raw responses contained the
same 20 and 18 items, and both pages remain limited timelines. A first corrected
probe exposed the truncated-host edge case before the final successful read.
These are existing-route observations, not new independent-provider coverage.

Five private native packet runs pass **16 announcement families and 49 originals**,
checking one common passage, unique attachments and protected source retention.
The separate automatic five-platform video replay passes with real bounded
Gateway reads, one card, five originals, three distinct captions and four
selectable recordings. Actual card/first-caption pixels and the complete native
text projection were inspected. Its single mixed cached/live observation was
1,314 ms to first card and 21,042 ms to collection. This does not establish that
every device/cache/scheduling state nominates the same editions; phone reports
and further live-state qualification remain separate work.

Private captures and failed attempts remain in the external evidence area.
Compatible signer/source checks, clean release lint, frozen-artifact comparison,
backed-up preview promotion and anonymous permanent-download verification follow
the [publishing procedure](PUBLISHING.md). Physical-phone p95, battery use and
global endpoint availability have not been measured by this increment.

## 0.38.57: recovered fifth video edition and extensionless recordings

The [video-scene follow-up](VIDEO_FOOTAGE.md#follow-up-qualification-03857)
records the acquisition and comparison boundaries. Existing TikTok routes now
canonicalize request-handle casing and preserve supplied clip runtimes. A bounded
MP4/WebM prefix reader lets already-nominated extensionless videos reach the
existing sampler, using its initial range block without another request.
Known adaptive aliases are excluded from smaller progressive-rendition selection.
Playback URLs, source ownership checks, chronological coverage semantics,
selected-provider/Tor policy and all sampling budgets remain intact.

The full JVM run covers **2,530 cases: 2,526 passes, zero failures/errors and four
optional skips**. New synthetic controls cover prefix truncation/oversized boxes,
image/audio containers, WebM declarations, a bounded Gateway playback probe,
runtime field scope/units, ambiguous creator scopes, canonical request paths,
unknown-video nomination and adaptive rendition rejection.

The API 35 native release selection covers **146 named checks: 145 passes,
zero failures and one opt-in skip**. An initial selector omitted eleven intended
methods; the remaining eleven passed separately, and the combined named coverage
matches the preceding release selection. No omitted method is counted as passed.

The automatic private five-platform replay passes its native card, caption,
attachment and protected-save assertions starting without scene observations.
It preserves five originals, three distinct captions and four selectable video
editions in one card. Real bounded reads supply the comparison evidence; no
fake samples or full recordings are injected. Native top/caption screens were
inspected. Early harness failures and their corrections are described in the
video contract and remain in external private evidence.

Five further native packet runs cover **16 captured announcement families and
49 originals**, with one displayed common passage per family and complete
attachment/source-save preservation. Representative native cards were inspected.
On a dedicated API 26 emulator, all six cache/recovery checks pass. Its first
extensionless-recording probe timed out during startup; a subsequent warm probe
returned four actual scenes in 8,626 ms using 3,670,016 bytes and fourteen range
requests. The initial timeout remains a failed acquisition observation even
though the diagnostic method itself completed. This does not qualify cold-start
reliability or physical-phone performance.

One normal production-pool observation returned ten dated TikTok entries through
an existing RSS method. An exact-post route retained its supplied 145-second
runtime. A creator embed still returned HTTP 429, and challenged feed-API
responses remained failures. These observations establish useful existing-route
recovery, not universal access, quota evasion or newly independent providers.
The change adds no model, permission, native dependency or database migration.

## 0.38.56: shared footage with different captions

[The video-scene contract](VIDEO_FOOTAGE.md) describes the bounded sampler,
ordinary cache, worker authority and dated observations. Different captions can
join through existing local publisher identity and three compatible, temporally
aligned scene observations. Dotted prose now survives URL classification;
formatting-only dotted/spaced caption copies share a verbatim displayed version.
Distinct source captions, discussions and playable recordings remain retained.

The full JVM run covers **2,519 cases: 2,515 passes, zero failures/errors and four
optional skips**. It includes range refusal, byte/request accounting, validator
and source association, crop and sequence controls, literal caption preservation,
bounded request planning and a synthetic fifth-platform abbreviation. That
synthetic case does not establish acquisition of the reported fifth edition.

The final API 35 native selection covers **146 named checks: 145 passes,
zero failures and one opt-in skip** across feed collections, image identity,
reading memory, Library/migrations, cache recovery, actual video controls and production descriptor/image/PDF/article
worker checks.
Six new cache controls cover reuse/restart, corruption/expiry, purge races,
source association, transient recovery and playback priority. Cache cleanup
retains protected saves, and an obsolete callback cannot repopulate its generation.

A private automatic replay starts without scene observations. Existing cached
posts/previews become readable while bounded live recording reads run behind the
visible card. The settled card contains four source originals, one copy of each
distinct caption and three selectable recordings; saving preserves every source.
Initial and settled native screens were inspected. One emulator observation was
679 ms to first visible card and 6,897 ms to collection formation. It is not a cold-feed,
phone, percentile or battery benchmark. The fifth source was not acquired.

The preceding supplied-sample foundation passed its separate native replay.
Earlier automatic replay failures exposed screenshot assumptions about retained
row anchors and multi-paragraph captions. The corresponding content/save reports
passed, but those attempts are not counted as passed instrumentation. The runner
now requires a JUnit success, not just the shell command's exit status. The corrected runs pass five native
packet invocations covering **16 announcement families and 49 originals**, plus
the separate automatic four-original video replay. Card captures were inspected.
An expanded worker selection initially included two debug-only probe/stall checks;
the release worker refused them as designed. They are excluded from the final
146-check release selection rather than counted as passing checks.

A dedicated API 26 emulator also passes all six cache/recovery checks and a real
bounded range/descriptor read: four scenes, 1,835,008 bytes, seven requests.
These are stock-framework engineering checks, not a privileged user feature.
Release lint passes. Artifact/source integrity, compatible preview promotion and
the permanent download follow the [publishing procedure](PUBLISHING.md).

The change adds no model, permission, database migration or native dependency.
Sampling runs only for a settled visible card, respects Wi-Fi-only video and
resource controls, yields to foreground traffic and cancels on scrolling,
backgrounding, overlays or playback. Unsupported ranges or insufficient evidence
leave editions separate. Android's media retriever uses a dedicated ordinary UID
worker, not a permissionless sandbox; the descriptor protocol and returned data
are bounded. No full-file or equal-audio claim follows from matching scenes.

## 0.38.55 public-timeline sharing attribution

A native source audit reproduced missing repost attribution: an admitted HTML
profile contained nine explicit repost headers but none survived extraction.
The reader now binds those plain names to the requested profile's own linked
name/username, then retains a separate sharing role. A fresh routed native read
returned 19 posts and retained all nine supplied headers. This is a dated
single-source observation, not global availability or historical coverage.

Original authors, canonical post URLs, captions, timestamps, attachments and
links remain unchanged. Existing single, fused and saved readers render the
sharing label outside the caption. Snapshot serialization retains it even when
the current representation uses another source. Passive adjacent discovery
points from the sharing account to the original author, without asserting common
control. [The connection contract](ADJACENT_DISCOVERY.md#reposts-retained-from-public-timelines-03855)
links the public renderer definitions used to qualify this independent parser.

The full JVM selection covers **2,485 cases: 2,482 passes, zero failures/errors
and three optional skips**. Six synthetic controls exercise source/profile
binding, cursors, nested and conflicting headers, unchanged source fields,
meaningful titles, snapshot retention and discovery direction. Release lint
passes. The change adds no acquisition request, model, native library,
dependency, permission, database migration or matching-threshold adjustment.

Native qualification covers the existing feed, collection, image identity,
reading memory, Library and migration selection, plus a new fused/saved-reader
control. The new control checks one displayed caption, sharing attribution,
original authorship and complete saved source records with networking paused.
Initial assertions were corrected for the existing native name substitution and
the test API's explicit substring option; production presentation and matching
rules were not loosened to satisfy them. External evidence retains both failed
fixture attempts and their corrected run separately.

The candidate also repeats **16 captured announcement families and 49 originals**
through native scrolling and protected saves. Each checked case retains one
reading row and one checked common passage, its expected distinct media and all
captured source text/media. Native screens were inspected. The private replay
harness now aligns an oversized card's beginning explicitly before its dedicated
screenshot; an already-intersecting final picture was not sufficient evidence
of the caption's appearance. Real source captures remain outside public source.

This increment has no new physical-phone latency or battery measurements and
makes no end-to-end speed claim. Cached posts lacking the declaration need an
ordinary fresh source read; the app does not infer a missing repost from a feed
membership alone. Substantially different captions on independently encoded
videos remain a separate media-identity investigation.

## 0.38.54 image-evidence preparation and scoped matching

This increment reduces repeated local comparison work behind the existing routed
reader. Darwin remains the donor. Whole-feed matching now projects relevant
image evidence before deciding to run again, and one pass uses the same frozen
observations for its captured fields and comparison callback. Bounded reference
copies precede worker-side resolution outside the image-store monitor. Existing
source/membership cancellation, latest-value conflation and full-group admission
remain in force.

Parsed frame records share the existing estimated 8 MiB operation workspace with
text and URLs, with a 512-entry frame cap. Exact strings and detailed/coarse modes
remain distinct. Matching thresholds, contradiction checks, recording identity,
field/source provenance and protected saves retain their existing rules. The
change adds no model, dependency, native library, permission, database migration
or acquisition request.

The JVM run covers **2,479 cases: 2,476 passes, zero failures/errors and three
optional skips**. New controls compare complete coarse/detailed/malformed outcomes
with uncached reads, exercise nested and failed-operation cleanup, and enforce
the shared byte allowance. The private business/cross-post packets and optional
pinned embedding check retain their explicit opt-in skips. Release lint passes.

The final API 35 native selection covers **131 named checks: 130 passes, zero
failures and one skipped opt-in private replay**. New controls check unrelated
images, unchanged renewed observations, relevant changes, alias conflicts,
eviction, expiry and clearing. A running snapshot retains both its captured
fields and comparison result when the live bank changes. Detailed-only artwork
still reaches attachment comparison without becoming general post identity.

An initial new fixture expected a heavily edited picture to retain a strong
visual fingerprint after eviction. The existing opacity/detail/color guards
correctly retained attachment-only evidence. The expectation was corrected to
check the exact remaining attachment observation and absent post-identity field;
production thresholds were unchanged. The initial 129-pass/one-failure/one-skip
run, corrected 130-pass batch, final three-control check and final complete
131-check batch remain separately recorded in external evidence.

The final candidate also repeats the preceding **16 captured announcement
families and 49 originals** in the actual native feed and Library. Every case
keeps one reading card, one checked shared passage, its distinct attachments and
the full captured source text/media in saved originals. Actual screens were
inspected. Private inputs and images remain outside corresponding source.

Three serial process-restarted local CPU samples per version use the same
765-post pool and actual prior decoded observations. All complete ordered-group
and content-observation digests match across six runs, preserving 617 groups.
Median grouping is 4.414 seconds on 0.38.53 and 4.207 seconds on the candidate;
sample ranges overlap. The [performance record](FEED_PERFORMANCE.md#03854-scoped-image-updates-and-reusable-comparison-records)
describes the workload and its limits. These component measurements do not
establish physical-phone, end-to-end latency or battery improvements.

## 0.38.53 announcement collections and complete source presentation

This increment extends the existing detectors and shared collection plan. It
uses cached publisher bindings and already acquired text/media evidence; no
model, new acquisition route, dependency, permission or database migration is
introduced. Darwin remains the donor. The preceding 0.38.52 search and chain
improvements are retained in this release.

### Reproduced behavior and correction

Three reported announcement families were replayed against the unchanged signed
0.38.52 APK in the real native feed. Each occupied two rows and repeated the
checked common passage. The causes differed: a trailing account reference, a
short live-broadcast introduction, and a delayed short edition with formatting
differences. Their local account bindings were present; another identity database
or a website lookup per comparison was not the missing component.

Frozen multi-platform anchors, complete title/body relationships and typed
broadcast identity now supplement the existing routes. Small internal wording
edits retain strict context/claim guards. Detailed attachment observations can
corroborate qualified literal name variants without becoming general account
identity or exact-file validation. A whole shared resource announcement can
retain different short introductions, while a resource footer alone cannot
collapse a changed appointment or correction. See
[collection contracts](ATTACHMENT_COLLECTIONS.md#collected-announcement-editions-03853).

The already admitted presentation reads common passages once, attributes
differences, and preserves a complete literal path for every original key.
Reference URLs remain in originals and Links rather than generating paragraphs
of tracking-code clutter. Ordinary successful composition remains preferred.
The qualified name route also repairs a fallback that otherwise repeated the
surrounding paragraph.

Native replay expanded to **16 captured announcement families and 49 originals**.
Every family ends with one reading row and one occurrence of its checked shared
passage. The checks use actual locally decoded source files, the real
MainActivity/chronological reader, continuous scrolling, individual card
screenshots and Library saves. Saved originals are compared against the captured
source text and media fields, independently of local fingerprint/metadata
enrichment. The three initial cases retain respectively one
shared graphic, two distinct pictures, and three selectable playable recordings.
Other replays cover source aliases, small wording edits, title additions,
proper-name references, different video framing and gallery membership.
This is coverage of those captures, not a universal claim about all posts.

The save pass independently exposed a Facebook extraction-alias rejection.
Portable same-instant, distinctive complete-caption copy evidence now preserves
those captured originals without inferring common account control. The media
inventory remains independent of text compression; a matching poster still does
not establish equal recordings. Private captures, account bindings, images and
reports are excluded from source and release assets.

### Regression gates and measurements

The full JVM run has **2,476 cases: 2,473 passes, zero failures/errors and three
optional skips**. The private business packet, private cross-post packet and
optional pinned embedding check are skipped without their explicit inputs.
Release lint passes. A native API 35 batch covers **128 named checks: 127 passes,
zero failures and one skipped opt-in private replay**. Separate opted-in captured
replays exercise the 16 families above.

Rejected candidates are retained in the private evidence. An early compositor
preference changed established formatting and admitted rejected factual edits;
the original path now runs first, and the new fallback reuses factual,
destination, context and endpoint guards. Its ten full-suite failures were
corrected before qualification. Two native shared-text rendering regressions
were corrected and passed both focused rechecks and the complete 128-check
batch. A new changed-weekday/shared-footer control failed before its targeted
introduction guard and passes afterward. None of those rejected candidates was
published.

A desktop JVM component comparison used the same 765 cached posts, five warmups
and nine measured grouping passes per version, without image downloads or
synthetic pixel matches. Median grouping was **795.6 ms in 0.38.52 and 797.1 ms
in the candidate**. Collections changed from 633 to 625; every original key and
input payload was retained. This measures warm text/source grouping, excludes
network and UI work, and is not a physical-phone latency, p95, memory or battery
measurement. The native reader and save checks provide separate functional
evidence.

## 0.38.52 chain labels, coffee recall and prepared local matching

The preceding speed measurements covered reconciliation, not the remaining local
matching work. New opt-in Android component measurements against the unchanged
signed v0.38.51 (`1a835e5`) identified seconds of repeated caption processing in
an accumulated cache. This pass changes native Kotlin matching and source-query
construction. It adds no model, dependency, permission, network route, background
poller, database migration or persistent document cache. Darwin remains the donor.

### Independently reproduced recall defects

A labelled fixture contains cafés with names omitting “coffee”, coffee retailers
and an unrelated shop. The old shared place vocabulary associated `coffee` with
retailers alone. Overpass could retrieve cafés through its existing interest path,
but final business matching rejected them. A focused JVM control failed. In the
large unchanged-APK native fixture, all **200 labelled cafés were missing**. The
actual old Search-tab journey also failed exact membership: its completed deck
showed the retailer and omitted the café. Both old and candidate native screens
were inspected. The corrected journey shows both; quoted/exclusion controls and
saved-Library byte-preservation checks pass.

The reader mapping now retains cafés and retailers. Generated spelling candidates
keep their actual vocabulary entries, rather than returning a generated form that
the conservative stemmer leaves unresolved. This repairs `coffees` without changing
the stemmer or discarding the literal query. Exact known forms also reach the
shared concept/FTS alternatives. Fuzzy business suggestions are not imported into
general phrase expansion. An early unshipped candidate failed the existing
`space` → `spice shop` protection; that candidate was rejected and the original
guard remains. Quotes, protected identifiers and exclusions retain their controls.

A separate source-construction regression reproduced an equivalent café predicate
appearing twice inside one bounded Overpass request. Typed and legacy predicates
now avoid that repetition when they are equivalent; broader legacy predicates
remain. This does not imply that the old code issued two HTTP requests or prove
a measured server-side speedup. Regional bounds, output caps and Gateway policy
are unchanged.

### Chain grouping across source representations

The current live Taco search returned 15 locations of the requested chain in one
reading card, before this additional correction. That dated observation does not
establish which version or record representation produced the reported flood.
A broader deterministic control independently failed for numbered storefront
names and structured food categories. The grouping label now recognizes an
explicit trailing store number; exact Schema.org food types participate in the
existing compatible-category check. This is shared code, with controls covering
Taco Bell, Burger King, McDonald’s and Starbucks, rather than query-specific rules.

Original names, addresses, phones, hours and source evidence remain in distinct
branch records. Geography and conflicting brand identities still constrain the
catalogue; other storefront formats and incompatible categories stay separate.
The native Taco journey includes numbered names and structured categories, then
checks the same card as website/name evidence streams in. A subsequent live run
actually reproduced a split: it began with one chain card, then a directory
appended a branch street to the business name and published a generic brand
descriptor, producing a second card. That candidate was held. Its captured records
remain private, with a replay checking the complete chain membership and facts.

The final label path admits that suffix only when the entire suffix matches the
record's own normalized street. A trailing generic brand descriptor can match an
otherwise exact scoped catalogue alias. Different streets, trailing unrelated
words, another storefront format and explicit conflicting IDs remain separate.
Four-chain JVM controls and the native late-arrival journey preserve the original
card identity and every branch record. The opt-in live check records bounded
progress counts, rather than only the final deck.

That replay also exposed a directory hostname vetoing the brand bucket after
repeating the branch address. The reading projection now distinguishes that
directory claim from publisher-bound site evidence. Explicit brand fields can
qualify a generic schema branch name. Genuine competing official sites, scoped
brand conflicts and unrelated businesses remain covered by negative controls.
A known storefront format keeps priority over its parent's declared brand. The
captured-record replay passes, and the subsequent cold/warm live checks retain
one chain card at every recorded populated stage, including later discoveries.
The native fixture ends with 26 locations in one card and its independent venue
still separate. These observations qualify the exercised records and routes;
they are not a universal claim about every chain or publisher.

### Work moved out of repeated record evaluations

`LocalDiscoveryFilter.prepare` freezes the bounded clause alternatives for an event
intent or saved-event Library read. Each evaluated record owns its bounded text
views; alternative clauses reuse those views without retaining earlier records.
Business category matching defers long description/evidence projections until
needed. Fixed lexical, age, admission and publisher patterns are reused.

An event intent with no content constraints returns its text decision immediately;
date/geographic/saved-live admission still runs separately. Explicit incompatible
age fields can reject a record before scanning its long caption. The existing
unknown-range behavior, admission facts and explanation strings remain covered.
`LocalDiscoveryPreparedTest` covers concepts, exact/negative phrases, protected
identifiers, existing text bounds, changed documents and concurrent query reuse.
`LocalEventIntentTest` covers explicit/unknown ages and reason preservation.
The [search implementation guide](SEARCH_INTERPRETATION.md#prepared-local-matching-03852)
links the architectural references and distinguishes local lazy views from shared
query state. This is project code, not a bundled Lucene search runtime.

### Dated component measurements

The API 35 emulator evaluated the same synthetic **1,200 businesses and 2,500
events**, with long descriptions and independently labelled result identities.
Each operation has three samples. No build or other test ran during the timed
matching comparisons. The table reports medians, including the first sample;
small samples are not a p95 or a statistical performance distribution.
Database reads, reconciliation, source fetching and UI drawing are excluded.

| Matching operation | v0.38.51 median | Candidate median |
| --- | ---: | ---: |
| Business cuisine | 1,767.0 ms | 687.0 ms |
| Business burger category | 1,464.5 ms | 669.9 ms |
| Business makerspace category | 1,569.7 ms | 645.0 ms |
| Business repair category | 1,606.0 ms | 630.8 ms |
| Business quoted name | 2,275.3 ms | 625.8 ms |
| Event empty text intent | 2,190.1 ms | 1.2 ms |
| Event compound subject | 4,020.7 ms | 2,484.1 ms |
| Event free workshops | 4,177.0 ms | 2,441.8 ms |
| Event outdoors/toddler intent | 2,672.4 ms | 1,664.6 ms |
| Event subject with excluded phrase | 5,496.6 ms | 3,556.2 ms |

The coffee fixture is a correctness comparison (0/200 versus 200/200 cafés), not
an equal-population latency claim. Other labelled populations remain equal. The
1,001-card grouping control retains all 1,200 original locations. The known-defect
baseline benchmark intentionally records its coffee failure; it is not counted
as a passing native run. All candidate populations pass.

These are emulator component observations, not complete-search, physical-phone,
battery or memory benchmarks. Topic matching still has measurable work; this is
not a claim that every local search is instant. The existing progressive delivery,
request budgets, source ownership, Tor/session policy, cancellation and saved
snapshot guarantees remain in force.

The full JVM run passed **2,461 cases, zero failures/errors and two optional skips
(2,463 total)**. The skips remain the private cross-post packet and optional native
embedding check. Release lint passed. The signed candidate passed the existing
business/event/store/menu/Library native selection with **83 passes, zero failures
and zero skips**. One preceding shared-process candidate run timed out in three
background-start waits after the standalone benchmark; those three passed in a
fresh process with unchanged code. Both logs are retained, without claiming a
proven cause. Frozen clean-commit APK/source/signing, repeated shipping-native
checks, preview backup/state preservation and the anonymous permanent download
are verified before promotion/publication.

## 0.38.51 publisher route diversity and repeated reconciliation

Reviewed against v0.38.50 (`dda1ee7`) on October 27. This increment changes
`LocalWebDiscovery.kt`, its routing regressions, `darwin-routes.json`,
`LocalDiscoveryStore.kt` and its native store regressions. No model, permission,
production dependency, database migration or new background poller is added.
Darwin remains the routing donor. The new paths discover publisher links; search
snippets do not become business/event facts or hydrated social posts.

A controlled Gateway/SOCKS counterexample failed on unchanged scheduling: four
untried mirrors preceded a different working protocol, and the three-attempt
budget never reached it. The test timed out. The corrected ordering snapshots
observations once and interleaves methods only in unproven slots. The same check
now reaches the different method second, while the first remains held. A second
control retains two recently useful mirrors ahead of unknown methods. Existing
cases cover three shared permits/attempts, selected-provider confinement, source
refusals, cancellation, session round trips, endpoint-specific learning and the
retained LibreY HTML path. These are deterministic routing changes, not a claim
that different frontends have independent upstream indexes.

Eight newly inspected published 4get roots were read through Android Gateway/Tor
using a generic query. Two returned explicit successful JSON with organic links;
the others returned two timeouts, one HTTP 500, one refused cross-origin redirect,
one challenge page and one HTTP-200 JSON failure. Only `4get.dcs0.hu` and
`4get.edmateo.site` enter the bank. A separate production-parser native check on
each returned ten links in **3.430 s** and **3.385 s** after transport readiness.
Together with the retained qualified endpoint these provide three configured
4get alternatives, alongside LibreY. These are dated successful reads, not proof
of continued availability, complete regional coverage or independent operators.
The [protocol and current inventory](LOCAL_DISCOVERY_ROUTING.md#publisher-method-diversity-and-unchanged-cache-work-03851)
describe the remaining limits and an inactive Mwmbl research lead.

The store previously repeated full JSON decoding and matching on unchanged
publication inputs. Two small in-memory receipts now identify the exact bounded
active inputs with framed SHA-256, including expiry, saved-ID preference and the
complete business scope. Fingerprinting and repair use one database transaction;
only committed results are remembered. A miss decodes the payloads from the same
scan rather than reading them twice. There is no persistent duplicate record
cache, change to saved bytes or shortcut based on row count/checked time alone.

`LocalDiscoveryStoreTest` exercises unchanged reads followed by same-length edits
from a second connection, newly active rows, event expiry without renewal,
coordinate-less records with different locality scope, a forced SQLite abort and
an outer rollback. Existing admission, cache/saved-record and factual-revision
checks remain. These native controls passed alongside the opt-in synthetic
preparation benchmark; final shipping selection is recorded below.

The API 35 emulator measured the same synthetic 1,200-business/2,500-event pool
in the old and candidate **release** builds, each with three operation samples.
No other build/test was running during these release measurements. A saved
business snapshot remained unchanged as a record; separate store controls compare
protected payload bytes. Timings below concern reconciliation
alone, excluding network, subsequent record reads, relevance and card drawing.

| Operation | v0.38.50 first sample | Candidate first sample | v0.38.50 subsequent samples | Candidate subsequent samples |
| --- | ---: | ---: | --- | --- |
| Business reconciliation | 218.1 ms | 253.4 ms | 215.0 / 210.0 ms | 34.3 / 36.5 ms |
| Event reconciliation | 335.3 ms | 355.9 ms | 341.5 / 332.9 ms | 131.7 / 124.3 ms |

The first pass still performs complete matching and has measured overhead.
Repeated unchanged work is substantially cheaper on this fixture. Matching debug
builds independently showed the same direction. An earlier debug-candidate versus
release-baseline comparison was not used to claim a speedup. These observations
are not physical-phone end-to-end latency, p95, memory or battery qualification.

The complete JVM run covers **2,452 cases: 2,450 passes, zero failures/errors and
two optional skips** (private cross-post packet and optional native embedding).
An initial private business replay failed exact membership because its captured
rows were paired with an incomplete expected-member set. The retained baseline
capture and its reviewed expected set pass together; no product matching rule was
changed to resolve that engineering-input error. The failed run remains external
evidence. Release lint passed. The signed candidate passed **81 business/event,
store, menu and Library native checks with zero failures or skips**. Its business
and event screens were inspected, including the single 25-location chain card.
The same native selection is repeated against the frozen clean-commit artifact
before publication, with source/signing and retained-preview-state verification.

## 0.38.50 chain grouping before website enrichment

The reported taco-search flood exposed a generic alias-compilation defect in
v0.38.49 (`b2489c5`). The pinned NSI data has separate ordinary and Cantina
storefront records sharing the parent label Taco Bell. Our compiler flattened
`name`, localized names, `matchNames` and parent-brand labels into one list.
The exact ordinary name then appeared to have conflicting IDs, so the catalog
correctly refused to guess from that damaged representation. A second gap skipped
the catalog whenever a directory supplied any brand label, even without an ID.

The next live check found a remaining branch under a website locality heading
and the generic `Store` category, while its retained directory name was still
the exact chain name. That candidate failed the live one-card assertion. A
private replay and two further frozen controls reproduced the omission. The
reading projection now considers up to eight distinct qualified directory or
publisher name assertions, preparing category interpretation once. Generic
schema types such as Store remain unknown for this lookup; detailed incompatible
categories still reject it. Public-post name mentions are not identity aliases,
and conflicting recognized source-name IDs leave the record ambiguous. No
published field is overwritten or manufactured.

The compiler now preserves storefront/explicit matching aliases separately from
parent labels, using compact alias indexes in the same offline resource. Lookup
first applies the existing country, exception and category rules, then prefers an
exact storefront alias. Two genuinely conflicting exact names remain unresolved.
Compatible published brand labels may use the catalog; explicit IDs and conflicting
brand declarations cannot be overridden. Canonical storefront names also keep
qualified variants distinguishable. There are no brand-specific code branches.

Changed paths are `scripts/compile_place_brands.py`, the pinned compiled
`place-brands.json`, `KnownPlaceBrands.kt` and the reading projection in
`LocalDiscoveryPresentation.kt`. The same 14,259 catalog rows are retained; the
raw resource grows from 1,285,502 to 1,360,421 bytes. There is no added request,
model, permission, dependency, background worker or database migration. Saved and
live branch records remain separate; the change groups reading cards. Locations
retain their names, addresses, phones, source evidence and distance order.
The compiler reproduces the resource from the existing archive hash and preserves
its BSD notices. [Pinned upstream records](https://github.com/osmlab/name-suggestion-index/blob/c2b5ff98903a8dce2d9c75e9b358fb3a6f8ae024/data/brands/amenity/fast_food.json)
remain the source, rather than an app-maintained special case for one chain.

Two focused JVM controls failed on unchanged production: bare ordinary branches
and brand-labelled branches spanning official site variants. The corrected tests
also retain distinct formats, different explicit IDs, incompatible categories,
country exceptions and genuine same-name conflicts. `LocalBusinessDeckTest` and
`KnownPlaceBrandsTest` verify unchanged location facts.

A frozen v0.38.49 debug build failed the new native control with **25 cards
instead of two**: 24 bare Taco Bell branches plus one independent restaurant.
The candidate passes the same control with two cards and a visible **24 nearby
locations** counter. A further branch carrying a locality heading, generic Store
category and qualified source name raises that counter to 25 in the same card.
Addresses/phones remain intact and the chain key stays stable as brand/website
details stream in. Both native viewports were inspected directly.
The baseline debug catalog was byte-verified against the official APK. An initial
attempt pairing debug instrumentation with the release module hit an internal
method-name mismatch before the fixture; it is retained as an engineering
failure, not a functional counterexample.

A completed live search on the unchanged official release already grouped ten
records named Taco Bell and two records with generic titles but explicit scoped
brand relationships into one card after enrichment. This does not
reproduce the reported flood at that completed stage. It demonstrates why testing
only the final search state misses the sparse first-deck failure. Those real
records are replayed privately to verify that qualified grouping and every field
remain intact. The first replay assumed every member had the literal brand name
and failed its membership assertion. The expected record IDs now come from
reviewed name/brand/first-party source fields, independently of the grouping
output; all membership and field-preservation assertions remain. No reader area, address, source packet or screenshot is published.

The complete JVM run passed **2,448 cases, zero failures/errors and two optional
skips (2,450 cases total)**, including the private full-record replay. Optional
skips are the private cross-post packet and native embedding check. Broader
business/event/menu/library native qualification passed **77 methods with zero failures or skips**. The signed shipping artifact repeats that selection before
publication.

The final APK/source, signer, preview-state preservation and anonymous permanent
URL are verified separately during publication. Physical-phone performance is
not inferred from these emulator checks. Unknown or contradictory business
identities remain separate; this is not permission to collapse unrelated places
merely because their names resemble one another.

## 0.38.49 independent-review correctness pass

Reviewed on 2026-10-27 against **315e896 (v0.38.48)**, rather than assuming the
packet's older v0.38.38 findings still applied. The fixes and regression sources
are identified by the immutable **v0.38.49** source tag. This is a targeted pass:
no model, permission, production dependency, database migration, additional
crawler or speculative public endpoint was introduced. Darwin remains the routing
donor. Saved records, source provenance and source-owned continuation remain intact.

| Finding | Disposition | Evidence and correction |
| --- | --- | --- |
| 1. False historical completion | **CONFIRMED AND FIXED** | Generic dated HTML with no continuation previously cleared an absent retained head. `FeedCompletionTest` reproduces this before correction. `Page.historyExhausted`, `FeedRefresh.exhausted`, `FeedCatchUp` and the qualified readers now require explicit terminal evidence or actual overlap with the previous head. |
| 2. Stale publisher hints | **CONFIRMED AND FIXED** | A native controller callback blocked behind the store monitor committed an obsolete hint on unchanged v0.38.48. All query-owned source offers now carry admission; source outcomes and menu writes also check their generation at the database boundary. |
| 3. Event compounds and exclusions | **CONFIRMED AND FIXED** | `rocket launches -cancelled` failed compound-alternative admission; minus operators were lost. `LocalEventIntent` now carries bounded whole-subject paths, quoted phrases and exclusions into both publisher queries and final admission. |
| 4. Corporate facts assigned to a branch | **CONFIRMED AND FIXED** | City-only corporate descriptions could supply local hours. `LocalBusinessSocial.branch` requires matching branch evidence; a second reproduced control separates units in one building. Shared corporate relationships remain available. |
| 5. Physical-device performance matrix | **QUALIFICATION INCOMPLETE** | Only API 29/API 35 emulators were attached. No controlled physical-phone end-to-end median/p95, sustained CPU, memory or battery matrix was collected. Emulator tests and owner phone feedback do not establish those figures. |
| 6. Independent endpoint resilience | **QUALIFICATION INCOMPLETE** | Bounded failure/stall controls passed and the one configured 4get endpoint returned useful links through Android Gateway/Tor. Production routing already had the necessary budgets and per-source isolation. A single independently hosted endpoint still does not establish a redundant live inventory. |

### Historical coverage

Changed sources: `core/.../Models.kt`, `FeedRefresh.kt`, `FeedCatchUp.kt`,
`Extraction.kt`, `RssSources.kt`; native persistence coverage is in
`RepositoryFeedCatchUpTest`. Unknown terminal state is nullable and omitted from
ordinary serialized Pages; absence of a cursor is not positive evidence. Generic
pages, empty heads, unknown empty responses, failed/limited/cached/index-backed
responses and unsupported terminal claims leave an old gap retryable. Useful
new rows remain readable and the previous successful frontier is retained.

The qualified terminal readers recognize a selected Reddit Listing's explicit
`after:null`, an exact source-owned Nitter `No more items` footer, or the existing
RSS/Atom feed-level RFC 5005 complete declaration. Dropped or malformed rows, and undated social timeline
rows, cannot authorize completion. RSS retains its existing readable undated
entries; a complete declaration describes the logical feed's supplied entries. Nitter's `Load newest` is not an older-page
cursor; wrong-account paths, unrelated filters and duplicate cursor parameters
cannot certify that account's end. An empty qualified terminal continuation may
finish after a nonempty page; an empty first response may not. Initial bounded
subscription coverage has no previous gap and retains its existing behavior.

These are format declarations, not proof of all material ever published on a
website. See the [Nitter renderer](https://github.com/zedeus/nitter/blob/master/src/views/timeline.nim),
[Reddit listings](https://www.reddit.com/dev/api/#listings) and
[RFC 5005](https://www.rfc-editor.org/rfc/rfc5005.html).
`FeedCompletionTest` covers generic retry, explicit/absent Listing ends, empty
terminal continuation, invalid rows and complete/ordinary/wrong-namespace RSS.
The RSS compatibility control failed on the intermediate fix before being repaired.

### Generation boundaries

Changed sources: `app/.../LocalDiscoveryController.kt`, `LocalDiscoveryStore.kt`.
The two reported `offerSources` calls were unguarded. The audit also found
query-owned seed/refinement offers, account-projection receipts, route-yield
updates, ingestion-completion receipts and menu ingestion that
needed the same boundary. They now check admission after taking the existing
store monitor and before transaction commit. Hint and scheduling observations
commit together, without invoking factual reconciliation. Menu admission binds
both query and menu generations, including visibility across worker threads.
Public response bodies and account-reachability caches retain their reusable
source/query keys; a receipt claiming facts were projected or ingested is
generation-owned. Explicit imports remain independent of a search generation.

`LocalDiscoveryTest.queryChangeRejectsPublisherHintsAlreadyWaitingForTheStore`
reproduces the blocked obsolete callback and verifies a current callback still
commits. `closingAMenuRejectsFactsAlreadyWaitingForTheStore` revokes only the menu
generation. `LocalDiscoveryStoreTest.aRevokedBatchRollsBackFactsAndPublisherHintsBeforeCommit`
checks rollback of facts, hints and route-yield observations, unchanged saved
payloads and factual revisions. No global serialization or delivery delay was added.

### Meaning and branch attribution

Changed sources: `core/.../LocalEventIntent.kt`, `LocalBusinessRouting.kt`,
`LocalBusinessSocial.kt`, plus the controller's announcement-location check.
Event search still issues two publisher queries. One useful whole-subject alias
may occupy the second branch; audience-only synonyms cannot displace its existing
targeted publisher route. Exclusions and quoted phrases accompany both branches.
Search plans are memoized per immutable intent. Regressions cover spaceflight,
compound morphology, minus/quoted exclusions, quoted possessives and unrelated
near-matches. A native Events search renders the useful alternative while
rejecting the cancelled and unrelated records.

Branch evidence requires the matching brand plus a known phone or matching street
and locality; conflicting phones, streets or units reject attribution. An
address-only match for a known unit must identify that unit. One global hours
block cannot be assigned among several published addresses. Unqualified account
descriptions remain sourced account evidence, rather than becoming branch facts.
The same qualifier governs whether an announcement may borrow the branch location.
Controls include two branches with different hours, city-only corporate text,
matching branch addresses, conflicting/shared profiles, same-building units,
and repeated or multiline valid addresses. This reproduced defect was in the
profile pathway; it does not imply every existing website extractor was defective.

### Qualification scope and retained failures

The final JVM selection contains **2,442 passes, zero failures/errors and three optional
skips (2,445 cases)**. Optional skips are the
private business packet, private cross-post packet and pinned native embedding
check. The isolated API 35 native debug selection passed **211 methods, zero failures
and one optional mixed-network live-Tor skip (212 methods total)**. Native scope covers feed coverage/catch-up,
streaming/reading locks, collection/save/media preservation, subscriptions,
discovery/menu/announcement flows, library migration and search privacy. Actual
native screens were inspected, including first-card delivery, streamed social
business details and compound-event results; synthetic fixtures disclose no
reader location or private state.

Retained failures matter: the initial focused JVM run failed four cases on the
unchanged baseline; the native stale-hint race also failed before correction.
The first full candidate JVM run failed two existing controls: serializing a
false completion flag changed a Page quota, and an audience-only alias displaced
a targeted query. Nullable unknown state and subject-only alternates repaired
those regressions. A subsequent same-building branch control exposed the unit
ambiguity and failed before its correction. The broader native candidate had
208 passes, three failures and one optional skip: one old fixture expected hours
from city-only evidence; another sampled its revision before legitimate work
reached the barrier; a cancellation fixture seeded a follow before its Room
observer admitted it. Their preconditions were repaired while retaining the
assertions. An earlier focused native current-offer control required overriding
the streaming callback, rather than only its delegated nonstream counterpart.
An added RSS control initially assumed undated RSS entries were unsupported;
the existing RSS policy deliberately retains them. Its malformed-row control
now checks a dropped empty item without changing that date policy. These failed
runs are retained as failed, not counted as passes.

`LocalWebDiscoveryRoutingTest` adds failed/stalled prioritized-4get controls:
an eligible alternate still delivers within existing hedging/attempt/concurrency
budgets, cancellation does not train a source outage, and a 503 does not poison
its peer or trigger speculative HTML retries. Existing selected-provider and Tor
controls remain in the full suite. No production routing change was needed for
this finding. A separate opt-in native check returned ten publisher links from
`4get.canine.tools` in 2.023 seconds after Tor readiness. That single index
operation is not a complete-deck benchmark or a p95. An initial engineering command
quoting error ran zero methods and is not counted as a provider failure or pass.

Source/tag, signed shipping artifact, protected preview-state promotion and
permanent-download integrity are qualified separately during publication. Private
logs, fixtures, backups and screen captures remain outside the public checkout.

## 0.38.48 cold-source coverage and complete gallery roots

Qualified on 2026-10-27. The owner's immediate phone feedback after 0.38.47
showed that its warmed-cache inspection did not establish a good cold experience.
This increment separates acquisition, account ownership, grouping and visible
presentation. Public subscriptions were made through the actual Subscribe all
interface on a disposable API 35 emulator; live refreshes injected no posts or
fingerprints. The retained API 29 laptop preview was also reviewed directly.

Cold retrieval exposed different source windows: one Facebook response supplied
three entries and the working Instagram feeds supplied six without a cursor.
The old first-subscription stop left a counterpart outside the acquired window.
An empty feed now publishes its first coherent rows and source memberships
immediately, then follows bounded source-owned initial history: up to three pages,
or five for Facebook, stopping earlier at 24 posts or two sufficiently old
entries. RSS keeps one initial window; warm catch-up retains its original gap
and completed-generation transaction. An unreadable legacy wrapper cannot define
the previous frontier. Four real refreshes observed first Room delivery in
0.637–0.808 seconds while complete source checks continued for roughly 50–90
seconds. These are emulator observations, not phone or paint measurements.

Two independent ownership controls failed before correction: a validated
Instagram author declaration was not used by linked-account matching, and
conflicting legacy account aliases could select an owner by list order. Actual
follow memberships now disambiguate the latter; unresolved conflicts remain
unqualified. Display-name similarity does not establish ownership. The reported
short-caption family already grouped in the initial cold replay, so this record
does not claim that its exact handset failure was independently reproduced.

Native scrolling also found a gallery beside its repeated singleton pictures.
Already admitted gallery editions now share one announcement root, with a bounded
union of observed slots for child proof. Save/composition validation independently
requires pairwise matching captions and an actually shared picture before using
that union. An independent gallery remains ambiguous; stock-photo chains do not
authorize transitive collection. The existing colon/downward-pointer convention
also accepts a complete, distinctive shorter gallery heading. Original records,
unique pictures, additional sentences and source attribution remain intact.

Direct visual review covered 90 scrolling frames of one warmed public bundle,
100 of another and 165 of the cold public feed. The inspected short-caption
family appears once with three platforms; the gallery appears once with five
originals and five distinct portraits. The cold arcade announcement now includes
X/Facebook; the warmed cache retains all three previously acquired platforms.
The six-entry Instagram feed still withholds that older counterpart. These
windows are the scope of the review, not a claim that every future or unavailable
edition is covered. Distinct lunar photographs, portraits and document pages
remain distinct. Private captures and reader records stay outside the checkout.

The full JVM suite passed **2,435 cases, zero failures/errors, three optional
skips**. The isolated API 35 native selection passed **131 methods, zero failures
or skips**, including first-page publication, legacy-wrapper recovery, complete
gallery saving, image-cache evidence, late arrivals, reading locks, cache purge
and subscriptions. An earlier 130/131 run exposed a test selecting the Search
platform instead of the actual Following tab; its setup now uses the native tab.
A repeat with unrelated live-review records present failed six screen-wide
fixture assertions. That run is retained as failed; the clean disposable-fixture
batch is the 131-method passing record. The live laptop preview was never reset.

Three untraced native samples per APK over the same private 738-post pool show a
grouping median of 10.834 seconds before and 11.209 seconds after, about 3.5%
more CPU time while retaining every original and collecting one more family.
See [performance scope](FEED_PERFORMANCE.md). No database migration, permission,
production dependency, model, native runtime or per-comparison network lookup is
added. Source/tag, signed shipping artifact and permanent-download qualification
are performed separately during publication.

## 0.38.47 native feed inspection and refresh-stable collections

Qualified on 2026-10-27. Repeated inspection of actual native subscription feeds
found failures that captured grouping tests alone had missed. Intermediate
candidates were installed into the live laptop preview after native regression
checks, with private state backups and retained-file hash verification. The
inspection followed rendered cards through scrolling, source refresh, late media
observations and deliberate Newest adoption; private source records and screen
captures stay outside the public checkout.

The fixes cover empty generated feed wrappers with misleading update dates,
generated author/title echoes, source transport-escaping artifacts, compact
proper-name references, cover-backed announcement headings, same-feed caption-only
reel counterparts and publisher-authored thread supplements. Distinct wording,
reaction text, pictures, recordings, source links and discussion roots remain in
the collection. One video per source edition uses a selectable player slot rather
than a repeated row of players. Multiple recordings from one original stay
separate. These paths extend the existing detectors and literal fusion routes.

Late counterpart delivery now coalesces fully qualified overlapping feed rows
while retaining the visible card's body/key and established ordering timestamp.
Automatic adoption cannot move an already displayed announcement farther down
the same scroll. Partial overlap does not authorize joining unrelated groups.
Explicit Newest adoption can establish a fresh chronology.

A later native pass still found one announcement twice despite its private
replay passing. The live source had renewed four signed image URLs. Its frozen
body retained earlier media while current grouping lost the corresponding pixel
observations. Replaying that precise URL change reproduced two groups. The image
bank now reuses compatible observations through the existing strict CDN object
identity, with exact observations taking precedence and contrary decoded copies
blocking alias fallback. Expiry, eviction and clearing remove this evidence.
No files, hashes, AI declarations or cryptographic validation are transferred.
The actual Android decoder/refreshed-source replay then produces one group.

Recorded-input checks cover ten announcement families and 33 selected originals
inside a 738-record corpus with actual subscription memberships, in both input
orders. They retain every selected original and check named common passages
appear once. Separate Android decoding checks exercised six refreshed-image
reads, smaller cache-restoration renditions and the signed-URL transition. Sixty-four
mixed-resolution combinations also passed; resolution differences were ruled out
before the URL change was isolated. Six recorded repost examples verify one
caption with supplied full destinations and unchanged raw records. A subsequent
native pass found an attached Facebook story repeated beside its X edition; the
adapter's generated “Shared from” line is now retained as visible attribution
outside the caption. Its two decoded originals pass the corpus replay as one
card. The qualified name-alias compositor also takes precedence over partial
paragraph composition, avoiding a repeated paragraph around a name/handle change.

The full JVM suite passed **2,426 cases, zero failures/errors, three optional
skips**. The broader intermediate API 35 native selection passed **176 methods,
zero failed, one optional live-Tor skip**. After the signed-URL fix, all **68
focused native methods passed**, including contrary-image, unsupported-transform,
expiry and eviction controls. Debug/test assembly and release lint pass.

One intermediate native run caught a fixture publishing a media file outside
the repository's lease, allowing startup orphan maintenance to race its database
registration. The fixture now uses the production lease. Another exposed the
legacy test scheduler resuming Compose recomposition on a worker; this suite now
uses the queued v2 rule already in the pinned dependencies. Production coroutine
dispatchers and cache cleanup were not weakened to obtain passing checks. Failed
runs remain in the private evidence record.

There is no new production dependency, permission, model, network transport,
database migration or native runtime. No extra crawl or whole-video download is
introduced. The bounded visual alias index owns references to the existing image
observations and expires with them. Public fixtures use synthetic inputs and the
private reproduction-string audit reports zero findings. These checks qualify
the inspected source windows and retained cases; they do not establish perfect
matching for arbitrary future posts or withheld source content.

## 0.38.46 complete collected posts and bounded candidate recovery

Qualified on 2026-10-26. This increment retains the existing detectors,
CaptionWeave, literal source paths, discussion roots and chronological reading
locks. A shared read-only collection plan gives the live card, Context and the
saved reader a complete attachment inventory even when compact caption alignment
cannot represent an already admitted collection. That fallback retains complete
attributed source text instead of selecting only a platform's longest version.
Failed saved-source validation reports failure rather than silently saving the
primary edition alone.

Retained failing controls reproduced four independent defects: a redundant
generated title displacing a richer same-original gallery snapshot; auxiliary
candidate overflow erasing a distinctive reciprocal nomination; a late photograph
with an unchanged caption failing to trigger body adoption; and equal video
posters suppressing distinct playable recordings. The final controls pass. The
same-original chooser now compares effective captions within an acquisition
batch; it does not invent publisher edit identities or union historical revisions.
Candidate buckets are scheduled by selectivity under the existing 128-candidate
bound. Skipped comparisons are not evidence that posts differ.

The existing gallery route now accepts a qualified exact heading within a fuller
announcement, retaining its additional sentence. Detailed attachment-only image
observations can support that nonblank caption route without acquiring general
post-identity authority. Short admitted captions can compose despite opaque
campaign links. Feed-enclosure entries matching declared media no longer become
competing editorial destinations; literal publisher links and genuinely different
articles retain their guards. Decorative ending punctuation can share one actual
reading caption while material wording remains attributed and originals unchanged.

Media inventory and screen layout are separate. Confirmed picture/rendition
copies still combine with their source labels. A shared cover can use one media
slot with selectable editions while retaining distinct recordings or qualified
related frames. Preview attribution distinguishes supplying a still from supplying
a playable video. A newly observed picture triggers the existing pending/idle
adoption mechanism; a proven re-encoding adds attribution without reopening a read
announcement. Persisted read signatures and viewport protections are unchanged.

A private Gateway capture retrieved 19 current head posts across three platforms.
Eleven selected originals, including one retained earlier counterpart, form four
expected announcement families in both input orders with explicit linked-publisher
fixtures. Every selected media read decoded. A separate assertion checks the short
shared caption appears once. This qualifies the captured input and supplied
linkage, not every possible phone subscription binding or future source response.

Validation: **2,404 JVM cases, zero failures/errors, two optional skips**. Final
API 35 native regression: **171 methods, 170 passed, zero failed, one optional
live-Tor skip**. Debug assembly and release lint pass. The selection covers cold
gallery matching, literal wording/projection guards, late-photo adoption, read
inheritance for observed re-encodings, same-batch storage/page replay, distinct
video choices, Context, saved/portable copies, cache recovery, settings retention,
sharing, chronological memory, coverage and revalidation. Native screens were
inspected, including selecting a recording and its actual decoded cover.

Earlier broad runs caught a repeated styling-only caption and a related-frame
layout regression; both were repaired before the final full rerun. Two native
fixtures needed actual Library/media scrolling and an asynchronous-open wait.
An additional same-URL/conflicting-duration control verifies stable selectable UI
keys. Failed runs and corrections remain in the private evidence record.

No dependency, permission, database migration, model, decoder or network transport
is added. This change introduces no extra crawl or full-video verification fetch.
Release promotion verifies the clean source stamp, existing signer and unchanged
native payload, then backs up and checks retained preview state. Controlled
physical-phone performance and arbitrary fine-detail image changes remain outside
this qualification. See [attachment collections](ATTACHMENT_COLLECTIONS.md) and
[media collections](MEDIA_COLLECTIONS.md) for the production boundaries.

## 0.38.45 cache recovery and source-attributed wording variants

Qualified on 2026-10-26. A new native regression first reproduced a manual-cache
purge followed by a successful source refresh that left the feed empty. Manual
clearing had marked every removed post as suppressed, and subsequent refreshes
would not store those posts. Manual clearing now re-admits refill without deleting
first-seen/read history; automatic expiry keeps its suppression policy. A newly
retrieved copy also receives a fresh cache lifetime rather than inheriting an old
first-seen date and expiring immediately.

Owned response fixtures exercise an HTTP-success challenge body, a corrupt copy
from an earlier run, a missing ordinary file and an atomic replacement racing an
older decode. Failed ordinary image bytes are discarded; saved offline files and
newer replacements survive. Retry and completed refreshes can recover visible
errors. Successful visible pictures do not refetch on a refresh. These are native
Gateway/Room/decoder/UI checks, not a reproduction of a particular phone VPN.

A private three-platform capture supplied the same two pictures and nearly
identical captions with one local wording insertion. All six media reads decoded;
the attachment inventory retained two distinct pictures. The earlier grouping
failed its private replay. The new bounded literal-variant route passes that replay
in both input orders, retains every original, and presents common passages once.
Synthetic fixtures exercise two/three variants, source reconstruction, complete
sentence additions and the existing claim/name/destination exclusions. Initial
candidates exposed status/name guard bypasses; those were corrected before the
passing full suite. See [caption variants](ATTACHMENT_COLLECTIONS.md).

Validation: **2,394 JVM tests, zero failures/errors, two optional skips**; debug
assembly and lint passed. The final API 35 native selection ran **161 methods:
160 passed, zero failed, one live-Tor opt-in skipped**. It includes actual Settings
→ Storage → Clear cache and feed refill; repeated purge/refill with 240 additional
subscriptions and unchanged configuration/read progress/saved text; image retry
and refresh recovery; dark/light native source marks and opening the correct
original; saving all three source captions; Library/media retention, sharing,
portable state, chronological feed memory, expiry, coverage and revalidation.
Native screens were inspected. Earlier fixture failures required fresh source
provenance, waiting for theme recreation, and scrolling to an off-screen tab;
they were corrected and rerun. An initial live Tor Project checker call closed
its TLS connection after bootstrap. That external result is retained separately;
the live two-process/checker test now requires explicit opt-in, as documented in
[building](BUILDING.md). Local transport and fail-closed regression checks pass.

No app permission, database schema, native runtime, decoder, network transport or
model changes are needed. Release promotion retains the existing signer, checks
the corresponding source and native payload, backs up the live preview and
verifies retained state before first launch. Private captures and reader backups
remain outside the public checkout and source archive.

## 0.38.44 compound references in corroborated image copies

One long caption can use a compound account handle while another spells out a
full proper name. The alias comparison now permits one leading article and an
ordered sequence of whole proper-name words, with at most two omitted name/work
qualifiers. At least two substantial words must reconstruct the entire handle;
a shared surname, partial spelling, changed subject, number, negation or concrete
destination cannot establish this match. This additional path requires the
existing independent image proof and long identical surrounding prose. It makes
no account-ownership claim and introduces no lookup or model request.

The recorded two-platform case occupied two cards on retained 0.38.43 logic,
despite both pictures decoding correctly. Its captured pixel observations now
produce one collected card and one complete caption taken from the actual
full-name edition, retaining both originals. Synthetic positive/separation cases
exercise the same mechanism without bundling reader reproduction inputs.

Source records, image acquisition and decoding are unchanged. The new native
fixture checks one caption, one picture with both platform labels, and protected
capture of both original editions. Qualification receipts remain external.

Qualification passed **2,389 JVM cases**, zero failures/errors and three optional
skips; final numeric/negation guards and the private recorded-input replay also
passed a 45-case selection. Debug/test assembly and Android lint passed with
existing warnings. The 112-method native reader/fusion/media/Library selection
has **112 passes and no skips** after correcting the new fixture's assumption of
a fixed platform-label order. Its assertions now require exactly both source
labels while respecting the configured order. The final 46-method native
fusion/media/sharing/Library selection passed, and its actual screen was inspected.
The initial fixture failure and successful rerun are both retained. No production
loader, Gateway, permission, model, database schema or native runtime is changed.

## 0.38.43 reader regression recovery and visible sharing

The full Material pull arrow/progress bubble replaces the overly subtle toolbar
feedback introduced in 0.38.42. Pixel-checkpoint separation, stable card anchors,
Following's All/source restoration and the finished-batch exit-copy bank remain.

Sparse chain records can now use an offline, pinned Name Suggestion Index slice:
14,259 public brand entries, compiled aliases, country/circular coverage and
exclusions, and compatible category checks. Unsupported polygon scopes are
omitted. Ambiguous identities and contradictory explicit declarations stay
separate. These are reading-group hints, never new business facts or proof of
account control. Branch records and nearest-first location order stay intact.
The compiler verifies the pinned archive and bundled BSD notices; no per-search
request, production library or model is added. See
[the upstream catalog](https://github.com/osmlab/name-suggestion-index) and
`scripts/compile_place_brands.py`. A new native category-search counterexample
shows 24 bare branches as 24 cards on qualified 0.38.42 bytes and one card on the
candidate, before website enrichment. Private sparse and live checks are recorded
separately from the synthetic fixture.

Image observations now distinguish strong post-matching evidence from attachment
comparison. Low-contrast photographs and simple graphics can suppress repeated
attachments inside an already qualified collection without linking unrelated
posts. Fine raster proof supplements coarse hashes where encoding changes cross
the hash threshold. Known clip-rendition identities and observed matching posters
collapse duplicate video players while incompatible known durations remain
separate. Source-platform sets accumulate on the retained attachment. Protected
copies preserve bounded comparison fields; export validation accepts the new
optional field without changing the source record.

A private replay of retained material reproduced repeated dark graphics and
low-contrast photographs. Actual native decoding reduced the selected galleries
from five/two attachments to two/one: the first keeps a genuinely different
portrait photograph. A separate video cohort retains one player with both source
platforms. A full 668-post replay also covers a delayed, re-encoded picture with
credit-name/handle variants; substantial surrounding prose and strong image proof
are required, and the shared caption is read once. Originals remain inspectable.

An additional detector removes decorative emoji/tail tags only in its comparison
projection. Bounded chunk overlap nominates candidates; literal ordering, claims,
destinations, publisher identity and close timing still govern admission. Its
candidate budget is independent of the established routes. Word substitutions,
changed numeric/negation/approval symbols and concrete destinations stay distinct.
Publishers without an actual decorative variant skip the extra preparation and
nomination bank; a cheap admission scan enables work only for relevant linked
publishers and their plain counterparts. An initial timing run exposed the cost
of preparing every ordinary post, so that unconditional work was removed.
A dated production-reader packet containing three ornamental variants now forms
one card and one actual source caption. Early regression failures caught an own
enclosure-link mismatch and an overbroad timing rule; both were tightened rather
than weakening the established separation tests.

Every post has a visible Share control and platform-original actions. They reuse
the existing clean-link resolver and show progress while it checks the route.
Platform actions restrict selection to that platform's editions; Discussions
still opens All. Collected originals expose Share beside Copy. Native chooser
tests intercept intents, so qualification does not actually send a message.

The Instagram-first-search phone report remains unconfirmed; this release does
not claim to resolve that intermittent case. No permission, database migration,
native runtime or change to Gateway/Tor policy is introduced. Source-dependent
observations are dated checks, not uptime or physical-phone latency guarantees.
The JVM suite passed **2,385 cases**, zero failures/errors and three opt-in skips;
the final destination-comparison refinement also passed the targeted cross-post,
announcement and decorative-copy selection. Debug/test assembly and Android lint
passed, with existing warnings retained. The 301-method API 35 native selection
has **281 passes and 20 optional skips** after updating the one old platform-icon
fixture to choose Comments from the new menu. Its original assertions remain;
the complete 38-method fusion/sharing/media selection then passed. A repeated
broad run also exposed a Library fixture reading initial shelf positions before
its query result arrived. It now waits for the related-match result before the
unchanged ranking assertion. The final performance admission checks passed the
full JVM suite and a 45-method native fusion/sharing/media/Library selection.
Both failed reports and successful reruns are retained.

After admission gating, three quiet runs of the existing 240-post/120-group
cached-picture fixture measured median grouping at 3.528 s, first-card time at
6.214 s and eight-swipe wall time at 5.216 s. The retained 0.38.42 runs measured
3.535 s, 5.974 s and 5.084 s respectively. The earlier unconditional candidate's
first-card median was 7.283 s. These small emulator samples support removing that
avoidable work, not a physical-phone frame-rate or zero-cost claim.

Two separately enabled production checks passed: actual media acquisition/decoding
retained attachment counts of two/one/one images and one video, and a fresh broad
business query returned 14 chain locations in one card. That search produced its
first card in 8.358 s and finished enrichment in 50.808 s on the emulator. Those
dated network timings are not phone benchmarks. Native sharing/menu/progress,
refresh, branch-picker and collected-post screens were inspected. Private replay
inputs, screenshots, state backups and publication receipts stay external.

## 0.38.42 reader progress and picture origins

The reader separates durable pixel checkpoints from the state needed to paint
the deck. Eighty pixel-offset changes in one card preserve the exact persisted
position without republishing the reading projection; anchor identities and read
marks still publish. An initial 240-post, text-only emulator comparison measured
median eight-swipe wall time of 4.716 s before and 3.683 s after this separation.
This small debug fixture does not establish physical-phone frame rate.

A cached-picture fixture exposed another startup cost: every restored image
fingerprint restarted the grouping projection and its readiness gate. A retained
release failed the new ten-second first-card deadline. Sequential, conflated
revision processing finishes the first grouping and then incorporates later
image evidence; changed source content and memberships still cancel obsolete
work. The candidate cleared that deadline. Three alternating runs per build with
240 posts, 120 groups and cached 1,280 × 960 pictures measured median first-card
time of 16.351 s before and 5.974 s after; eight-swipe wall time changed from
5.565 s to 5.084 s. Each run collected only 30–40 frame samples, so these timings
show a reproducible emulator improvement rather than phone frame-rate proof.
Captured fingerprints remain attached
to the matched editions because caption fusion and saved-copy validation use
that proof; an intermediate optimization that dropped them failed an existing
native caption-retention check and was removed.

Following remembers its own All/source selection across Search, including a
search on another platform. Explicit source tabs update that selection. New
readers start on All, and Facebook joins the default source set without replacing
custom choices. Pull gestures and user-requested check progress use a fixed
toolbar slot. The finished-batch exit-copy bank stays stable within a reading
session, distinguishes loaded material from successful source checks, preserves
unread/incoming/filtered status, and avoids repeating an already-visible exit
line in a snackbar.

Collected attachments retain their actual source-platform sets through image
deduplication, including same-URL and qualified CDN copies. Different pictures
remain distinct. Feed labels open the original editions; protected local copies
also show the labels. A synthetic native late-arrival case covers a numeric and
an opaque Facebook edition alongside two other platforms, one card, a shared
caption read once, and distinct picture-source labels. Private recorded-input
replay also checks the selected case within a larger retained corpus. No
personal reproduction text or reader state is bundled with these tests.

The reported Instagram-first-search startup error remains unconfirmed on the
test devices. Three opt-in native Search-tab queries returned readable cards.
Separate first-operation runs began with filters unready and Tor at zero percent;
both retained and temporarily cleared source-health state returned posts through
the selected protected index. The latter completed its first batch in about
24 seconds. A retained API 29 preview search submitted 2.65 seconds after launch
also progressed from placeholders to readable posts, with a partial-index notice.
These dated results do not establish that the phone-specific error is resolved.
Transport protections and provider/source selection remain unchanged.

Qualification covered **2,370 JVM cases**, zero failures/errors and three opt-in
skips, plus **181 native methods: 178 passes, three opt-in skips, zero failures**.
Android lint and debug/test assembly passed. Native coverage includes all reader
memory journeys, cross-post/fusion/caption retention, image matching, source tabs,
search/scope/privacy, channels, refresh/revalidation, and protected local copies.
The deprecated immediate Compose test clock produced thread/layout exceptions
under asynchronous fixture updates; the reading-memory and performance fixtures
now use the pinned queued v2 rule. The native privacy check examines the exact
paused-Tor failure and absence of connections to the selected providers, rather
than treating unrelated direct work as an increase caused by the blocked search.

No provider, model, permission, database migration or native runtime is added.
Private replay packets, device state, captured social material and timing traces
remain in the external evidence area. Source/signing, protected preview-state,
versioned artifact and permanent-download checks still gate promotion.

## 0.38.41 sparse place records in chain cards

The previous matcher covered explicit brand and shared-site records but missed
many sparse name-directory results. A privately supplied production query
returned 14 matching locations as 13 reading cards: only two locations had a
common website, while the others lacked brand/site fields or carried a directory
page. The same recorded packet failed the new one-card assertion before the fix.
It now yields one card with exactly those 14 original location records; other
business records remain separate and every field is preserved. A separate fresh
enabled production-controller search also returned those 14 locations in one
chain card. Inputs, packets, source URLs and detailed traces remain private.
These are dated source-dependent checks, not a phone latency or uptime benchmark.

Two physically distinct branches already connected by qualified site/brand
facts can establish context for an exact normalized name. Sparse rows attach
only to one unambiguous established chain, with contradictory brand IDs, sites
and known categories excluded. A lone site claim, common directory/ordering host
or repeated name alone does not establish a chain. Scoped website-field evidence
can qualify an abbreviated hostname; account-ownership rules are unchanged.
Overture's optional brand primary name and validated Wikidata ID now survive
normalization. Preparation stays in the existing background projection.

A 17-case core selection initially had three failures: sparse-row attachment,
bound abbreviated websites and dropped Overture brand fields. Separation controls
already passed. Further coverage checks unbound/mismatched sites, shared ordering
providers and contradictory categories. The optional private recorded-packet
replay and the full 18-case grouping suite passed together. The new native sparse
streaming case failed its actual 13-cards-instead-of-one assertion on retained
qualified 0.38.40 debug bytes, then passed on the update; no linkage failure was
counted as product evidence. The inspected synthetic native screen shows one
14-location card and preserves its earlier card key as evidence arrives.

Final qualification: **2,369 JVM cases**, zero failures/errors, two optional skips
(model weights and private-packet input); Android lint and debug/test assembly
passed. Frozen APKs ran **147 native methods: 130 passes, 17 opt-in skips, zero
failures**. Installed APK hashes match those files. Coverage includes business
and event discovery/store, menus, announcements, branch ordering, Library/protected
copies, card status, social search/scope, scheduling, privacy and feed windows.
The separately enabled live production case and private-packet replay are reported
above rather than inflated into the default regression totals.

Live engineering reports now distinguish reading cards from physical location
records. `groupedName` is an optional private execution expectation, not a bundled
business/location default. No provider, request, installed dependency, model,
permission, database migration or cache is added. Normal signing, exact source,
privacy, preview-retention and permanent-download checks gate promotion.

## 0.38.40 chain cards, branch distance and concurrent progress

The business deck retains scoped brand declarations from regional and published
brand records, schema brand relationships and compatible official websites.
Prepared identities and indexed weak-association components keep conflicting
brands separate, including bridges through no-ID or multi-website records.
Same-name and operator-only records do not establish a chain. Intact legacy OSM
brand tag/value pairs can participate from cache. No legal ownership is inferred.

Grouping runs in the background before the 160-card limit, within the existing
2,000-record read bound. Separate branch facts and protected saved copies remain
intact. Locations sort by distance from the selected area point, with missing
coordinates last, independently of stable outer card keys/positions. An open
branch stays selected as a nearer branch arrives. The location picker shows
branch distances and expands twelve at a time.

A new six-case JVM counterexample had four failures on the unchanged grouping
implementation: lost regional brand ID, ignored declared brand, branch-suffixed
chain title and an ambiguous shared-website association. The two separation
controls already passed. Final coverage adds eleven core cases, including
transitive multi-website conflicts, distinct card keys, scoped schema brands,
legacy-cache admission and nearest-first ordering without modifying branch data.
Two new native cases ran against retained, qualified 0.38.39 debug bytes and
failed their actual assertions: 170 chain branches excluded an independent
business from the deck, and a late nearer branch remained last. No linkage error
was counted as product evidence. Both pass in this release, alongside existing
branch-contact and protected-save coverage. Actual native chain/selected-branch
screens were inspected.

The first 145-method broad native batch also caught an unchanged menu test
waiting ten seconds for its loading status to clear after menu data arrived.
Three separately traced standalone executions completed normally; those passes
did not establish a fix or the original timeout's cause. A separate controlled
native collision then demonstrated a real defect: the controller's snapshot-copy
search completion restored a menu status cleared between its state read/write.
Atomic state updates retry that collision and preserve the completed status;
search progress checks its generation inside the update. The same injected
collision fails on the retained pre-fix app and passes after the change. All ten
menu methods then passed, with the original deadlines and assertions unchanged.
The first failed broad run and diagnostic traces remain in engineering evidence.

Final qualification: **2,361 JVM cases**, zero failures/errors and one optional
model-weight skip; Android lint and debug/test assembly passed. The frozen native
selection ran **146 methods: 129 passes, 17 opt-in skips, zero failures**. It covers
local discovery/store, announcements, menus, protected records/Library, card
status, social search/scope, scheduling, privacy and feed windows. Installed APK
hashes match the frozen files.

No data provider, model, runtime library, permission, database migration or extra
network request is added. Existing locality, cache, route, Tor/session and request
bounds remain. The source text/archive privacy checks, compatible signer,
matching clean APK revision, unchanged retained preview state before launch and
full anonymous permanent-download hash form the release gate. These controlled
fixtures are not a physical-phone latency/battery or general source-availability
benchmark.

## 0.38.39 regional directory admission and private reproduction inputs

Current business/event fixtures use synthetic identities, contacts, addresses
and coordinate frames. The original branch, duplicate, field-provenance and
geographic relationship assertions remain. Source-specific live checks obtain
reproduction values privately at execution; unrelated public provider examples
remain identifiable. Historical measurements omit reproduction details rather
than substituting invented query values. This does not rewrite older releases.

The directory reader shares one serial admission slot across the repository,
uses the existing two operators and request/response bounds, and learns useful
nonempty delivery separately from query-specific failures. Known pauses are
checked before transport preparation. Generated category-only queries omit
unnecessary name/brand/alternate-name regex branches, while quoted and unresolved
names retain their literal routes. See the
[routing contract](LOCAL_DISCOVERY_ROUTING.md#regional-directory-admission-03839).

The same new native pause test ran on the retained 0.38.38 main APK and candidate
main APK: the baseline enabled transport despite two known directory pauses;
the candidate kept transport disabled. The baseline failure was the actual
assertion, with no class/method linkage failure. Subsequent final native coverage
also includes this contract.

Two additional JVM counterexamples found repeated retry headers discarding the
longer wait and an ordinary empty query teaching a productive content route.
Both now pass. A five-case query-plan counterexample failed only the unwanted
category-regex condition before the compiler change; literal, quoted, mixed-name
and exclusion cases already passed and remain covered.

The current full run covered **2,350 JVM cases**, zero failures/errors and one
optional model-weight skip. Lint and debug/test assembly passed. Frozen APKs ran
**143 native methods: 126 passes, 17 opt-in skips, zero failures** across local
discovery/store, announcements, menus, Library/card status, social search/scope,
scheduling, privacy and feed windows. Installed APK hashes match the frozen
qualification files. Actual native business/event, detail-status and Library
screens were inspected. The additional live classes skip without explicit opt-in.
Three deliberately incomplete private-input executions separately rejected the
missing argument before the corresponding lookup; these expected rejections are
not counted as passing regression tests.

The first native batch caught an untouched search-dialog test reading its
submission reference immediately after a pointer action. The test now waits for
callback delivery before making the unchanged payload assertion. Its seven-case
class and the final full batch pass; the first failure is retained in engineering
evidence. No production UI or dispatcher change was made. Six Python checks cover
the private-fixture audit, including encoded matches, private-input path guards,
source-ZIP scanning and exact-hash upstream-notice exemptions. Twenty-two changed
ordinary Kotlin fixture classes retain their original assertion counts. The
privacy-only changes leave all 507 DEX/native/asset members identical to the
routing candidate.

Live Overpass availability was **not** established. Bounded reads through required
Tor with all-traffic Tor off failed at both existing operators using GET and
form POST. In captured traces, TLS completed in roughly 0.8–2.8 seconds, followed
by no response headers before the unchanged 24-second client deadline. Removing
the category's extra regex branches did not resolve those live failures. A
separate intentionally empty-region probe timed out at the first operator and
received HTTP 502 at the second; its response explicitly reported full query
workers and a full queue. Endpoint probing stopped after that capacity response.
These observations do not prove that query cost, Tor readiness or the phone was
the cause. The controlled request/parser tests and a completed diagnostic capture
must not be presented as successful live place retrieval.

Private execution parameters and responses remain outside public source. No new
source host, probe worker, dependency, model, native library, permission or database
migration was added. This is not a phone latency/battery benchmark. Release
promotion requires the matching clean source revision, compatible signing,
unchanged retained preview state before first launch, and a fully hash-verified
anonymous download from the permanent release URL. Older published tags and
assets remain intact; the current fixture cleanup does not erase release history.

## 0.38.38 default-provider representation recovery

Social search, entity discovery and local publisher search share the official
DuckDuckGo HTML/form/Lite reader. A productive head returns without extra work;
a stalled first read can coexist with a later Lite attempt. Three representation
reads per concrete query and a shared two-ordinary/one-Lite reader limit bound
the operation. Local index reads also retain their existing three permits.
Foreground priority begins before slot admission, while initial discovery retains
coexistence with its own deferred detail reads. Query, source, Tor/session,
origin, byte and continuation constraints remain enforced. There is no provider
switch, new package, model, native library, permission, database migration or setting.

Two new native checks ran against the retained byte-verified 0.38.37 debug APK
with the same new test APK. The baseline did not recognize the provider's
recoverable HTML error; its first business-card case also timed out after eight
seconds while the original response was held for twelve. Both checks pass on the
candidate. The candidate's fixture produces business and event cards through the
Lite path while HTML remains held. This is a default-index/UI delivery contract
with an owned publisher stub; existing platform tests separately cover hydration.
Actual native screens were inspected.

A separate two-case JVM counterexample found a stronger 120,000 ms refusal
overwritten by a later 15,024 ms reply. Shared feedback is now synchronized,
visible across workers and monotonic with respect to refusal strength. Worker
completion records real refusals before answer acknowledgment can hide them.
Completed useful material and a pause can coexist. Admission denial is distinct
from a new server failure, preserving observation counts and existing deadlines.
Known query-specific empties are also checked before new representations start.

The full JVM run covered **2,323 cases**, zero failures/errors and one optional
model-weight skip, with Android lint and debug/test assembly passing. A subsequent
**50-case focused run** passed after correcting the live test's pagination layer,
including one added composition fixture. Production APK bytes were unchanged
between these runs. The final frozen native selection ran **124 methods: 122
passes, two opt-in skips, zero failures**. It covers discovery/store, announcements,
menus, Library/saved records, social search/scope, scheduling/privacy and feed windows.
The final installed debug/test APK hashes match the frozen evidence.

The default-provider live router returned ten publisher links in **3,631 ms**
after Tor readiness. A separate Lite check obtained eight supported social
destinations and fifteen more through its actual continuation, in **2,865 ms**
for the two-page operation. The second page stayed on Lite and retained the
selected logical source. The first pagination harness had supplied a raw provider
token to the expanded query-plan interface, which correctly rejected it locally;
the corrected literal-query check and a separate nested-plan fixture exercise
both contracts. No continuation admission was relaxed to make the check pass.

These are dated index/page observations and controlled emulator contracts, not
physical-phone search, battery or general uptime measurements. Captured responses
and private execution inputs remain outside public source. The
[routing record](LOCAL_DISCOVERY_ROUTING.md#default-provider-recovery-03838) describes
representation bounds, protocol references and limitations. Signing, exact
corresponding source, retained preview state and the stable public download
receive the normal release checks.

## 0.38.37 independent publisher-link fallback

The local index router adds a native 4get reader in a separate publisher-link
bank. Native social-search capabilities and continuations are unchanged. The
selected provider, Tor/session/origin enforcement and existing three-attempt,
three-reader limits still apply. An empty or missing LibreY API representation can promote
its retained HTML candidate ahead of unstarted mirrors within that same budget.
Explicit authentication, challenge and server-wait refusals do not retry another
representation. Redirect wrapper decoding now checks provider hostname boundaries.

Research inspected **18 representations across 17 candidate endpoints** through
Android NetworkGateway/Tor, using generic queries. Nine 4get and eight SearXNG
roots were considered; only one supplied usable organic links. Other responses
included authentication/rate/403 refusals, SOCKS failures, challenge HTML and a
JSON API-disabled error with HTTP 200. No unproductive root was activated. The
qualified 4get reader checks `status: ok` and bounded `web` rows, ignores answers,
snippets and single-use pagination tokens, and rejects ambiguous critical fields.
Protocol documentation and the dated scope are in
[local discovery routing](LOCAL_DISCOVERY_ROUTING.md#independent-publisher-links-03837).

Final qualification: **2,297 JVM cases**, zero failures/errors, one optional
model-weight skip; debug/test assembly and Android lint pass. The frozen native
selection ran **122 methods: 120 passes, two opt-in skips, zero failures**.
It covers discovery/store, announcements, menus, saved records/Library migration,
social search/scope, scheduling/privacy and feed windows. Both old and independent
protocol fixtures produce visible business/event cards while the first mirror is
held. The actual native synthetic screens were inspected. Existing cancellation,
source budget, explicit wait and saved-copy checks remain covered.

A separate enabled check through the new production protocol returned ten
publisher links with Tor ready in **2,763 ms**, using an unrelated regional query.
This measures that dated index-link delivery only, not completed factual cards,
phone search latency, battery use or general endpoint availability. Captured
responses remain in external engineering evidence and are reused during analysis.

No dependency, model, native library, permission, database migration or new setting
is added. Signing, source/APK correspondence, preview-state retention and the
permanent download receive the standard release checks.

## 0.38.36 selected index routes and cancelled search packets

A real Gateway/SOCKS regression held the first index mirror and supplied a readable
second mirror. The prior serial implementation failed the 3.5-second delivery
check. Bounded rolling attempts now reach that mirror and can use a freed slot for
a third one. The existing session round-trip check already passed before this
change; it is retained, not presented as a newly discovered bypass.

The local index singleton shares endpoint/Tor learning with SourcePool. Checks
cover cross-query/platform pauses, other-endpoint eligibility, unchanged explicit
deadlines after skipped work and in-flight success, query-specific empties,
cancellation, bounded concurrent readers and source/representation attempts,
HTML fallback, API absence, pre-transport pause admission and redirect-origin
pinning. Strict streamed JSON extraction rejects malformed/excessive structures
and preserves organic links without treating snippets or special answers as facts.

Opt-in Android probes used NetworkGateway and Tor with a generic query, independent
of a reader's area. One existing instance returned ten JSON links in 4,698 bytes of
decoded response text; a separate HTML observation contained 10,709 bytes. Ten
candidate API endpoints were inspected once each, without pagination or retries.
Other observations included HTTP errors, TLS/SOCKS failures, an empty array and a
parked domain returning HTML with HTTP 200. None became a newly active mirror.
Only the observed existing instance receives the qualified API flag. These are
source-dependent observations, not universal uptime or performance guarantees.

The initial 119-method native selection found one cancellation-fixture failure.
Its fixed delay did not establish which side of the query change received the
packet. Explicit gates made that ordering reliable. A further native counterexample
observed the callback actually waiting at `LocalDiscoveryStore.ingest`, changed the
query, then released the store. The pre-fix implementation wrote the stale batch;
the guarded implementation rejects it. A separate dedicated-database check verifies
rollback at the commit boundary, including publisher hints, unchanged factual
revision and protected saved bytes. The three focused checks then passed together.
An earlier JVM fixture failure came from acquiring a client during teardown while
Tor was deliberately unavailable; its production assertions passed. The teardown
and the cancellation test's timeout distinction were corrected.

Final qualification: **2,291 JVM cases**, zero failures/errors, one optional
model-weight skip; debug/test assembly and Android lint pass. The final frozen native selection ran **121 methods: 119 passes, two opt-in
skips, zero failures**.
The native selection covers local discovery and store contracts, announcements,
menus, Library/migration, saved records, social search/scope, privacy, scheduling
and feed windows. Actual synthetic business and event cards were inspected after
JSON index discovery, publisher retrieval and structured extraction through the
owned native SOCKS fixture. No source scripts run.

Two separately enabled methods also passed on the same final frozen APK: the
synthetic accumulated-cache diagnostic and the selected public-index router.
The production router returned ten publisher links with Tor active in one dated
2,292 ms observation. That timing is index-link delivery, not a completed regional
business/event search or a physical-phone benchmark. Identifying response output
stays in the external engineering evidence.

The increment adds no model, dependency, native library, permission, setting or
database migration. Preview-state, signing, exact source/APK correspondence and
permanent-download verification follow the normal publishing procedure. Physical
phone latency, energy and memory distributions remain unmeasured.

## 0.38.35 first business/event cards and accumulated-cache work

A controlled native regression on the previous implementation held two public
publishers open and supplied a readable third publisher. No first card appeared
within the six-second check. A delayed, single additional admission now lets that
third publisher deliver first business and event cards while both older reads are
still held. It does not issue a second request for the same action, shorten
ordinary source timeouts or remove slower valid data. New rescue admissions stop
after a useful deck arrives. Two normal readers and the one rescue slot have
explicit bounds, error, single-execution and cancellation tests.

Initial discovery can also continue beyond early empty publisher batches using
unused capacity within the existing total limit. Native checks exercise both
progressive batches and a combined cached list. Early local transport preparation
overlaps cached-record projection. Gateway, selected-Tor failure, source identity,
geographic/date admission, ownership and saved-record protections remain in place.

The accumulated-cache diagnostic exposed tens of seconds of local work on a
link-only update. Business matching now reuses prepared immutable comparison keys
and compiled lexical patterns. A differential audit against the frozen prior
predicate found **zero differences across 32,400 synthetic business pairs**.
Typed/no-op ingestion avoids decoding and reconciling unrelated record types.
Status-cache updates and publisher queues have independent cleanup/bounds, and
queue offers use transactions. Dedicated native databases verify that source-only
updates preserve unrelated payloads and protected copies, that explicit legacy
repair still works, and that expired publishers can be rediscovered. By-ID reads
enforce expiry before cleanup; an additional native case verifies that an expired
alias cannot restore stale contact facts or alter a protected saved snapshot.

For 1,200 synthetic businesses and 2,500 events, median business reconciliation
changed from **38,132 ms to 548 ms**; median link-only ingestion changed from
**42,684 ms to 0.74 ms**. Each fixture used three samples on the same API 35 emulator
with frozen before/after APKs. These measure those local operations, not complete
search latency or phone hardware. Smaller fixtures and the exact delivery limits
are recorded in [local discovery routing](LOCAL_DISCOVERY_ROUTING.md#first-card-admission-and-accumulated-caches-03835).

Qualification on October 25, 2026: **2,270 core JVM cases**, zero failures/errors,
one optional model-weight skip; debug/test assembly and Android lint pass. The
final frozen native selection ran **118 methods: 116 passes, two opt-in skips,
zero failures**. It includes the new storage contracts and first-deck cases,
local discovery, announcements, menus, Library/migration, social search/scope,
privacy, scheduling and feed windows. A separate enabled synthetic performance
method passed. Actual native first-business and first-event screens were inspected
with invented place names. One earlier fixture demanded exactly nine completed
reads although a useful result overtook a queued empty read; the final checks
cover deterministic streamed batches and bounded combined-list admission instead.

A separately enabled production-controller regional cuisine lookup returned an
eligible card with Tor active. Its reproduction parameters and identifying output
are kept privately. This is one source-dependent observation, not exhaustive
inventory, universal provider uptime or controlled physical-phone qualification.
Physical-phone latency, battery and memory distributions remain unmeasured.

The increment introduces no model, dependency, native library, setting or database
migration. Source/APK correspondence, unchanged signing identity, retained preview
state and permanent-download checks follow the existing publishing procedure.

## 0.38.34 shared native search interpretation

Business discovery now compiles category and cuisine intent into geographic
source predicates and uses the same interpretation for returned-record admission.
The literal business-name path remains available. The custom native layer combines
conservative morphology, complete phrase alternatives, bounded category typo
candidates and 518 compiled iD place concepts. General concept expansion also
serves Library, scoped public feeds, menus and selected public social-index routes.
Cuisine correction remains scoped to businesses: an ordinary topic such as
“space” does not become “spice”, and “Indian Example Coffee” remains a name.
Quoted phrases bypass expansion; negative clauses, hyphenated names, technical
literals, regional boundaries and frozen concrete-query continuations have checks.
See [search interpretation, limits and source notices](SEARCH_INTERPRETATION.md).

Native qualification exposed an important difference from string-only tests:
Android FTS4's implicit-AND/OR behavior did not retain complete alternative paths.
Library now binds independent MATCH expressions and unions their row IDs in SQL.
The original owner grouping, collection, saved/history and result limits remain
in the shared template. Direct wording precedes the separately marked related
bank; retained content stays local and requires no index migration. Source health
calibration has a separate one-literal-request operation, delegated through the
scope wrapper, so reader query expansion does not multiply health probes.

Qualification on October 25, 2026: **2,259 core JVM cases**, zero failures/errors,
one optional model-weight skip; debug/test assembly and Android lint pass. The
final frozen API 35 native selection ran **110 methods: 108 passes, two opt-in
skips, zero failures**. It covers regional discovery, announcements, menus,
Library migration and saved records, social search/scope, privacy, scheduling and
feed windows. Native screens were inspected for cuisine matching and Library
wording/order. The cuisine fixture finds a restaurant whose name omits “Indian”
while excluding unrelated and out-of-area records. The real SQLite fixture checks
quotes, plurals, exclusions and concept alternatives. An earlier menu visibility
assertion passed alone; moving that fixture to the existing v2 Compose clock
was followed by two successful complete selections. Production timeouts were
not increased. Earlier failures and their reruns remain in the private record.

A separately enabled test uses the final frozen Android build's complete
production discovery controller and NetworkGateway. A regional cuisine search
returned an eligible business from its own website, with Tor active, after
approximately 9.3 seconds. Reproduction locations and identifying source details
are retained privately rather than included in the public report. Two
observations reached that first useful card; collection was
then deliberately stopped, so neither establishes exhaustive local coverage.
Earlier direct Overpass attempts returned HTTP 502/timeouts through Tor; the
complete router's alternate first-party path succeeded. External uptime and
cold-start physical-phone latency, battery and memory distributions are not
qualified by these observations.

This increment adds no AI model, native library, dependency, database migration,
background crawl or setting. Vocabulary data is pinned and compiled at build
time; original query paths remain, network alternatives are bounded and private
query memo entries expire. Existing protected sources, Tor failure behavior,
ordinary cache cleanup and saved-record protections retain their boundaries.

## 0.38.33 business and event reading polish

The old detail-sheet gesture was reproduced on the unchanged 0.38.32 debug build:
a top-edge drag moved the business reading viewport by 116 pixels. The fixed
shared viewport passes top and bottom edge gestures for both business and event
details, while preserving Back and pinned Close dismissal. Menus and the native
article reader use the same container. This qualifies the reported opened-panel
case; it is not a claim about every scrollable surface in the app.

Chain cards group compatible brand locations without merging their underlying
records. The native branch selector retains the selected branch's own address
and callable phone. Event actions deduplicate tracking variants, combine a shared
registration/announcement destination and tuck other editions underneath. Source
presentation deduplicates repeated fields and excerpts without deleting stored
provenance. Confirmed unavailable routes do not become primary buttons;
transport/budget/rate failures remain unverified, with deliberate source inspection
available. The existing protected public-copy resolver can recover a readable
edition. These checks are bounded, cached observations; no claim is made that an
external destination cannot fail later.

Initial discovery no longer delays all card enrichment. The scoped gateway
scheduling regression holds a real owned directory request open, delivers a
concurrent deferred detail request, then verifies that actual content reading
still cancels deferred work. Other native fixtures hold the large brand dataset
open while a light contact lookup streams. List cards and opened panels distinguish
Updating, Searching and Ready, and the search itself reports completion or stop.
The projection avoids reconciling both search modes for every background update.

Short event pages with matching explicit structured dates can be read through
the permissionless document worker. Native checks establish a distinct worker
UID, no INTERNET permission, no private sentinel access, bounded factual output
and scratch cleanup. Soft 404s, login pages and unrelated structured records remain
rejected. Prepared announcement reads reuse the existing bounded article bank;
keys retain event, network-session and request-purpose boundaries.

Qualification on October 25, 2026: **2,244 core JVM cases**, zero failures/errors,
one optional model-dependent skip; debug/test assembly and lint pass. The frozen
API 35 native selection ran **94 methods: 92 passes, two opt-in skips, zero
failures**. Skips require captured PMC material or a live Tor observation and do
not qualify those external behaviors. The selection covers local search,
branch/contact streaming, announcements, saved/offline records, menus, article/PDF
reading, parser isolation, source scheduling, privacy and feed revalidation.
Actual native announcement, branch, progress/completion, edge-scroll and PDF
screens were inspected. The initial broader run exposed obsolete label/status
assertions, an expanded lazy-list row requiring scrolling and a PDF timing failure;
the PDF passed standalone, its test adopted the existing v2 Compose clock, and the
corrected complete selection passed. No production timing allowance was increased.

The increment adds no AI model, native library, dependency, setting or database
migration. Ordinary link observations expire; saved records remain protected.
See [delivery limits and implementation](LOCAL_DISCOVERY_ROUTING.md#detail-delivery-and-reading-panels-03833).

## 0.38.32 efficient public feed paths

Two existing Nitter-compatible readers returned HTTPS RSS whose item and media
references used their own HTTP rendering hostname. The decoder discarded those
items and fell back to a larger HTML read. The bounded source/profile/creator/GUID
binding now recognizes that legacy representation, produces original HTTPS X
identities and preserves same-mirror media over HTTPS. Generic HTTP links,
foreign insecure render hosts, credentials, unusual ports and disagreeing
records remain excluded. No HTTP networking is enabled. See the exact
[feed boundaries and dated observations](FEED_COVERAGE.md#secure-legacy-rendering-references-03832).

The new positive regression fails with the unchanged decoder; six focused alias
cases pass with the correction, including hostile alternatives. Corrected direct
production-Gateway reads, with compiled protection and a 1 MiB/read budget,
returned two dated twenty-post NASA RSS windows from each of
`nitter.meowing.monster` and `nitter.mie.foundation`. The windows had no overlap
and used distinct published Min-Id cursors. First bodies were approximately
26 kB and needed one response, replacing the observed RSS-plus-HTML fallback.
These observations establish usable payloads and navigation, not a phone latency
distribution or live-Tor uptime.

`teapawt.net` adds one UNKNOWN fallback entry before permitted originals. Its
operator-listed aliases are not separate independent providers. NASA and
WhiteHouse returned readable RSS heads, but their advertised RSS continuations
repeated both body and cursor. Separate two-window Gateway HTML reads supplied
older posts. Owned JVM and Android regressions verify that existing bounded
catch-up can recover from repeated RSS through a fresh native timeline, preserve
the initial posts and reach the prior window in three reads. No cursor is
transferred between providers or invented from a timestamp.

The audit tool now invokes the production feed adapter rather than appending
`/rss` after Darwin's `#m` fragment. Earlier HTML observations remain valid; their
inference of an RSS redirect is corrected in this record and the source catalog.
Explicit account/operation selectors and response-type, query-name, body-hash and
extraction diagnostics distinguish parser fallback from actual RSS behavior.
The audit retains counts rather than response bodies or raw public cursors.

Qualification on October 25, 2026: **2,232 core JVM cases**, zero failures/errors
and one optional model-dependent skip; debug/test assembly and lint pass. The
frozen API 35 native selection ran **84 methods**: **83 passes**, one opt-in live
Tor check skipped, zero failures. This includes native RSS continuation and loop
recovery, strict sessions and redirects, feed history/coverage/revalidation,
source scheduling/heartbeat, privacy, reading windows/memory, saved public-source
cards and business detail streaming. Actual native gallery and business-detail
screens were inspected. A separate enabled public-source check on the same
frozen Android build returned twenty dated NASA posts directly from
`nitter.mie.foundation/NASA/rss` and twenty-one dated WhiteHouse posts from
`teapawt.net/WhiteHouse/rss`, retaining original X identities and HTTPS media.
This is source-dependent public behavior, not an independent security audit.

The increment adds no model, dependency, native engine, setting, database
migration or background polling job. The existing business/social bridge remains
in the qualified build. Signing, source correspondence, private preview-state
backup and the permanent public APK alias follow the existing release procedure.

## 0.38.31 business and social bridges

Business search retains published social/contact fields across Overture, OSM and
AllThePlaces, including supported handles, numeric Facebook pages and account
URLs placed in generic website fields. Existing cached records receive that
classification too, without deleting their original evidence. Scoped MemberZone
contact blocks supply member accounts, hours and visiting directions. Identical
repeated postal addresses no longer erase a typed business's location; different
addresses remain unresolved. A captured public Coffee House 1420 page returned
no address, hours or accounts with 0.38.30; the corrected replay retains its
published Vero Beach address, hours and two accounts, excluding chamber accounts.
See [business/social boundaries](BUSINESS_SOCIAL_BRIDGES.md).

The existing background profile checks now retain useful business information
from the read they already perform. Named local identity and contact/location
corroboration gate branch hours, phones, email and menu routes. A shared phone
alone cannot turn a person's profile into the business. Linked accounts remain
explicitly unmatched until their returned profile qualifies. Brand, operator,
owner and source-publisher relationships remain distinct. Qualified published
profile hours can replace older directory hours. Menus enter the existing
branch-aware reader; no model call, dependency or native runtime is added.

The first 226-method Android batch exposed one genuine scheduling regression:
putting social reads behind the shared detail permits delayed them while two
slow supplemental tasks held those permits. Business profile checks now retain
their separate two slots and stream their extracted data before the heavy work
finishes. Focused native checks pass, including one read per account and three
platforms. Availability and profile-projection receipts are separate: a previous
positive check keeps its icon while new facts load once; negative rests and saved
copies remain intact. Unbound social search hits retain their ordinary path into
website discovery, rather than being mistaken for already-known accounts. Two unrelated UI checks passed in isolation; their fixture
synchronization now waits for actual keyboard/sheet closure rather than assuming
Android window animations end synchronously. Production UI timing and route
deadlines were not widened.

Expanded qualification also encountered the old Compose test factory resuming a
frame on an image worker thread. The installed Compose 1.12.1 already supplies
its v2 factory/standard dispatcher; the affected feed and discovery fixtures use
that factory without changing production image dispatch or dependencies. One
interrupted fixture had left a generated subscription and two posts behind.
Setup removes only this fixture's generated subscriptions and their bound rows
on the explicitly isolated device, after a private state backup. Caption checks
now preserve exact signal and stored originals while allowing the proper-name
bank to replace VP with its resolved display name. The test-only streaming
backend explicitly overrides the streaming method, avoiding Kotlin delegation
silently calling its delegate's empty discovery method. The focused five-case
rerun and old-positive-cache journey pass. See the
[official Compose test migration](https://developer.android.com/develop/ui/compose/testing/migrate-v2).
The final complete selection is recorded below.

The final suite completes 2,229 JVM cases: zero failures/errors and one optional
model-weight skip. The frozen API 35 selection completes 229 Android methods:
227 pass, two opt-in live-search/Tor checks skip, zero failures. Native frames
show a first card while account reads are held, an updating indicator, then the
completed email/hours/account details. Contact actions, menus, saved-copy
protection, migration, filtering, search and feed/collection regressions pass.

Two enabled live Gateway checks confirm scoped MemberZone extraction and
first-party website social connections. The tested brewery's Instagram route
returns a login requirement and its Facebook route fails selected-profile
qualification; both remain unavailable. Published associations are retained,
not falsely advertised as working accountless content. The equivalent real
member-page replay also passes. These observations do not establish permanent
availability of any provider or physical-phone latency/battery measurements.

Release assembly and lint pass. The update retains application ID and signing
identity. Preview state is backed up before installation; versioned APK/source
artifacts and the fixed public download asset are retained.

## 0.38.30 attachment collections and source-bound retweets

The White House gallery/member case exposed a colon-versus-layout-pointer caption
variation and several CDN addresses for the same Meta objects. A narrow caption
comparison and source-specific image identities collect those editions without
changing request URLs or original fields. The renderer retains discarded copies'
alias evidence within one collection and requires compatibility with every known
pixel observation. A near JPEG cannot transitively swallow a different edit.
Gallery containment also respects detailed contradictory observations. No new
model, runtime, dependency, database schema or automatic request is added.

The NASA example additionally exposed a Nitter rendering hostname different from
the fetched origin and an alternate retweet heading. The parser binds that item
to an agreeing selected feed/channel, creator and numeric GUID before retaining
its original X URL; it grants no global hostname alias or fetch permission.
Account-reference-only variants require independently corroborated media and
preserve every different reference in the source-attributed composition. The
normal quantity, negation, destination and ambiguity guards remain active.
See [attachment evidence and boundaries](ATTACHMENT_COLLECTIONS.md).

Eight initial regressions fail four cases against unchanged 0.38.29. The final
JVM selection completes 2,219 cases with zero failures/errors and one optional
model-weight skip. An extra direct phrase-aligner assertion was corrected to
exercise the complete grouping boundary, which owns destination admission;
the end-to-end changed-URL check passes without removing any admission guard.
An early research harness requested more than Gateway's allowed eight operations;
its setup failed before I/O and was corrected to the existing limit. Production
request policy was unchanged.

The frozen API 35 Android selection completes 178 methods: 176 pass, two explicit
live-search/Tor checks skip, zero failures. New cases cover qualified CDN copies
without decoding, retained alias bridges, contradictory pixels and a real combined
card/gallery/comment journey. The viewer check waits for the decoded image before
capturing evidence. Native screens show two pictures and both original streams;
raw originals remain unchanged. Debug, instrumentation and release assembly plus
release lint pass.

The actual retained White House replay changes three editions/two cards into one
card with five pictures and three originals, with zero Gateway requests. A separate
NASA replay collects its two editions; the bounded image check retains five images
from eight attachments, including an unqualified low-information pair. Physical-phone
frame-time, energy and exhaustive source coverage are not claimed by these emulator
and captured-source checks. Evidence remains external under
`evidence/gallery-members-20261025`.

## 0.38.29 native public Threads keyword search

The original fallback now reads Threads' ordinary public keyword-search HTML
through the existing Gateway. The decoder requires the exact selected/final
query and origin, anonymous actor-zero preload metadata and one explicit search
connection. Recommendations cannot become results. Supplied captions, dates,
names, links, galleries and evidenced thread relationships use the existing
post decoder. Matching post authors do not become matching directory accounts.
No model, browser, script execution, account state, dependency, native engine or
storage-format field is added. Darwin's alternatives and selected Tor controls
remain in place. See [search protocol and limits](THREADS_PUBLIC_SEARCH.md).

Three bounded public-page reads exposed an important reference limitation:
the advertised recent/profile selectors did not establish those operations.
The first two returned identical 18-post windows; the source metadata declared
ordinary search. A third supplied 20 result threads but still declared
`recent: 0`. Compiled-filter and URL-cleaner checks preserve the investigated
functional parameter. Those selectors remain unqualified and are rejected by
this reader before I/O. The app orders the limited returned window by actual
publication time and adds the compact **Limited public search results** notice.
There is no invented cursor, account-directory claim or preview-quota renewal.

Four new core cases fail on unchanged 0.38.28, and its owned native search fails
with the old unsupported-search error. The final nine search cases, twelve
existing Threads profile/reply cases and two routing-deadline cases pass. Coverage includes query/origin and
anonymous metadata binding, literal braces/quotes, contradictory qualifications,
recommendation exclusion, empty/oversized records, complete galleries, parent
evidence, one real Gateway GET, absent credentials and zero requests for unsupported
selectors/scopes/cursors. An initial candidate expectation of an empty Page was
corrected to retain Extraction's existing EMPTY failure contract; production
empty-source admission was not weakened.

Two direct multiword-query observations return 19 usable posts with either
form-space or percent-space encoding. A valid page took 4,630 ms, beyond the
generic original shortcut. Controlled 4,500-ms delivery reproduces this discard
on the old four-second budget and passes with eight seconds direct/sixteen
seconds through Tor for Threads keyword search only. A Reddit control retains
its four-second timeout. No broad route timeout or encoding change is made. The final frozen candidate
also returns a live 19-post multiword window in 4,935 ms with zero unavailable
entries and a healthy original route.

The full JVM suite completes 2,200 cases with zero failures/errors and one
optional model-weight skip. The API 35 frozen Android selection completes 174
methods: 172 pass, two explicit live-search/Tor checks skip, zero failures.
It covers the existing feed/fusion, reading-memory, history/coverage, privacy,
discussion, names, Context and filter cases plus all native search-feed, scope
and directory-shelf regressions. The new native fixture uses the complete app
frame, retains chronological cards after one GET and has no invented people or
more pages. Its screen was inspected. Debug, instrumentation and release assembly
plus release lint pass.

A separate live production-pool/SearchFeed comparison uses fresh routing state,
the same query and identical first-window work limits. After Tor readiness,
the baseline obtains one full post in 8,436 ms; the candidate obtains 19 in
4,006 ms, including 19 additional keys in that pair. This single changing-source
sample is not a latency distribution, complete search coverage or a phone
performance claim. Raw responses, frozen APKs, results and native evidence
remain external under `evidence/threads-search-20261025`.

## 0.38.28 faster reading-signature preparation

Native sampling of the retained-feed workload found repeated whitespace matching
and per-byte formatted hex in reading-signature preparation. A direct whitespace
scanner and hex writer retain the existing normalization, framing, references,
media identities and matching rules. No network request, model, dependency,
native runtime, retained text cache or storage-format change is added.

Three process-restarted untraced native samples per APK use the same private
517-post, 24-source snapshot. Median observation preparation changes from 1.242
to 0.742 seconds, 40.3% less time. All six runs preserve the same 468 ordered
collections and all observation signatures/timestamps. A paired native feed
benchmark preserves its 120 cards; its swipe time remains similar. These are
local emulator measurements, not an improved phone-FPS or complete cold-launch
claim. See [performance evidence](FEED_PERFORMANCE.md).

The full JVM suite completes 2,189 cases with zero failures/errors and one
optional model-weight skip. Exhaustive Unicode comparison covers all 1,114,112
codepoint values, including surrogate integers, on desktop Java and through the
existing signature recipe on Android API 35. Mixed runs, word boundaries,
non-whitespace controls and fixed persisted signatures also pass. The helper
preserves the runtime's own NEXT LINE behavior rather than assuming desktop
and Android regex defaults agree. Debug, instrumentation and release assembly
plus release lint pass.

The initial 147-method Android batch passes 145, skips one opt-in live-Tor check
and fails the originals-sheet global Gateway-counter assertion by one. Temporary
diagnostics on both baseline and candidate identify an unrelated, query-free
DuckDuckGo favicon warmup; the originals-sheet case itself records no checks.
The fixture now stops that independent preparation and its Tor controller on
Main before pinning both offline policies. It also delegates real visible name
leases through a spy, requiring a nonempty observation and local-only permission
for every lease. Lookup features, the zero-request assertion, complete-caption
checks and chosen-thread checks remain enabled. Cleanup reapplies restored name
preferences. Production preloading is unchanged; temporary filter reflection
is retained only in external diagnostic evidence, outside the final source.

One later broad invocation omitted the isolated-device flag and skipped twelve
cases; it is retained but excluded from qualification. The correctly flagged
147-method batch passes 146 with one explicit live-Tor skip and zero failures,
and the final frozen-test build repeats that result after cleanup refinement.
Coverage includes image/post fusion, reading memory, history/coverage, connection
controls, both discussion suites, all 23 name-store cases, seven Context
persistence cases, Unicode parity, caption details and filter UI. Actual native
feed and originals-sheet screens were inspected. Raw logs, traces, frozen APKs,
private inputs and screenshots remain outside release assets under
`evidence/reading-preparation-20261025`.

The experimental browser-persona runtime remains excluded. No fingerprint-based
preview renewal, additional live-source access or physical-phone performance
is qualified by this optimization.

## 0.38.27 source-bound Threads replies and complete galleries

The existing public original-page fallback now reads explicit selected Relay
connections, retains complete supplied galleries and offers the page's actual
reply snapshot to native All/Threads discussions and the existing Context
consumer. It excludes related-post recommendations. Numeric IDs bind streamed
patches; selected URL/code/author checks preserve the earlier sparse single-post
JSON form without inventing IDs. Source dates, counts, links, media and public
names keep their original attribution. No model, runtime, dependency, database
schema or extra page-discovery request was added.

Two bounded Android Gateway reads obtained five dated NASA profile posts and a
selected post page. The old parser lost three images from a four-image gallery
and flattened five recommendations into the post collection without reply
relationships. Replay now retains all four images and ten supplied replies,
including a proved nested parent, while excluding those recommendations. The
source reported 27 replies; this is a partial window. A separate first profile
continuation returned 403 and stopped. No continuation, search, complete reply
coverage or fingerprint-based preview renewal is claimed. See the
[reader and protocol record](THREADS_PUBLIC_READER.md).

Five initial parser regressions failed on unchanged 0.38.26. Its owned native
comment-entry check also failed; the candidate opens replies from the actual
card button, keeps All as the default, and switches to Threads without another
request. The full JVM run completed 2,185 cases, zero failures/errors, one
optional model-weight skip. Its initial run found one video-duration compatibility
regression, fixed by preserving the selected sparse JSON form; the old assertion
was retained. Debug/test/release assembly and release lint pass.

The first 125-method native batch passed 123 methods, skipped one opt-in live
Tor check and failed the originals-sheet global Gateway-counter assertion by
one. That fixture now cancels the ordinary activity reader and clears only its
temporary restoration targets before creating its injected reader; lookup
features and the zero-request assertion remain intact. The isolated assertion
passes on unchanged 0.38.26. The first live gallery capture showed the four-image
counter but still displayed a loading indicator; the refined harness waits for
the decoded image rather than accepting that screen. Both initial observations
and the final qualification stay external under `evidence/threads-public-20261025`.

The next 126-method batch passed 124 methods, skipped the same live-Tor check
and failed the draft-collapse assertion immediately after an IME Done action.
That one test now invokes the minimize button's actual accessibility click,
avoiding coordinates that can move during native keyboard insets; the collapse,
retained-draft and reopen assertions remain unchanged. The focused check and the
final full 126-method batch pass: 125 passes, one opt-in live-Tor skip, zero
failures. The latter covers feed/image fusion, saved editions, reading memory,
history/coverage, connection controls, both discussion suites, all 23 name-store
methods and seven Context persistence checks. It also includes the owned card
entry and a live original-page four-image gallery. Actual native reply and
decoded-image screens were inspected. No production composer behavior was
changed to obtain these harness results. Emulator measurements do not establish
physical-phone performance or live Tor provider availability.

## 0.38.26 bounded URL work reuse during grouping

The fusion workspace reuses normalization of an exact URL input during one
complete grouping operation. It shares the existing estimated 8 MiB budget and
uses a 2,048-entry map cap; nested work shares it and independent threads do not.
Return, failure and cancellation release it. The resolver, matching thresholds,
original editions and contradiction guards are unchanged. No network request,
model, runtime or persistent reader-state field was added.

Three untraced process-restarted native stage runs per APK over the same private
517-post snapshot changed median grouping from 6.841 to 6.116 seconds, 10.6% less
time. Every run retained the same 468 ordered groups and membership/order digest.
These are local-stage emulator measurements, not full cold-launch or phone
performance. [Performance qualification](FEED_PERFORMANCE.md) gives the workload
and remaining limits. Raw evidence stays external under
`evidence/feed-url-work-20261025`.

The full JVM suite completed 2,173 cases, zero failures/errors, one optional
model-weight skip. The 77-method Android batch passed 76 methods with one
opt-in live-Tor skip and zero failures. It covers merged images/cards, saved
editions, reading memory, history/coverage and connection controls. Debug, test
and release assembly plus release lint pass. Unit checks exercise exact URL
spelling, null reuse, nested ownership, failure cleanup, shared memory bounds
and concurrent reader isolation. Actual native merged-caption and attributed
source-context screens were inspected.

The research-only child-comment probe can select a source-reported document ID
and true/null chronological input. Its separate live attempt returned an explicit
rate limit on the first child read and stopped; no new child capability or
fingerprint-based preview renewal is qualified. That negative result and its
source references are retained under
`evidence/instagram-child-query-leads-20261025` and described in
[query research](INSTAGRAM_QUERY_RECOVERY.md#later-child-query-lead-october-25).

## 0.38.25 bounded Instagram query recovery and reuse

The public reader recognizes the observed missing-document error only when every
error names the exact requested query ID. Its existing one-page bootstrap and
single retry remain bounded. After usable selected content passes validation,
the adapter can reuse the proven query metadata for ten minutes within the same
source, target, input shape and actual Gateway session. Each fresh read still
fetches a new response. No content, permission or authentication is cached by
this bank. [Query recovery](INSTAGRAM_QUERY_RECOVERY.md) records its admission,
expiry, connection-change and failed-payload checks.

The unchanged 0.38.24 app failed the owned native missing-document fixture.
The candidate displays a recovered reply in All, then its actual Instagram
Refresh control displays a different reply. The first read costs three requests;
the next costs one, versus repeating three before the bank. All four fixture
requests omit cookies and authorization. The screen was inspected.

The full JVM suite completed 2,171 cases, zero failures/errors, one optional
model-weight skip. The 77-method Android regression passed 76 methods with one
opt-in live-Tor skip and zero failures. Debug/test/release assembly and release
lint pass. A separate live API 35 public-provider journey passed: All displayed
14 Instagram comments and the native More button expanded them to 26 distinct
comments. Its native screen was inspected. This is one dated direct-transport
observation, not complete reply coverage or a provider availability guarantee.

Separate child-reply research found a stale query ID and a public REST path that
returned an unusable HTML shell. Neither was registered as a working route.
Fingerprint-based preview allowance renewal remains unqualified and its native
experiment remains excluded from normal builds. Raw research, baseline failures
and final checks stay external under `evidence/instagram-child-comments-20261025`.

## 0.38.24 faster cached-media restoration and gallery grouping

The image-comparison rebuild skips its artificial wait when a local media file
is absent, while retaining the full scan bound and decoder throttling after
cached-file work. Gallery matching applies its existing cheap time/media
exclusions before its existing original-identity proofs. No matching threshold,
source window, network policy, runtime or dependency was changed.

On the same API 35 emulator, the native late-cache-pair case changed from 9.604
to 1.957 seconds. Three CPU-stage samples per APK over the same private 517-post
snapshot changed median grouping from 8.155 to 6.732 seconds, with identical
468 ordered groups in all six runs. These are scoped local-stage measurements;
the complete cold-reader delay remains under investigation. See
[performance qualification](FEED_PERFORMANCE.md).

The full JVM suite completed 2,159 cases, zero failures/errors, one optional
model-weight skip. The final 76-method Android batch passed 75 methods with one
opt-in live-Tor skip and zero failures. It covers images, cross-post and fusion
cards, saved editions, all reading-memory journeys, source-window invalidation,
history/coverage and connection controls. The private CPU diagnostic passed
separately. Debug/test/release assembly and release lint pass; actual native
fusion and retained-anchor screens were inspected. All 33 native-library ZIP
members remain identical to 0.38.23.

A supplementary history fixture initially equated completed pages with request
starts. Its record contained the initial request plus three permitted recovery
starts, both recovered roots and the pending state, but only three
completed pages. A raced/cancelled route consumes an allowance without returning
a page. The fixture now allows two to four completed pages while retaining its
exact request-count/later-source assertions and requiring the exact recovered
post set. Production history/routing code is unchanged. Intermediate failures
and corrected final evidence remain external under
`evidence/cached-feed-profile-20261025`.

## 0.38.23 faster complete grouping and quiet refresh

The existing conflicting-context check now precedes repeated shared-prose
analysis in fusion alignment; every admission and contradiction guard remains
in force. In the same native 240-post fixture, median grouping changed from
6.830 to 2.710 seconds and first merged-card display from 8.765 to 4.196 seconds,
with identical 120 groups and initial/scrolled card identities. Scrolling time
and sampled frame durations did not improve. [Performance qualification](FEED_PERFORMANCE.md)
records the workload, prior baseline, diagnostics and limits; these are not
physical-phone or cold-process measurements.

Background source checks use the fixed feed toolbar status. They no longer pin
an animated pull indicator over cached cards. Pull gestures retain their arrow
and existing overlap guard. Explicit refresh cancellation is reachable in Feed
options. The new native regression fails against unchanged 0.38.22 on its one
floating indicator, then verifies an uncovered card, unchanged anchor/toolbar,
visible updating status and a cancelled source remaining pending.

The full JVM suite completed 2,159 cases, zero failures/errors, one optional
model-weight skip. The 122-method Android selection passed 120 methods with two
opt-in live-Tor/NASA-neighborhood skips and zero failures. All 23 reading-memory
journeys and both source-window invalidation checks are included alongside the
preceding release's 97-method selection. The native benchmark passed separately;
assembly and release lint pass. Actual native benchmark and refresh screens
were inspected. Raw and intermediate evidence remains external under
`evidence/feed-startup-20261025`.

## 0.38.22 source-bound Instagram authors

Public grid previews, full posts and replies retain their literal source account
behind the display name. A failing baseline reproduced two identical display
names losing their distinct handles. The reader now preserves that binding in
author links, original-account attribution, captured copies and archive
snapshots, and reuses it in the existing bounded participant graph. Supplied
selected-post names also seed the existing local-name bank. There is no new
acquisition request, dependency or native runtime. The source declaration is not
independent evidence of common control across sites.

The JVM suite completed **2,159 cases**, zero failures/errors and one optional
model-weight skip. The final API 35 Android batch covered **97 named methods**:
95 passed, two opt-in live-Tor/NASA-neighborhood checks skipped, zero failures.
It includes actual public Instagram discussion/history buttons, all nine
subscription journeys, feed coverage/history, source-pause and connection
controls, name storage/presentation, saved-state validation and participant
discovery. Native screens confirmed two identical displayed names opening
different authentic accounts. Release assembly and lint pass.

Earlier mixed runs hit an existing subscription-dialog setup timeout; the
standalone nine-method group passed, the prior 0.38.21 prefix passed, and candidate
prefixes reproduced the timeout. A synchronized idle callback alone did not
resolve it. The subscription test group now uses the installed Compose v2 test
environment, following Android's [coroutine-scheduling migration guidance](https://developer.android.com/develop/ui/compose/testing/migrate-v2).
All assertions and shipping subscription code remain unchanged. The complete
97-method batch then passed as above. Failed logs, comparison and intermediate
results remain separate under external `evidence/author-identity-20261025`; the
precise earlier frame interleaving is not established.

Old saved records may omit the optional binding. An old snapshot without it
never inherits a current author, and imports reject malformed account fields
before writing reader state. All 33 native APK library members remain identical
to 0.38.21. These checks do not qualify complete Instagram history, physical-phone
performance or fingerprint-based preview allowance renewal.

## 0.38.19 on-demand public profile history

Ordinary Instagram browsing can continue a successful finite alternate snapshot
through the qualified public paged reader. The initial read adds no history
request. In the live API 35 routing test, RSS-Bridge supplied six dated NASA
posts, then two continuations expanded the same page to twelve and twenty-four
unique posts in 1,067 and 479 ms. A separate native test pressed the actual
Load more button twice and observed the same 6 → 12 → 24 progression.

A new regression first failed on unchanged 0.38.18: its successful finite head
provided no continuation. The candidate preserves that one-request initial
control. Cursor ownership is stored separately from card provenance, survives
ordinary page caching, and remains registry-, target-, policy- and session-bound.
Known windows are skipped in bounded batches; duplicate-only checkpoints can
advance without inventing new cards or certifying empty content as healthy.
Dated subscription recovery keeps its separate operation-local scope and rules.

The full JVM suite passed 2,140 cases with zero failures/errors and one optional
skip. Across sixteen unique native methods, fifteen passed and one opt-in
live-platform check skipped. The owned cache/card and live profile grid screens
were inspected. [Profile history](PROFILE_HISTORY.md) documents the exact checks,
first harness failure, public-source and transport limits. External evidence is
retained separately under `evidence/profile-history-20261025`.

## 0.38.18 public Instagram pagination and selected full media

The new ordinary-build public reader returned 36 distinct NASA grid previews
across three pages on API 35, including 24 IDs beyond the initial twelve-item
window. SourcePool's selected-source continuation used the actual issued
cursor. Selected-post queries returned a real publication time, playable video
and a complete three-image gallery. The source's simpler public-App-ID profile
endpoint remained login-gated and URL cursor attempts repeated the head; those
are recorded failures, not additional healthy paths. The native fingerprint
experiment remains excluded from normal APKs.

The full JVM regression passed 2,130 cases, zero failures/errors, one optional
weight-dependent skip. The API 35 native batch covered 18 named checks: twelve
passed and six opt-in native/live-provider experiments skipped. The actual native NASA gallery screen was also inspected
after opening its URL through the reader search sheet. Active checks
included four public acquisition/paging/media probes, native platform cards,
public-pathway displays and feed revalidation. The new protocol's live Tor and
physical-phone performance remain unqualified. Grid previews do not contain
source dates and do not gain FEED capability or move a caught-up boundary.

The implementation and evidence limits are documented in
[Instagram public paging](INSTAGRAM_PUBLIC_PAGING.md). External payloads, frozen
APK/source hashes, state backups and logs remain under
`evidence/public-preview-paging-20261024`, outside public source and artifacts.

## 0.38.17 feed-header navigation and additional live alternatives

The full JVM run covered **2,090 cases** across 179 suites, zero failures/errors
and one optional weight-dependent skip. Debug, release and instrumentation
assembly and release lint passed. The final API 35 Android batch accounted for
**118 named checks** across sixteen classes: 116 passed and two opt-in
live-source/Tor checks were skipped. Mixed RSS/social channel, pending-refresh
and restored-position native screens were inspected. A separate reading-scope
rerun overlaps this batch and is not an additional unique check.

Two new JVM regressions first failed on unchanged 0.38.16: the published Nitter
RSS header cursor was lost, and the collector could not use it to bridge its
previous reading window. The initial/overlapping control passed. The identical
final native test APK, SHA-256
`ff078bf234d6759c9123802e1fd19d590c4e81bcc40841547f227a04977335a3`,
failed its substantive missing-history assertion on byte-verified retained
0.38.16 debug. That baseline retained one useful post and a pending interval
after an RSS head and unavailable HTML read. The candidate joins both roots
through two RSS reads, preserves separate FEED/TIMELINE health, and never asks
for the HTML head. Initial/overlapping native controls still use one request.

The header is opaque data, escaped as one parameter for the same source root,
account/list operation and issuing session. Cursor requests are pinned to their
exact selected address through cleaning, filter rewrites and redirects. A local
epoch binds the bookmark across dispatch; it is never sent over HTTP. Another
Gateway, restart or direct/Tor/direct round trip requires a fresh head. Invalid
local bookmarks do not train source outages. Parsed XML and mutable navigation
are separate fields in the existing bounded feed memory: an identical new 200
body can change or remove its header, while an exact validated 304 can retain an
omitted header. Ambiguous supplied headers cannot grant navigation.

Twenty new header unit cases cover these bindings, supported account tabs and
numeric lists, malformed/duplicate/oversized headers and JSON, current-header
replacement/removal, 304/no-store behavior, cross-account redirects, filter
rewrites, mode changes and the decode-to-IO race. An additional owned SOCKS
case observes two full-body heads and a header continuation, remote hostname
resolution, one head parse, the fresh cursor, and no validators, cookies,
credentials or local epoch in requests. This is selected-transport fixture
qualification, not a live Tor exit observation. Four new native cases cover
joining history, cheap initial/overlapping heads, stale-session cancellation
and redirect rejection. The broader batch retains search, scheduling, feed
coverage/revalidation, saved RSS, cross-posts, atomic publication and anchors.

Production-Gateway direct audits inspected seventeen distinct selected HTTPS
roots with compiled protection, at most two pages/source and 1 MiB/read. Failed
or refused reads stopped rather than immediately retrying. Several old and new
maintainer-listed deployments refused, failed DNS, exceeded this audit budget
or returned HTML. A later audit correction found `/rss` had been appended after
Darwin's `#m` fragment; those old reads do not establish an RSS redirect or lack
of RSS support. See the corrected [0.38.32 feed qualification](FEED_COVERAGE.md#secure-legacy-rendering-references-03832).
The maintainer-listed Shitter deployments
`https://nitter.meowing.monster` and `https://nitter.mie.foundation` each supplied
two dated twenty-post NASA HTML windows with no overlap. Both are now
Nitter-compatible runtime alternatives, initially UNKNOWN, with the existing
content checks, learned health and bounded routing. They are not claimed to
have independent upstreams, universal target coverage or qualified live Tor.

The qualified APK grows **5,920 bytes** over 0.38.16. All **496** other
non-generated native/data members are unchanged; the source-pool JSON gains
the two alternatives. No dependency, model, runtime pack, Page field, migration,
setting or polling job is added. Disposable realistic browser personas are
separately documented research, not a capability of this release. See
[header navigation](FEED_COVERAGE.md#rss-header-navigation-03817),
[feed reuse](FEED_REVALIDATION.md) and the
[public-read research](DISCOVERY_SOURCES.md#public-read-request-personas-research-path).

## 0.38.16 bounded native history recovery

The final full JVM suite covered **2,069 cases**, zero failures/errors and one
optional weight-dependent skip. Debug, release and instrumentation assembly and
release lint passed. The final API 35 Android batch accounted for **114 named
checks** across fifteen classes: 112 passed and two opt-in live-source/Tor checks
were skipped. Native mixed RSS/social channel and pending-refresh screens were
inspected. The restored reading-scope case also passed separately; that duplicate
run is not an additional unique check.

Two new regressions first failed on unchanged 0.38.15: a useful finite RSS head
could not use its source's HTML timeline, or another native source, to bridge
the retained previous head. The identical final native test APK failed against
byte-verified retained 0.38.15 debug at its substantive missing-history assertion.
It had one new post and one RSS request, with the gap pending. On the candidate,
the real timeline read joins three roots in two requests while retaining the
initial post's fuller caption, image and provenance. FEED and TIMELINE observations
remain separate. Initial/overlapping native controls still make one cheap read.

An additional regression failed before qualifying Sotwe's account response as
finite. Its adapter explicitly lacks an admitted older-page operation; that
window now carries `limitedTimeline`. Recovery does not repeat the same API when
`feed()` already uses `fetch()`. Generic adapters can expose a separate cheaper
RSS representation and a native timeline. A further control confirmed that an
HTML head reached after RSS failure is not retried after its cursor fails: the
existing timeline cooldown already advances to the other method.

The collector owns an ephemeral pool/target-bound history scope. It can admit
three additional native adapter starts, including cancelled hedges, within the
existing eight-page/300-post/45-second-direct or 90-second-full-Tor allowance.
Alternatives precede a permitted original; a continuation cannot widen initial
original access. Real cursors remain with their issuer. No copied/saved Page can
carry recovery authority. Failed/repeated cursors and explicit continuation
limits can recover, while unbridged cursorless windows still remain pending.
Completed posts survive owned deadlines; caller cancellation and initial privacy
policy changes still cancel the complete operation. Recovery keeps primary
canonical copies and never substitutes an archive, index or undated timeline
for proof of a fresh joined window. Ordinary RSS navigation is unchanged.

Unit controls exercise bounds, richer-copy retention, genuine cursor I/O through
an explicitly supplied owned loopback cursor, original tiers, cooldowns, undated
responses, policy round trips, cancellation and deadlines. Production HTML still
requires HTTPS pagination; an initial HTTP-link fixture was repaired rather than
weakening that rule. Early fixture-construction/canonical-key errors were fixed
before the substantive baseline. The new coroutine boundary exposed a changed
cancellation exception identity; preserving the original cancellation fixed that
regression without weakening its assertion. Intermediate reports are retained.

The three new native cases exercise actual Gateway/parser/collector operations,
cheap initial/overlapping heads and an unbridged six-source pool that stops after
three additional starts. The broader batch covers source scheduling, feed
revalidation, RSS/saved text, cross-posts, search scopes, persisted gaps, atomic
publication, channels and reading anchors. These owned fixtures and emulator
checks do not establish new public-source uptime, live-Tor or phone performance.

The candidate APK adds **6,504 bytes**, with all **497** non-generated native/data
members unchanged. No dependency, model, runtime pack, Page field, database
migration, setting or polling job is added. See
[feed recovery](FEED_COVERAGE.md#native-feed-recovery-03816) for implementation and
the separately labelled Nitter response-header research lead. It is not an
enabled header-continuation adapter in this release.

## 0.38.15 feed-window coverage and published history routes

The full JVM suite covered **2,053 cases**, zero failures/errors and one optional
weight-dependent skip. Debug, release and instrumentation assembly and release
lint passed. The final API 35 Android batch accounted for **111 named checks**
across fourteen classes: 109 passed and two opt-in live-source/Tor checks were
skipped. Native mixed RSS/social, pending-source and restored reading-position
screens were inspected. The reading-scope case also passed separately.

Four regressions first failed on unchanged 0.38.14: ordinary sliding RSS, generic
social RSS and an RSSHub snapshot could close a missing previous window, and
ordinary RSS navigation was discarded. Candidate checks cover RSS-Bridge too.
The identical final native test APK failed on byte-verified retained 0.38.14
debug because its readable finite relay window incorrectly closed missing
history. The candidate retains the post, makes one owned request and stays
pending. Other native checks cover Atom continuation, logical subscription
identity, qualified Blogger reads, unavailable-Tor rejection, persisted gaps,
deadline retention, atomic publication, source removal, channels and anchors.

Additional tests found query-only Java URI resolution dropping the filename,
an unrecognized exact account-deletion path, and HTML-style case folding
confusing XML namespace declarations. The existing HTTP URL resolver preserves
the filename; supplemental policy recognizes the scoped account action while
keeping documentation readable. Namespace lookup now preserves case and empty
undeclarations. Complete-feed markers must be feed-level, properly namespaced,
empty and untruncated. Valid uppercase prefixes remain supported.

Live research found a custom-domain feed publishing an HTTP Blogger next link
with its exact Atom blog ID. A new regression failed on that unhandled link.
The qualified route uses only the matching known HTTPS feed operation, bounds
and validates parameters, pins its provider origin, and verifies the returned
publication ID before extracting posts. Wrong/missing identities, other
operations, unsafe origins and legacy Pages without that identity cannot grant
the route. The optional bounded `Page.syndicationId` adds no database migration
or account-ownership assertion. Ordinary pages retain their existing origin rules.

An October 24 production-Gateway audit read 25 posts from Righto's custom
domain and 25 different posts through its published Blogger endpoint over HTTPS.
Both retained the original subscription and exact blog ID. An earlier direct
provider audit also read two pages successfully. Android's old Blogger feed
redirected to its replacement and supplied a finite twenty-post window; another
old Blogger source hit the insecure-destination guard and was not retried.
These are dated target-specific observations, not full-history, live-Tor or phone
performance qualification. No article bodies were saved as audit evidence.

The initial broader native batch had 107 passes, two opt-in skips and one
reading-scope fixture failure, repeated alone. A composition wait alone did not
repair it. The captured screen showed Reddit selected while the fixture assumed
X/Bluesky without enabling them. Explicit fixture-platform selection, retained
composition gates and unchanged group/anchor assertions pass in the final
focused and full runs. Production platform-selection behavior was preserved.
Initial harness syntax and audit-library setup failures are retained separately
from actual source observations.

The implementation uses the existing eight-page/300-post/source-deadline budget;
initial subscriptions and overlapping windows do not crawl history. It adds no
polling job, setting, model or runtime. The qualified APK adds **4,176 bytes**
over 0.38.14, with all 497 non-generated native/data members unchanged. See
[feed coverage](FEED_COVERAGE.md) for exact scope, provider qualification and
reproduction. No new physical-phone hardware matrix or independent security
audit is claimed for this increment.

## 0.38.14 protected feed revalidation and parsed-page reuse

The full JVM suite covered **2,029 cases**, zero failures/errors and one optional
weight-dependent skip. Debug, release and instrumentation assembly and release
lint passed. The selected API 35 Android batch accounted for **67 named checks**
across nine classes: 65 passed and two opt-in live-source/Tor checks were skipped.
All expected method identities were accounted for. Native chronological mixed
RSS/social channel and cached feed-reader screens were inspected.

Three new JVM regressions first failed on unchanged 0.38.13: external social
feeds and frontend RSS did not send supplied validators on the next read, and
an unsolicited HTTP 206 could certify a complete refresh. The identical native
test APK then ran against byte-verified retained 0.38.13 debug. Its conditional
fixture failed because the second request lacked If-None-Match and downloaded
the full body again. The fixture sends 304 only when the request actually carries
the correct validator; an earlier exploratory fixture that returned 304
unconditionally is retained separately and is not this baseline evidence.

The candidate's native source-pool fixture retains the readable posts with a
successful current check and changes the second response from 5,676 body bytes
to zero. Clearing its feed memory requires a full unconditional read again;
a subsequent 503 remains unavailable instead of returning retained content as
a successful refresh. The broader native batch also covers mixed chronological
feeds, exact cached RSS text, saved/portable source editions, cross-posts,
search scope, streamed results, source scheduling and transport policy.

Additional JVM checks cover exact representation/header/target bindings,
unchanged and changed 200 bodies, Last-Modified, conflicting or unsolicited
304s, absolute expiry, cache clearing, privacy round trips, redirects/rewrites,
no-store/Vary, bounded eviction, oversized entries and in-flight ordering.
An owned SOCKS fixture observes two full-body requests with remote hostname
resolution, no replayed validators/cookies/credentials, and one parser call.
This exercises the Tor-selected Gateway path; it is not a live Tor exit test.
An initial extra generic-closing bracket prevented that harness compiling;
correcting the harness left production behavior and assertions unchanged.

The bounded memory is shared by the major syndication paths; every refresh
still contacts its source through NetworkGateway. Direct-only exact-session
validators can avoid body transfer, and identical validated bodies can avoid
repeat extraction. Tor always downloads the body and sends no retained
validator. Source failures stay pending and historical-window coverage retains
its separate catch-up contract. See [feed revalidation](FEED_REVALIDATION.md)
for the 32-record/4 MiB accounting/30-minute limits and source scope.

A separate October 24 public NASA RSS observation returned 200 with 249,777
decoded body bytes, followed by 304 with zero body bytes; all ten posts remained
and extraction ran once. The successive elapsed times, 2,947 ms and 30 ms,
include warm effects and are not a controlled before/after or phone benchmark.
No new provider, dependency, model, setting, background job, request allowance,
database table or export field is added. The qualified candidate adds 12,320
APK bytes over 0.38.13; all 497 non-generated native/data members are unchanged.

The technical docs now include explicit contracts across transport, failover,
decoding, reuse, storage and optional AI, plus a review protocol connecting
claims to their complete consumer and dated evidence. The satirical README
presentation remains intact. No new physical-phone hardware matrix, live-Tor
qualification or independent security audit is claimed for this increment.

## 0.38.13 source admission after transport startup

The full JVM suite covered **2,008 cases**, zero failures/errors and one optional
weight-dependent skip. Debug, release and instrumentation assembly and release
lint passed. The selected API 35 Android batch accounted for **56 named checks**
across seven classes: 54 passed and two opt-in live-source/Tor checks were skipped.

Seven new probe regressions failed on unchanged 0.38.12. They exposed expected
readiness being treated as a mode change, occupied read permits during startup,
startup time in source latency, stale observation dates, a newly learned pause
being ignored, local transport failures replacing good provider evidence, and
queued work surviving a connection round trip. Six additional initial-search,
directory and continuation regressions failed after the probe-only repair while
those paths remained unchanged. They use a filter that denies all destinations
and show that queued work reached request admission after a pause or mode change.
Additional checks cover pauses learned while waiting for a permit and separate
selection/session identities.

The identical native test APK first ran against byte-verified retained 0.38.12
debug. Both new methods failed their substantive assertions: a normal readiness
transition cancelled usable source data, and four waiting search checks blocked
a ready direct check beyond the one-second fixture limit. The candidate records
the usable check and completes the ready direct check in **45 ms** while all four
search checks remain waiting. These are owned loopback sources and simulated
readiness, not a live Tor exit, public-source latency or ordinary-feed benchmark.
The native streamed-search screen was inspected alongside the retained receipts.

Probe waits now precede source-read permits; source timing starts after admission
and local preparation, and observation dates describe completion. Policy and
public-index cooldown are rechecked after transport waits, with a further check
after a probe obtains its permit. Local startup failure preserves prior provider
health; actual admitted failures still train it. A separate ephemeral connection
selection detects mode round trips without confusing readiness with reader choice.
Actual request sessions, Gateway guards and the selected-Tor fail-closed behavior
remain in force.

No provider, dependency, runtime, model, setting, export schema, detached task or
request allowance is added. Probe concurrency remains four actual operations,
explicit sweeps remain bounded to 128 sources, and requested-read rolling hedges
keep their separate limits. See [source-check admission](ROUTE_SCHEDULING.md#transport-readiness-and-source-checks-03813)
for the mechanism and fixtures. This increment adds no new physical-phone hardware
matrix, live-Tor qualification or independent security audit.

## 0.38.12 useful results with provider backoff

The full JVM suite covered **1,993 cases**, zero failures/errors and one optional
weight-dependent skip. Debug, release and instrumentation assembly and release
lint passed. The selected API 35 Android batch covered **54 named checks**:
52 passed and two opt-in live-source/Tor checks were skipped. All expected
method identities across its seven classes were accounted for.

Four new JVM regressions first failed against unchanged 0.38.11: useful mixed
results lost a later refusal, saved continuation ignored a known pause, another
platform could use the same paused index, and in-flight success cleared the pause.
The identical native test APK also failed on byte-verified retained 0.38.11 debug:
both useful cards were present, but the recorded source status was HEALTHY
instead of RATE_LIMITED. On the candidate it retains both cards, records the
120-second Retry-After and makes zero follow-up directory/probe requests during
the pause. The native result screen was inspected. These are authored Gateway
and emulator observations, not a live-provider quota or phone-latency claim.

Additional regressions cover directory-to-post pause sharing, forced probes,
platform/endpoint/transport scope, snapshot restoration, useful latency learning,
increasing backoff, login scope, and cancellation without a stranded collector.
A response-hook mode-change check passed before the production changes: it
confirms existing Gateway session enforcement, rather than a newly repaired
privacy bypass. One new mixed-response fixture initially allowed its refusal
to arrive before the useful answer. A 100 ms fixture delay supplies the intended
late-refusal ordering; production admission and assertions were preserved.

The reader keeps useful material while the router learns provider availability
separately. One mixed operation produces one learning sample. Shared pauses
apply only to the public-index endpoint/transport and SEARCH capability; other
eligible methods retain their policy checks. No provider, runtime, model, setting,
background poll or reader/export schema is added. The candidate grows by about
7 KiB over 0.38.11 with native/runtime payloads unchanged. See
[search scheduling](SEARCH_SCHEDULING.md#useful-content-and-a-provider-pause-can-coexist)
for the exact mechanism and bounds.

The documentation also makes the shipped app-wide Tor control discoverable from
the existing README technical details, links the complete protection path, and
separates owner phone use from controlled hardware qualification. The satirical
product presentation remains intact. No new live-Tor, physical-phone hardware
matrix or independent security audit is claimed for this increment.

## 0.38.11 streamed public-index continuations

The full JVM suite covered **1,983 cases**, zero failures/errors and one optional
weight-dependent skip. Debug, release and instrumentation assembly and release
lint passed. The selected Android batch covered **53 named checks**: 51 passed
and two opt-in live-source/Tor checks were skipped. All expected method identities
across the seven classes were accounted for.

The new test first failed on the unchanged JVM implementation: a deferred query
was active, but an available fast page did not arrive within the controlled
two-second observation. The corrected identical native test APK also fails its
available-page assertion against byte-verified 0.38.10. On the candidate, the
native checkpoint contains both earlier pages while the Reel remains held and
the loading/Stop controls remain visible. Releasing it leaves all three posts.
That fixture makes five request starts, with two fast-query reads, two Reel
reads and one broad query. These are authored Gateway/reader observations, not
public-provider or physical-phone performance measurements.

Checks cover checkpointed Stop/retry without refetching a completed page,
early/late refusals, provider and permission changes while awaiting transport,
privacy-session round trips, unchanged direct reads during search-only Tor
readiness updates, source identity, 90 destinations across per-query deltas, identical
partial/terminal checkpoints and internal cancellation without stranded loading.
Another new regression caught an unnecessary hedge after a slow consumer had
allowed priority results to finish. Biased selection now consumes ready replies
before the expired hedge timer. Source latency learning uses first usable delivery.

The first native fixture mistakenly reused one Instagram shortcode for `/p/`
and `/reel/`; the reader correctly treated them as aliases. Distinct fixture IDs
fix that final assertion. An existing scope-dialog assertion passed unchanged
alone after failing in the batch; it now waits for the IME transitions, as the
surrounding tests already do, retaining the same validation assertions. The full
selected batch then passed. The initial native-fixture compilation also needed
explicit property types to avoid recursive Kotlin inference. These harness
corrections did not change production merging, scope validation or extraction.
Actual partial and completed native screens were inspected.

No source, model, runtime, setting or detached background job is added. The
candidate APK adds approximately 10 KiB over 0.38.10; native/model payloads are
unchanged. Query attempts, concurrency, cursor limits, shared reader limits and
Tor/original-access policy remain bounded. See [search scheduling](SEARCH_SCHEDULING.md)
for the selected-source progress path and its legacy page-at-a-time behavior.

## 0.38.10 fair public-index continuations

The full JVM suite covered **1,970 cases**, zero failures/errors and one optional
weight-dependent skip. Debug, release and instrumentation assembly and release
lint passed. The selected Android batch covered **52 named checks**: 50 passed
and two opt-in live-source/Tor checks were skipped. Method identities were
checked against all seven selected test classes, rather than inferred from the
runner's aggregate total.

The new native checks first ran against retained 0.38.9 with installed APK bytes
verified. Both failed their substantive assertions. In the three-query fixture,
the old reader used all twelve requests and delivered only the fastest shape;
the candidate delivered all three in six requests, retaining the fast initial
block. The ordinary native search screen also streams its first hydrated card
before the deferred Reel arrives. Other selected checks cover search scope,
provider/privacy behavior, discovery refresh, route scheduling and collected
posts, including NASA's existing three-platform regression.

An initial loopback test fixture tried to use HTTP HTML pagination, which the
production parser correctly rejects. The corrected fixtures stage a saved,
source/query-bound continuation at that boundary; HTTPS-only admission remains
unchanged. An initial candidate screen check reached both posts but attempted
to scroll an uncomposed lazy item. Using the list's scroll action corrected the
test navigation, and the full selected batch then passed. Native screenshots
were inspected; capture waits account for compositor frames after semantics.

This change adds no source, model, runtime, setting, detached background job or
request allowance. Deferred queries get priority over parked winner pages;
actual failures can fall through to validated parked continuations, while
refusals stop the plan. Old version 1 cursors remain readable. All planned
cursors are checked before networking, including parked ones. Gateway, original
access and Tor policy remain in force. See [search scheduling](SEARCH_SCHEDULING.md)
for the mechanism, exact bounds and controlled measurements.

The candidate APK grows by approximately 3.6 KiB, with no native/model payload
changes. These authored Gateway/emulator cases are not measurements of public
provider availability, physical-phone latency or live Tor exits.

## 0.38.9 public Instagram previews and source audit

The full JVM suite covered **1,963 cases**, zero failures/errors and one optional
weight-dependent skip. Debug, release and instrumentation assembly and release
lint passed. Android qualification covered **68 unique checks**: 67 passed and
one opt-in live-Tor test was skipped. The first batch had one new-test cleanup
failure: after asserting unavailable Tor prevented a request, cleanup tried to
acquire a client through that unavailable policy. Retaining the already acquired
client fixed the harness; both new tests then passed. The other 66 checks were
not rerun. Production privacy behavior and assertions were preserved.

The new native factory test reads frozen public Anonimogram responses through
NetworkGateway: one profile operation and one caption-preview operation. Twelve
undated previews remain outside feed refreshes; JPEG posters never become video
players. A separate anonymous native profile request returned NASA's explicit
name and profile data; the unavailable-Tor check started no request. The captured
native card projection was inspected, with artwork intentionally blocked during
the fixture. It does not establish live gallery/video coverage or automatic
combination of the two separate operations. See [source scope and rejected
leads](INSTAGRAM_PUBLIC_PREVIEWS.md).

NASA's captured “We are not slowing down” again renders as **one collected native
card**, retaining X, Instagram and Facebook originals and all three comment
roots. The originals remain unchanged. This requalifies the 0.38.1 fix; no new
NASA-specific matching rule is claimed. Changed-fact/destination negatives remain
covered by the full JVM suite.

The candidate APK adds approximately 8 KiB over 0.38.8. No native runtime or model
payload changed. These emulator and dated public-source checks do not establish
physical-phone performance, provider-wide availability or working live Tor exits.

## 0.38.8 requested route diversity and rolling hedges

The full JVM suite covered **1,953 cases, zero failures/errors and one optional
weight-dependent skip**. Debug, instrumentation and release APK assembly and
release lint passed; existing warnings remain. The API 35 native batch passed
**42 checks with one opt-in live Tor check skipped**. All 43 named methods across
six selected classes were accounted for, including source scheduling, search
privacy, heartbeat, discovery refresh, repository catch-up and collected feeds.
Installed application bytes matched the completed candidate. Native NASA,
saved-thread and reading-position screens were inspected.

Four of the six initial scheduling regressions fail against the retained JVM
implementation. They reproduce visiting more failed mirrors before a different
feed method, and a stalled operation holding later candidates behind a batch
boundary. The added cooldown regression checks eligibility again after an
operation has waited. Existing fast-first, failure-advances-one-successor and
immediate-failure tests still pass. Controlled checks also retain a maximum of
three active candidate operations, reject undated feeds, preserve known working
route priority and close cancelled Gateway calls without outage training.

The new Android scheduling fixtures were first run against verified installed
0.38.7 bytes: two failed and the cancellation check passed. All three pass on the
candidate with actual loopback Gateway requests. This qualifies native scheduler,
adapter, parser and cancellation composition, not public TLS, phone battery use
or source-wide availability. The former scheduler's three-second fixture timeout
and the new native timing are controlled examples, not a general speedup ratio.

The first broader native batch passed 40 checks and failed two existing feed UI
checks, with one skip. Their retained test selection contained only Reddit, so
setting X as active correctly fell back to the selected site. The fixtures now
explicitly select their X/Bluesky/Instagram/Facebook sources and still restore
the original preferences exactly. The full repeated batch passes, retaining the
same saved-alias, comment-stream, reading-position and incoming-banner assertions.
Production selection behavior was unchanged. The first JVM fixture iteration
also blocked a same-thread async job while waiting for its request; the qualified
negative run uses an IO dispatcher and accounts for a frontend's RSS/HTML attempts.

A bounded public TikTok check returned ten dated roots in 4,361 ms and 12 request
starts after the previously observed retry delay had elapsed. Earlier observations
were 14,242 ms/33 starts and 9,082 ms/three starts with different winners and a
rate limit. These are dated observations, not a controlled before/after benchmark;
[route measurements](ROUTE_LATENCY.md) records their limits. The diagnostic now
supports one fresh read; an invalid read count is rejected before transport setup.

[Scheduling policy](ROUTE_SCHEDULING.md) documents the shared ranking, three-job
bound and pinned coroutine timeout/select API. No source, AI runtime, persistent
cache, background poll or user setting is added. Original-access tiers, Tor
fail-closed behavior, Gateway protection and protected saved state remain intact.
No new physical-phone or API 26 qualification is claimed.

## 0.38.7 article fallback tiers and canonical revalidation

The full JVM suite covered **1,946 cases, zero failures/errors and one optional
weight-dependent skip**. Debug, instrumentation and release APK assembly and
release lint passed; warnings remain. The API 35 native batch passed **47 checks
with three opt-in checks skipped**. A separate live canonical-PMC check passed
through the production Gateway and isolated reader with original access disabled.
Installed native-test application bytes matched the staged candidate. Source
panels, original-link copying and the actual reader screen were inspected.

Six new JVM regressions fail before the fixes. They cover the missing NCBI HTML
fallback, repeated redirect/full-body retrieval, remembered originals preempting
recovered alternatives, an original URL mislabeled by metadata as an alternate,
a stale canonical target and an obsolete frame deleting newer validator bindings.
The latter is an efficiency failure, not evidence of cross-session ETag disclosure.
Other checks cover same-provider PMC aliases, directly discovered XML DOI identity,
sensitive final URLs and original access disabled after learning a redirect. A late
representation-change regression caught observed PDF MIME being learned as a
permanent PDF route. The hint now retains only the verified destination and
declared route kind, so a generic publisher URL can later return readable HTML.
The new JVM case failed before this correction; the native Gateway/worker case
also passes. Its first PDF response qualifies candidate dispatch, not PDF rendering.


The new native canonical-routing test also fails against the retained 0.38.6 app.
With the candidate, the first read makes two local HTTP requests (redirect plus
body), and revalidation adds only one request. The fixture serves **519 body bytes
in total**, invokes the actual isolated extractor once, and makes no document
request after original access is disabled. Its logical HTTPS article URLs map to
loopback HTTP using a test-only core transport seam: this qualifies resolver,
Gateway and worker composition, not public TLS identity or physical-phone speed.
Original/canonical URL distinctions and original clipboard value remain visible.

Actual protected Gateway probes retrieved NCBI's full HTML paper while Europe
PMC's HTML redirected to a script-only shell. Frozen native extraction returns
the same paper metadata/reading blocks as the JVM and rejects the shell. A live
Android read starting from Europe PMC's current `/article/PMC/…` URL obtained the
exact XML paper in one request to EBI, without first fetching the HTML shell.
These are dated direct reads, not source-wide availability or live Tor/phone
performance guarantees. The NASA three-platform collected-card check still passes.

A test-fixture editing error initially split an existing `gateway` statement and
stopped instrumentation compilation. It was corrected before native qualification;
production code was unaffected. A later shared transport-helper refactor compiled
but selected the public Gateway constructor and failed two native cases with a
cast error. Naming the internal `transport` constructor parameter fixes the test
binding; the repeated session suite passes all five checks. The test-only internal
access produces a pinned-compiler visibility warning and is not part of the
release application.

[Validation policy](ARTICLE_VALIDATION.md) and [PMC paths](SCIENTIFIC_FULL_TEXT.md)
record the changes. [Further route research](ARTICLE_ROUTE_RESEARCH.md) retains
working BioC/OAI-PMH body leads and format/recall cautions; neither new format is
activated in this release. No native runtime, model or background polling is added.

## 0.38.6 structured scientific full text

The final full JVM suite covered **1,934 cases, zero failures/errors and one
optional weight-dependent skip**. Debug, release and instrumentation APK assembly
and release lint passed; existing lint warnings remain. The broad API 35 batch
passed **44 checks with two opt-in live checks skipped**. After the final small
identity/lookup optimizations, focused checks passed **7 with one live check
skipped on API 35**, and **4 with one live check skipped on API 26**. Installed
APK hashes matched the completed candidate on both devices. Native article
headings, lists, captions, labelled tables, code and data-availability screens
were inspected. The NASA three-platform collection regression also passed.

The actual API 35 Gateway and isolated worker retrieved the exact public
AlphaFold 3 paper with original access disabled. Known PMC acquisition made one
full-text request; DOI acquisition also reached the matching XML through existing
metadata routes. These are dated direct reads, not Tor, physical-phone latency
or provider-uptime qualification. A retained full-paper comparison preserved all
79 body paragraphs over 60 characters under whitespace/punctuation normalization;
200 reading blocks, 76,654 characters and 12 selected citations matched exactly
between JVM and Android APIs 26/35. This is information retention on one source,
not a semantic-quality or universal-JATS claim.

The new native test fails against the retained 0.38.5 application because XML is
unsupported. The first implementation's full-paper audit exposed references
inside body sections and descriptions inside supplementary media; both are now
retained and covered by regressions. Exact root identifiers, conflicting IDs,
metadata-only/error XML, malformed/truncated input, internal/external entities,
structural/text limits, HTML fallback, route health, conditional reuse and
publisher-derived DOI checks have explicit coverage. External DTD declarations
make no JVM network request and parse successfully in the network-permissionless
Android worker. Rejected inputs leave the worker usable and transfer files are
removed. Returned DOI/access fields receive bounded primitive validation.

The first reader test tried selecting an offscreen, uncomposed table directly;
it now scrolls the real lazy list before retaining the visibility assertions.
One API 26 staging run attempted incomplete APK copies; installation rejected
both files and its tests therefore ran the older installed candidate. That run
was discarded. The repeated run used completed, SHA-256-verified copies, stopped
on installation errors, and verified the installed APK against those exact bytes.
Neither issue changed production reader behavior or the live preview.

The route adds no model, parser runtime or background polling. Figure/image-only
table pixels and supplementary-file contents are not automatically read; captions
and source anchors remain. Common MathML/TeX notation is supported, and excluded
nested articles are marked incomplete. See [scientific full text](SCIENTIFIC_FULL_TEXT.md)
for acquisition, identity, bounds and source-dependent limits.

## 0.38.5 article privacy sessions and warm routing lanes

The final full JVM suite covered **1,911 cases, zero failures/errors and one
optional weight-dependent skip**. Debug, release and instrumentation APK assembly
and release lint passed; existing warnings remain. The final API 35 batch passed
**41 native checks with one opt-in live-Tor check skipped**, covering actual
Gateway/sandbox article validation, native reading/cache, parser isolation,
local-model networking, search/media privacy and route heartbeat state. Native
structured and archived article screens were inspected.

Three new regressions failed before the validator fix: a fresh resolver replayed
a stored tag, changing to Tor replayed a direct tag, and a redirect forwarded its
old resource’s validators. Direct same-session 304 validation remains working.
Expiry, byte/count bounds, clear-during-extraction, unrequested 304s, changed
retrievals and policy round trips have explicit checks. A separate source-probe
regression failed because a mode round trip trained an outage; stale Gateway
operations now propagate cancellation and leave route health untouched. An
unchanged warmed Tor search client survives content-mode changes.

An earlier mixed native batch encountered three EOF/dead-Binder failures in the
worker fixtures. All 15 worker cases passed standalone. The fixtures had created
another controller for the app’s named worker service and sent process-wide STOP
when closing it. They now use the same single controller as production and do
not close an app-owned worker. The full 42-case batch then finished with zero
failures; UID/permission, malformed input, cancellation, colour/pixel and PDF
assertions remain intact. First-read timing is explicitly labelled as possibly
warm. Production parser isolation was unchanged.

[Validator/session design](ARTICLE_VALIDATION.md) records the exact policy and
limits. [Scientific route research](ARTICLE_ROUTE_RESEARCH.md) records three
successful public DOI metadata probes and a matching full JATS body. The JATS
route is not activated in this release. Emulator checks do not qualify physical
phone performance, live Tor exits or provider-wide availability.

## 0.38.4 retain completed catch-up pages at the source deadline

The full JVM suite covered **1,892 cases, zero failures/errors and one optional
weight-dependent skip**. Debug, release and instrumentation APK assembly and
release lint passed; existing lint warnings remain. The API 35 native batch
passed **60/60** across repository catch-up, cross-post feed, reading windows,
reading memory and collected-post presentation. Native NASA collection and
cached-reading/anchor screenshots were inspected.

The new native regression was first run against the retained 0.38.3 debug APK.
It failed after the real 45-second deadline because two completed newer pages
were missing from Room. With the same test APK and the candidate application,
those pages survive, the stalled read is cancelled, the subscription remains
pending with its previous success time, scratch is removed, and retry reaches
the original unfinished frontier. Separate JVM checks cover first-page timeout,
caller cancellation and ordinary success. The total deadlines and page/post
budgets are unchanged. There is no new background work, route request or runtime.

The captured NASA “We are not slowing down” regression still combines all three
platform editions into one native card with all three original/comment roots.
That duplicate fix shipped in 0.38.1; this release requalifies it rather than
claiming a new merger rule.

A corrected complete-route-pool audit measured fresh and learned head reads;
see [route measurements and limits](ROUTE_LATENCY.md). The first donor-only run
omitted PublicPathways and was rejected as a whole-app benchmark. Further live
Instagram original-header and Kittygram timeline checks remained login-gated or
unavailable; they were not promoted as working routes. The successful RSS bridge
is separate evidence. No physical-phone, Tor latency or upstream-uptime claim
follows from these tests.

## 0.38.3 original X profile fallback and feed health

The final full JVM suite covered **1,888 cases, zero failures/errors and one optional
dependency skip**. Debug/release/test assembly and release lint pass; existing
lint warnings remain. A further targeted run also verifies that the captured
NASA note keeps its text beyond the short preview. A further cross-source
regression replaces the older X capture with the newly parsed original page
and still collects its existing Instagram/Facebook editions into one caption.

Actual API 35 gateway reads retrieved five owned, dated originals each for NASA
and SpaceX with one request per profile. Original access disabled and selected
Tor unavailable both failed before network start. The public NASA caption and
native video control were visually inspected. These are dated source checks;
there is no new X search/history/comments or physical-phone qualification.

The broad native batch passed **61 of 62** checks across routing heartbeat,
catch-up, cross-posts, RSS, fusion, feed windows and subscriptions. One dialog
fixture timed out waiting for its initial seed. Focused runs exposed the same
controlled-clock issue in a second fixture. Advancing UI frames while asynchronous
setup completed made the full subscription class pass **9/9**. The live X check
also passed independently after its capture explicitly progressed frames and
verified the focused native window. Earlier screenshots showed stale content
despite matching semantics; those were rejected as visual evidence. The entire
62-check batch was not repeated after these test-only synchronization changes.

The old reader fails the new captured-page regression. A separate routing
regression fails before the feed-health fix by accepting a dated excerpt ahead
of a complete dated backup. Coverage includes strict literal/JSON resource
bounds, wrong origins/owners, private/tombstone records, full-note links, video
variants, quote scope, incomplete extraction and pending unbridged short windows.
See [the source and coverage details](X_PROFILE_FALLBACK.md).

## 0.38.2 cached reading during refresh

The final full JVM suite covered **1,868 cases, zero failures/errors and one
optional dependency skip**. Debug/release/test assembly and release lint pass;
existing lint warnings remain. The final API 35 native batch passed **81 of 81**
checks across catch-up, cross-post feed, feed windows, reading memory, portability,
saved copies, library migration and card work indicators. An earlier 62-check
batch also covered fusion, RSS, channels and subscription controls.

The cold-reader regression fails on the former implementation while a source is
held open. The final candidate reads the complete old Room window in **13 ms**
while that source is still blocked, then releases the newly collected generation
with its **17-pixel anchor preserved**. This measures cache collection after
repository initialization, not OS process startup or physical-phone performance.
Native cached-feed and three-platform NASA screens were inspected.

Failure-path checks cover concurrent storage bounds, cancellation after another
source finished, unavailable scratch, atomic database rollback, preserved saved
material, source pending/success state, completed failed-attempt generations and
refresh invoked from the UI thread. See [the storage/publication design](FEED_REFRESH.md).
This release adds no network request, native runtime or AI requirement.

## 0.38.1 route, Substack and discovery maintenance

The final full JVM suite covered **1,863 cases with zero failures/errors and one
optional dependency skip**. The API 35 broad batch passed 66 of 67 cases; the new
NASA capture had omitted a non-null media-description default. After correcting
that test fixture, its native feed/originals/comment-control regression passed,
as did the separately repeated subscription UI case. Earlier broad-run failures
also exposed a shared fake media hostname contaminating the RSS privacy fixture
and an incorrect test assumption that the collected NASA card must be owned by X.
The native feed correctly chose Instagram according to its stable chronological
input and rendered one caption with all three comment roots. These test corrections
did not change production source records, privacy policy or chronological order.

The new alternate-only Substack live Android gateway check returned the exact
requested public user, owned publication and declared social link in approximately
0.6 seconds, with original-site access disabled. A later alternate-only probe
exceeded its 3.5-second deadline. That exposed and corrected an owned-timeout
propagation bug: a route deadline is now an ordinary source failure, while a
cancelled parent remains cancellation and does not train a source outage.
Both deadline/fallback behaviors have explicit regression tests. This is dated evidence,
not an uptime or physical-phone latency guarantee. Actual native discovery and
NASA collection screens were inspected. See [maintenance details](ROUTING_OPTIMIZATION.md)
and [Substack scope](SUBSTACK_SOURCES.md#0381-identity-and-discovery-integration).

Debug/release/test assembly and release lint pass; lint retains existing warnings.
Advanced model runtime behavior and physical-phone energy/performance are outside
this routing-only maintenance qualification.

## 0.38.0 subscription, search and collection qualification

Full JVM suite: **1,837 cases, zero failures/errors, one optional skip**. The
API35 native regression passed **78 checks**, with two opt-in checks skipped.
Release assembly and release lint passed. Native subscription controls and
collected-image screens were visually inspected. Live Gateway checks retrieved
Instagram and TikTok post cards; individual upstream failures remain documented
in [the qualification record](PRODUCT_POLISH.md). Physical-phone performance,
battery and GPU qualification remain separate.

## Previous 0.36 qualification

Status: the 0.36 implementation and emulator qualification are complete. On
October 23, 2026, the owner explicitly requested publishing the tested build as
0.36.0 through the permanent download link for phone use. Physical-phone
performance, battery and GPU qualification remain unverified; publication does
not change that evidence boundary.

## Recorded checks

- Full JVM suite: **1,743 tests, zero failures, one optional weight-dependent skip**.
  The four offline retention-scoring tests also pass. Debug/release APK assembly
  and both Android lint variants pass. Lint warnings remain; a pass is not a
  statement that every warning has been removed.
- An independent build began with an empty tools/products root and completed
  all 12 dependency-ordered steps, native ABIs, JVM tests, debug/release APKs,
  lints and corresponding-source packaging in 3,290 seconds. Its early candidate
  tree was `5fb28e104ffc5724a025a571a662f413022cacfb`. Final implementation commit
  `b82b6e0` then passed all 11 steps without bootstrap in that independent checkout
  and prepared root in **607 seconds**. This separates a verified clean build
  path from final source validation; neither measures phone performance.
- The final broad API 35 x86_64 Android run covered **746 tests** and reported
  four failures. Each affected class subsequently passed independently on the
  latest candidate: native HLS/DASH playback (2), filter UI (13), discussion and
  original-edition UI (9), and native RSS (9): **33 tests without failures**.
  Playback passed when the concurrent host-model work ended; the timeout remains
  in the raw batch record. Other fixes finished text editing before a scrolling
  assertion, isolated optional name lookup in the RSS fixture, and prevented new
  identity lookups in the captured-originals view. The entire 746-test batch was
  not repeated after that last narrow view change.
- That broad run passed all six new announcement tests and all 72 Context-reader
  controller tests. The new checks include AI-off bundled OCR, selected Tor
  unavailable, exact image evidence and portable fields, saved-record expiry
  protection, cached observation dates, forced-refresh refusal/scratch cleanup,
  first cards preceding deferred flyers, and Free-only admission filtering.
- Native search, uncertain-announcement, evidence and local HLS/DASH screens were
  inspected. Public README images use synthetic data. Live-provider/model tests
  have separate opt-ins and are not implied by the ordinary Android suite.
- Frozen real-model Context controls, candidates and holdouts record fact-marker
  retention, actual omissions, noise admission and reuse independently. See
  [the evaluation record](CONTEXT_EVALUATION.md#036-candidate-retention-separately-from-completion).
  Early recall changes admitting unrelated material were rejected. A successful
  response or exact quotation alone does not establish retained information.

Private raw artifacts, process/fixture hashes and preview backups remain under
`LiberBuild/quality-release`. No physical phone is claimed qualified. These
receipts do not establish physical-phone qualification.

## Required journeys

| Journey | What must work | Measurements |
|---|---|---|
| Family events within a selected regional radius | Relevant activities, exact dates/locality, visible unknowns, optional uncertain announcements | Cold/cached first useful card, staged completion, missed relevant events and wrong-area results |
| Restaurant branch and menu | Correct branch, useful contact actions, preserved menu type/source, working public social account | First card/detail time, false merges, missing fields and unavailable-route recovery |
| Subscription catch-up | Stable reading position, merged cross-posts/images, incoming banner and truthful caught-up boundary | Scroll behavior, repeated content and pending unavailable subscriptions |
| Post and Context evidence | One press, cached brief, concrete useful details, conditions retained, noise-only updates reused | Fact retention, unsupported statements, noise admission, calls/time, memory and cancellation |

Compare matched current/candidate runs on a stock physical phone. Record model,
quantization, active KV/window, ABI, OS and device. Host/server measurements do not
qualify phone performance. Test CPU fallback and GPU where the phone supports it;
memory admission correctly refusing an oversized model is not an inference pass.

## Automated gates

- JVM tests, Android instrumentation and lint pass; inspect native screens and accessibility.
- AI-off discovery and bundled OCR work; selected Tor unavailable fails closed.
- Uncertain announcements cannot acquire invented dates, venues or eligibility.
- Saved copies survive cache expiry and upgrades; scratch files are cleaned.
- Frozen Context cases score useful-fact retention, noise and grounding separately.
  Review prose and qualifications, not only completion flags or schema validity.
- Initial cards do not await OCR/models. Cancelled searches cannot update the next
  area's deck. Background work ends with the bounded search; no idle polling loop.
- A fresh checkout with an empty external build root completes the documented build.

## Runtime promotion

F16/2K remains the shipping local baseline until a candidate cache profile improves
complete-task quality/time within phone memory limits and passes cancellation,
thermal and retained-information controls. Report each profile separately. Do not
promote compressed KV, a larger context or speculative decoding from acceptance
rate, host speed, valid JSON or source-code availability alone.

## Evidence and publication

Keep raw test artifacts and private backups on the external build volume. Commit
only sanitized fixtures, reviewed screenshots and summarized results. Record
first-card latency, network requests/bytes, peak memory, foreground energy/thermal
observations, and idle behavior. Separate reproducible app defects from dated
external-source outages; both require honest coverage notes.

Back up and update the laptop preview after tested increments. Publish the
official APK and corresponding source through `scripts/publish_release.py`.
For 0.36.0, the owner directed publication of the tested candidate before
physical-phone qualification; disclose that limit in its release notes. Further
local-inference profile promotions retain the qualification requirements above.
Preserve signing identity, app ID and the
fixed-name download asset. No credentials or personal reader state are release artifacts.

## 0.36.0-rc3: self-permalink cross-post repair

The “Fighting for Rural America” X and Instagram editions were independently
retrieved from public feeds on October 22, 2026. Their extracted full captions
agree and their publication times differ by 161 seconds. Instagram's RSS-Bridge
entry also links to its own post with a trailing slash, whereas its stored
canonical URL has no trailing slash. That link incorrectly entered the set of
competing destinations and prevented collection with the X edition.

Self references now use the existing platform content identity, with original
platform/federated-origin checks. The same rule removes canonical aliases of
both collected originals when checking copied material. Exact ordinary RSS URL
handling remains in place; article paths are not generally slash-normalized.
Other post IDs, case-sensitive IDs, changed claims and actual destination
conflicts remain distinct. Raw source text, attribution, URLs and discussion
roots are retained. This adds no lookup, model call or dependency.

The captured X/Instagram pair changes from two cards to one. The new synthetic
three-platform regression first failed on the prior code, then passed with the
repair. It also checks raw-record preservation and saved-edition validation.
Native Android shows one card with all three platform controls and loads all
three fixture discussion roots. This does not claim that Instagram's public
comment service is available. Physical-phone qualification and the official
Latest release gate above remain open.


## 0.36.1: hostile-content boundaries

This increment adds permissionless descriptor-based image/PDF/article workers,
bounded responses and killable deadlines, supplemental-link purpose checks,
pre-DOM admission, qualified RE2/J link cleaning, and narrow identity/clipboard/
credential controls. See [the boundary guide](HARDENING.md) for coverage and the
HEIF/AVIF compatibility exception. Stock Android and existing signing identity
are retained. No reader database migration is introduced.

The implementation qualification on October 23, 2026 records:

- **1,798 JVM cases**, zero failures, one optional model-weight skip.
- Six API 35 regression batches: **425 passed, seven optional skips**, zero
  failures. After the final parser/output/cleaner refinements, feed (64), Context
  (147 plus three skips), and privacy (40 plus one skip) were repeated and passed.
- Final API 35 boundary/reader/menu/saved-media/OCR/optional-AI batch: **70 passed**.
  Final API 26 boundary/article/menu/native-controls batch: **33 passed**.
  These overlap the broader journeys; the counts are not distinct-user totals.
- Actual isolated UID, denied network/private-file access, read-only descriptors,
  worker stalls/cancellation/recovery, hostile output, staging cleanup, transparent
  and wide/custom-color pictures, and ordinary extracted text are checked.
  AVIF interpretation matches the original decoder on API 35. GIF and BMP match
  on API 26 and API 35. The HEIC fixture is unsupported by the original platform
  decoder on both emulators; this is not a claim of successful HEIC decoding.
- Native identity, article, menu and feed screens were inspected. Optional live
  services and physical-phone performance/battery/GPU behavior remain unverified.

Private raw receipts and recovery backups are kept in `LiberBuild/evidence/hardening-v1`.

Release lint caught `Os.fcntlInt` being public only from API 30, despite the
older emulator executing it. Admission now uses that public API only on API 30+;
older Android uses public `fstat` and a zero-byte write probe. Tests check read-only,
read/write and write-only descriptors and unchanged bytes/offsets on both APIs.
Production release lint passes with zero errors, 177 warnings and one hint.
Including matching release instrumentation reports three additional existing
fixture warnings (180 total); warnings remain.

One batch run during concurrent release compilation and a second emulator had
an AVIF pixel mismatch and a menu visibility assertion failure (68 passes/two
failures). Both cases passed individually. The entire 70-case batch then passed
with builds/second emulator stopped, followed by four additional format-test
passes. These observations do not establish the cause of the transient failures;
the failed report and reruns are retained. Physical-phone behavior remains unverified.

The non-debuggable shipping APK passed a separate native selection: **48 passed,
one optional live-network skip, zero failures**. It covers branding/bridges,
Library/status, filters/privacy, article/menu/announcement/native-control journeys
and ten release-compatible descriptor/pixel/PDF/article cases. Instrumentation
was compiled with `-PantisocialTestBuildType=release` against the actual release
ABI. Debug instrumentation's Kotlin internal names do not match release; that
harness mismatch is not an app-path failure. Deliberate PROBE/STALL operations
remain disabled in release and are exercised only in the debug boundary batch.

## 0.37.0: adjacent-account discovery

Discover related adds a compass on public profiles and an action in Subscriptions.
It streams public connection cards independently of slower identity work, uses
existing account linkage to bridge supported graphs, and groups aliases only with
explicit local links, exact directory associations or the same website identity
object naming both accounts. Shared names, incidental bio links and separate
people described on one page cannot establish an automatic identity merge.

The included graph routes cover X/FxEmbed, Bluesky/AppView and Constellation,
Mastodon-compatible public lists, Reddit/Arctic Shift, publisher recommendations
and existing fetched public discussions. Sources and sample limits remain visible.
Related discovery requires no AI, additional runtime, social login or user key.
See [the implementation guide](ADJACENT_DISCOVERY.md) and
[the investigation record](DISCOVERY_SOURCES.md).

Qualification on October 23, 2026:

- JVM suite: **1,820 tests, zero failures/errors, one optional weight-dependent skip**.
- API 35 release regressions: **433 distinct successful checks and seven opt-in or
  device-dependent skips**, combining the feed, Library, Context, privacy, names,
  discovery and final targeted runs. The final targeted block passed all 58
  checks, including ownership grouping, independent graph/identity progress,
  cancellation, saved-record compatibility, name storage and the image-info UI.
- API 26: all 18 discovery/export checks passed after the cache repair; all seven
  discovery checks passed again after the identity and progress refinements.
  Native Android 8 and Android 15 discovery screens were inspected.
- Live public X, Bluesky, Mastodon and Reddit responses passed Android Gateway
  acquisition/parsing. These are dated source observations, not a latency SLA or
  qualification of every instance. Archive aggregation has a longer per-request
  allowance within the unchanged overall block deadline. A separate live check
  exercises Reddit through the app's actual bounded discovery session.
- Release assembly and Android lint pass. Existing lint warnings remain; passing
  lint does not imply every warning has been removed. No new production library,
  model or browser runtime is included. APK growth is approximately 100 KiB over
  0.36.1. Physical-phone energy and latency remain unverified.

Earlier failures remain in the private raw record. The new nullable forward
metadata initially failed the strict saved-export schema; explicit optional-field
registration, typed nested validation and old/new export tests repaired it. Android
8 exposed equal filesystem timestamps evicting a freshly written graph snapshot;
the cache now retains that write when enforcing its bound. Nine generic Compose
image-info fixtures could not launch against a release app; they now use the
existing target MainActivity with isolated-device guards and all nine pass. A
separate test-only host/dependency experiment was removed.

Telegram forward provenance survives offline snapshots, and the article reader
preserves citations belonging to the selected work before washing text. Cache
limits, privacy-policy changes, original-source settings, selected-Tor failure,
source cooldown and ordinary-cache cleanup have dedicated checks. Saved copies,
subscription data, application ID and signing compatibility retain their existing
protection. Source, APK, preview backup and publication receipts stay under the
external build root; credentials and private reader state are excluded.

## 0.37.1: startup-independent discovery-cache cleanup

Final integration review found that the ordinary-cache cleanup and storage-usage
hooks skipped uninitialized discovery stores. A process that had not opened
Discover related could leave its previous neighborhood and participant files
outside Clear cache and the byte total. Both lightweight stores now participate
in those IO hooks independently of whether the screen was opened. Parsing and
cleanup remain bounded; no network work is added.

A separate API 35 release instrumentation invocation opens no Activity or
discovery screen, writes both cache files, saves a text-only post, checks byte
accounting, clears ordinary cache, and confirms the files are gone while the saved
post is still readable. It passes, followed by all seven discovery checks. Release
assembly and lint pass. The 0.37.0 candidate APK, source archive and immutable tag
are retained; 0.37.1 is the corrected publication candidate. Earlier qualification
above applies to the unchanged graph algorithms, native UI and source adapters.
