# Release qualification

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
| Family events near Melbourne, FL, 25 km | Relevant activities, exact dates/locality, visible unknowns, optional uncertain announcements | Cold/cached first useful card, staged completion, missed relevant events and wrong-area results |
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
