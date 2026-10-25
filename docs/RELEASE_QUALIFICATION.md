# Release qualification

This is a dated engineering record for released increments and their candidates.
The owner also uses the app on an Android phone and reports reader defects;
those reports have driven fixes across feeds, search, discovery and Context.
References below to missing physical-phone qualification mean the specified
controlled performance, energy, GPU or hardware matrix has not been measured.
They do not mean the app has never run on a phone. Likewise, a local artifact
named `preview` can contain the same non-debuggable APK as an official release.
See [reviewing and evidence](REVIEWING.md) for how these evidence types relate.

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
or redirected RSS to HTML. These observations do not establish a live public
Min-Id path. The maintainer-listed Shitter deployments
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
