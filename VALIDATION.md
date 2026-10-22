## Collected image matching — 0.35.47 / code 95

The reported White House images exposed two different problems. Frozen feed
bodies retained raw media even after the image loader learned that platform
copies matched. Separately, thumbnail resize chains changed coarse photo hashes
for the text-heavy unaccompanied-children graphic. Galleries now observe fresh
local evidence without changing their captions, original URLs or discussion roots.
A versioned area-averaged 64-by-64 luminance grid adds local-change checks for
presentation; post/account grouping thresholds are unchanged. New saved editions
retain those observations, the offline reader uses its captured data, and an open
viewer keeps its selected gallery while the underlying card removes repetitions.

Qualification: **1,723 core tests passed**, zero failures/errors/skips. **54 distinct
Android cases passed**, zero failures/skips: 12 focused image/gallery cases and 42
complementary feed, fusion, saved-media, migration and image-search regressions.
Release lint has zero errors/fatals, 170 warnings and one hint. Debug, release and
instrumentation builds passed; native gallery screens were inspected.

The new three-platform test first failed against the prior implementation: the
local bank recognized the actual retained X/Instagram picture but the visible
frozen card still displayed three copies. It now reduces to one without refresh,
keeps an open viewer's selected page, retains all three source records and saves
an offline collection that still renders one picture after the bank is cleared.
An additional synthetic Facebook edition exercises platform-independent behavior;
it is not a claim of retrieving that particular live Facebook post. Public-source
fixture provenance accompanies the test assets. The original X pictures for all
three reported captions pass resize/JPEG comparisons; an edited graphic figure,
a crop and the three distinct pictures remain separate. Malformed/oversized
observations and legacy f1 compatibility are tested. Conservative perceptual
matching does not establish arbitrary image transformations or factual accuracy.

Earlier intermediate runs remain in external evidence: they caught the infographic
coarse-hash failure and aliasing in chained bilinear samples. True area averages
fixed that failure without weakening post-grouping thresholds. The viewer fixture
also needed the outer image action explicitly: a physical center tap on its
unloaded placeholder presses Retry instead. Final runs have no unresolved failures.

No AI, dependency, permission or matching-specific network request was added.
Release APK: **190,675,015 bytes**, just **2,048 bytes larger** than 0.35.46;
SHA256 `c626f287833b7cb4e9861eecaff50d88ac0434b225f2f5d35cb5257063a9802a`.
Evidence is under the external `image-dedup` directory. The derived comparison bank
keeps its 2,048-entry/seven-day bounds; each new observation is about 5.6 KB of
encoded samples. Physical-phone performance and universal duplicate recognition
are not claimed. See [media collections](docs/MEDIA_COLLECTIONS.md).

## Geographic opportunity routing — 0.35.46 / code 94

Local search keeps staged cards and its existing request limits. New regional
seeds are gated by coverage intersection, country, mode and topic before being
queued. Known foreign/sister-region routes, login/edit links and calendar views
outside the requested dates are excluded before retrieval. Explicitly distant
ordinary publisher records cannot grow a follow-up crawl. Regional publisher
coverage never becomes an event venue. Structured country/state conflicts defeat
same-named cities; new distant/online events stay out of the local graph, while
an already known occurrence can accept a move or cancellation.

The new passive readers cover LiveWhale v1/v2, declared local SpaceAPI venues,
geographically clipped Helsinki Linked Events, explicit family-calendar fields
and further offered calendar exports. Nearby-town addresses first reuse known
places, then get at most two bounded, exact-address lookups through Tor. Unknown,
ambiguous or wrong-house-number matches remain unresolved. Calendar stability
backs off from 30 minutes to six hours and resets for meaningful field changes.
No model, native dependency or bulk dataset was added. See
[local opportunities](docs/LOCAL_OPPORTUNITIES.md) for exact source/scope limits.

Qualification: **1,718 core tests passed, zero failures/errors/skips**, including
the optional acquired SSE pack with its host JNI path configured. **85 distinct
Android cases passed**, combining the final reader/search/settings regressions,
focused store/UI checks and five live source/venue checks. One separately opted-in
mixed-exit diagnostic was not run; bundled Tor connectivity and the new geographic
requests were tested. Release lint has **zero errors/fatals**, 170 warnings and one
hint. Native Search/Events screens and the installed non-debuggable main preview
were visually inspected. The fast-card fixture delivers while its other discovery
lanes are still delayed; no general physical-phone latency claim follows.

Real Gateway observations on the API35 x86_64 test emulator:

- LiveWhale: 50 dated records, 26 inside the selected Chicago area/window, about
  2.2 seconds for the bounded read.
- SpaceAPI: Binary Kitchen's local venue and offered calendar, about 1.0 second.
  Sensor events and its transient closed flag do not become activities/closure.
- Helsinki: 50 nearby occurrences over Tor, about 15.3 seconds including that
  run's connection work. An initial non-success response failed closed; the later
  explicit qualification succeeded. Source availability is not guaranteed.
- Space Coast family calendar: one listing and three detail reads took about
  20.3 seconds and produced three records with explicit date/location/cost fields.
  Those sampled neighboring-town venues lacked coordinates and were not falsely
  admitted as local cards merely because the publisher covers Brevard.
- Exact public-library address at 751 Jamaica Boulevard, Satellite Beach:
  about 2.0 seconds over Tor, inside Melbourne FL's 25 km circle. A repeated call
  returned cached coordinates without another Gateway request. This is a venue
  fixture, not a claim that the library announced the synthetic test event.

The broader regression exposed an older recommendation fixture whose URL actually
identified a Reddit comment on its current root. The fixture now uses a different
post URL; production discussion handling was not changed. Its complete six-case
subscription suite then passed. Earlier logs, the initial external-response
failure and the optional-host JNI configuration failure remain under the external
`local-opportunities` evidence directory; final checks have no unresolved failures.

Non-debuggable APK: **190,672,967 bytes**, SHA256
`3ace19bbabe1d5c81a2089dcb7d383b20b2ad371a54d4c15fd16553cb440c6de`.
This is **26,564 bytes larger** than 0.35.45. The main API29 laptop preview was
incrementally upgraded with the same signer and UID 10121; all **56 private files
were byte-identical before first launch**. Its complete prior private state and
APK were backed up externally. Database upgrade 7 preserves saved snapshots and
failed-source rests while requeueing previously successful pages for the new
projection. No physical phone/GPU qualification or signing-key change is claimed.

## Restaurant route scope and crowded pools — 0.35.44 / code 92

The real cold Melbourne search exposed a case absent from the direct-publisher
tests: directory recommendation, regional-category and upload links occupied the
bounded pool before the verified full-menu route. The app displayed the three
featured dishes despite already discovering the 86-item menu. This was a route
selection defect, not evidence that the full publisher menu was unavailable.

The router now ranks before taking its six-route cap, keeps the menu field
pending after a highlights excerpt, and prefers matching-location publisher
pages over unverified brand-name domains. Directory links must belong to the
matching listing/record, a fresh scoped declaration, an established restaurant
site or an isolated matching business card. Recommendation and upload/edit links
are excluded, including from previously cached evidence/queues. Explicit reads
end after a useful full-menu/text result. Protected saved snapshots are unchanged.

Qualification: **1,690 core tests passed, zero skipped**, including the new
directory-scope and crowded-pool regressions. **78 distinct Android tests passed**
(37 focused, 2 production menu reads, 39 regressions), including a populated cold
graph that must read the verified full menu before directory fallbacks. Release
lint has zero errors/fatals. This is an incremental continuation of the menu and
branding work below; 0.35.43 artifacts and its deployment evidence are retained.

## Native restaurant menus — 0.35.43 / code 91

The All platforms tab now uses the supplied anti social emblem. Restaurant menu
retrieval is a bounded extension of staged business enrichment: offered menu and
ordering links, OSM menu tags, exact public-provider tenants, and inert structured
or HTML dish records. Location evidence stays separate from brand identity and
unknown branch prices are labelled. Pickup/delivery/catering/specials remain
separate editions. Updated editions replace removed dishes; duplicate highlights
do not compete with a fuller menu. No AI, model download, account, website script
or paid service key is required.

Production NetworkGateway checks read Burger Inn's Melbourne locator and public
FromTheRestaurant menu: **86 priced dishes**, in about **1.2 seconds** for the two
known publisher reads on the emulator's direct content route. This is not a cold
regional-search, Tor or physical-phone benchmark. Hidden optional login dialogs
must not discard actually published catalogue data. Conversely, a cached old
menu cannot make an empty contact page count as fresh working menu content.

Indian River Coffee's page offers newer scanned App_1/App_2 PDFs alongside its
separate embedded-text menu. Initial failures came from selecting a scanned
document for the text test; comparing it with another PDF was not valid evidence
of an Android text API defect. The declared text menu now yields **3,653 cleaned
characters** with AI disabled. Original scans remain accessible through Other
published menus. Image-only documents are not invented as extracted text. The
text-only PDF worker supports older Android versions too; tests cover malformed
PDFs, image-only PDFs, absent models, source hashes and temporary-file cleanup.

Qualification: **1,688 core checks passed, zero skipped**; **77 distinct Android
checks passed** (36 menu/local discovery/storage/identity, 2 live menu sources,
39 portability/article/settings/source regressions), plus the menu UI rerun in
light theme. Dark/light native menu screens and the actual emblem tab were
inspected. Release lint has **zero errors/fatals**, 163 warnings and one hint.
The signed preview grows by **2,300,975 bytes** over 0.35.42; no model weights
were added. PdfBox-Android's complete upstream license and notice are bundled;
unused crypto/signing dependencies are excluded. Full source-specific behavior,
limits and provider-recognition distinctions are documented in
`docs/LOCAL_DISCOVERY_ROUTING.md`; saved menu fields are in `docs/PORTABILITY.md`.

## anti social release identity — 0.35.42 / code 90

The release name is **anti social** and the fresh Android identity is
`app.antisocial.android`. Kotlin packages, application/view-model/database/Tor
classes, JNI entry points, native library names, catalogs, own format identifiers,
preferences, database filenames, credential aliases, build properties and tooling
now use the new identity. No prototype-import migration is included: the owner
explicitly confirmed that the previous installation contained disposable tests.
Real development paths, historical artifact filenames, Darwin provenance and
upstream notices retain their actual identities.

The supplied PNG remains unchanged in `branding/anti-social-reference.png`.
Potrace directly traces its emblem and wordmark into native vectors; adaptive,
round, Android 13 monochrome and notification layers are local resources. Native
settings screens were inspected in light and dark themes on API 35. The new
wordmark follows theme contrast. The preview desktop launcher uses the supplied
emblem and the new name. See `branding/README.md` for reproduction and identity.

Qualification: **1,674 unit checks passed, zero skipped**, including the acquired
SSE model/native encoder check. **298 distinct Android checks passed**: identity
and JNI smoke (3), source UI/store (7), Context acquisition (6), article/cache (8),
live public sources (4), reader/search/portability/merge regressions (172),
settings/AI setup/saved material/feed memory/filter UI (88), and native workers
(10). The worker coverage combines nine checks in the 3 GiB lab with the 4B text
worker in a 6 GiB lab. The 3 GiB model attempt correctly failed admission; memory
checks were preserved. The 6 GiB run returned `23`, `Montreal 🙂`, and `READY`
after cancellation. OCR, audio slicing/resume, language detection, embeddings and
private-process IPC also passed. These are emulator CPU results, not physical
phone/GPU performance claims.

All 12 own native libraries were rebuilt for their supported ABIs; exported JNI
names match the new packages. The ARM64 Vulkan runtime compiled successfully.
A clean Gradle build removed former generated classes. Debug and release DEX
files contain no former application classes, APKs contain no old native copies,
the release signature verifies, and the active-source brand check passes.
All 163 checked upstream license files remain byte-identical; eight pinned primary
model notices verify. Release lint has zero errors/fatal findings (162 warnings,
one hint, including older adaptive-icon variants whose monochrome layer lives
in the Android 13 resource variant). The preview APK is 363,271,657 bytes.

Initial failed attempts remain in the external evidence directory. Renamed
synthetic X handles exceeded the platform's 15-character limit and were shortened;
an Instagram fixture selector was narrowed from an ambiguous repeated heading.
A host cancellation deadline failed during concurrent compilation, then passed
both focused and complete runs without changing timeouts or networking policy.
The original small-lab memory refusal is preserved alongside the successful 6 GiB
worker run. These repairs change fixtures and test conditions, not reader rules.

Evidence and promotion/rollback receipts live under
`LiberBuild/rebrand/`. The former preview APK and private state are backed up;
the disabled prototype retains its UID/keystore for local rollback. The new
preview starts with fresh state. Ordinary app networking remains through
`NetworkGateway`; root/ADB are development tools, never app requirements.
External source availability remains source-dependent and the live checks cover
only their recorded public samples.

## Quiet partial caches and source-name coherence — 0.35.34 / code 82

The installed 0.35.33 article generated and reopened without extra actions, but
the empty partial noise-thread result restarted a pending source review when
opened again. A completed empty partial cycle now gets a five-minute retry floor;
explicit Refresh bypasses it, and interrupted/queued work still resumes on opening.
Normal usable briefs keep their longer adaptive interval. Native partial-result
copy no longer asks for another Refresh; unread source status remains in Sources.

An installed brief also formed an unsupported full name using components from
different people. Unicode/coherent-name validation now reproduces that case,
accepts genuine full/short names and supplied descriptive/title prefixes, and
can remove a stubborn unsupported-name sentence after one rewrite without
throwing away the useful rest. Original evidence remains intact. These are
bounded controls, not general entity resolution or independent fact verification.

Validation: **1,569 core tests, zero failures/errors/skips**; **135 native UI,
controller, language and persistence checks, zero failures/skips**; final release
lint **zero errors, 149 warnings**. Selected-server controls over the expanded
noise fixture, actual acquired article and production-controller correction/cache
reuse all passed. The five-minute quiet period has a native failed-review fixture
covering reopen, expiry and explicit Refresh. The phone writer and grounded-past
recovery are unchanged from the passing 0.35.33 actual Bonsai qualification; source
and instruction parity are recorded, and this increment's name guards are tested
with new core cases and real server outputs. No new ARM-phone speed claim is made.

Evidence: `LiberBuild/inference-kv/context-delivery-0.35.34/`. Final installed
screens, record snapshots and unchanged-reopen checks are retained under
`main-upgrade`; versioned APK/source hashes and private-state backups accompany
promotion. Source unavailability stays explicit rather than counting as complete
coverage or being copied into the generated brief.

## Installed-output reactions and grounded micro grammar — 0.35.33 / code 81

The actual 0.35.32 preview confirmed one-press delivery and unchanged-cache reuse
on both checked White House threads. The reaction-heavy post completed with zero
selected additions; the acquired article finally yielded useful data. Its brief
still included an official's bare denial and motive accusation. That was a
writing failure despite the earlier lab cases, and this increment includes the
observed role wording in its regression collection.

After one same-evidence rewrite, code can remove a mechanically identified
reaction sentence while retaining the useful rest and checking correction values
again. Original evidence stays available. Factual administrative/court decisions
and numeric denials have separate controls and are preserved. Reader observation
guidance no longer asks for speaker attribution. The compact writer keeps trials
separate and reports past actions rather than issuing commands.

The first new Bonsai check caught “Fit … yesterday” despite the explicit prompt.
A bounded code path now recovers the original completed-action phrase from one
matching, non-negated source clause. It does not borrow the object or time from
another statement or rely on an uncertain/truncated/automatic observation. The
published repair uses original source wording and costs no additional inference.
Unsupported repairs remain retryable. The failed model output is retained in
the qualification evidence rather than silently accepted.

Validation: **1,567 core tests, zero failures/errors/skips** and release lint
**zero errors, 149 warnings**. **134 native UI/controller/language/persistence
checks passed** on the UI-identical 0.35.33 candidate. The final core-only grammar
refinement was then checked with the final APK's real selected-server noise,
article and production-controller correction/reuse cases, plus the actual Bonsai
positive/mixed-noise pipeline. That phone run passed in **7 calls, about 188
seconds** on the emulator CPU, with the shipped writer instructions. The unchanged
phone political-noise admission path retains its full 0.35.32 qualification; this
new phone run deliberately retests the affected positive writer with two useful
repair contributions and three noisy/off-topic items. This is not physical ARM
phone latency, population accuracy or independent verification of source claims.
The micro output can still be terse and fragmentary. Complete source receipts,
failures and observed model outputs remain inspectable.

Evidence: `LiberBuild/inference-kv/context-delivery-0.35.33/`. Installed-app checks
are retained under its `main-upgrade` directory alongside the preserved private
state and immutable APK/source hashes.

## One-press cached Context and data-focused briefs — 0.35.32 / code 80

Opening Context now starts missing/unfinished work and reuses fresh completed
publications, including valid no-signal results. Explicit Refresh overrides the
cache. A persisted model/style/recipe delivery stamp avoids online probes merely
to read cached work. The sheet automatically follows the first generation,
including regeneration of an old-writer cache, instead of freezing on working
notes and requiring Show. Later updates to an already readable current brief
retain their explicit update action.

Reader writing omits source-role and author lists, preserves provenance in
Evidence/Sources, and delivers concrete information without commenter catalogues
or generic reaction summaries. Claim-level conditions and allegations remain.
First-person corrections use the writer rather than an author-name prefix.
Small windows use compact instructions and a compact repair request. Larger
article reviews select bounded code-owned passage IDs, avoiding the abbreviated
or normalized quotations that deferred the actual linked report in 0.35.31.
The finite 64-passage limit is applied consistently instead of the smaller
freeform-finding quota; a 40-passage regression exercises that difference.

Validation: **1,563 core tests, zero failures/errors/skips**; **134 native UI,
controller, language and persistence tests, zero failures/skips**; release lint
**zero errors, 149 warnings**. Native screens were captured and inspected. Real
selected-server tests cover the expanded public noise fixture, a complete acquired
NYPost article, and a rude numeric correction through the production controller
with unchanged-work reuse. The article now produces a brief rather than a quote
failure. Qualification uses the selected transport through NetworkGateway.

Bonsai 2B PQ2_0/protected-PQ4 on the emulator CPU passed the expanded noise and
positive repair controls: 18 model calls, about 398 seconds. Its final writer
instruction matches the shipped source; subsequent changes cover initial/legacy
publication display and the larger-model article quota. This is a bounded
candidate qualification, not an ARM-phone latency measurement or a claim of
uniformly polished prose. Its observed wording remains terse and can be awkward.
Noisy material is not promised to contain a useful addition. These cases do not
establish population accuracy or independent verification of a publisher's claims.
Unavailable and unreliably reviewed sources remain visible as incomplete.

Evidence: `LiberBuild/inference-kv/context-delivery-0.35.32/`, including build
summaries, actual source/model calls, cache controls and native screenshots.

## Supported information and direct corrections — 0.35.31 / code 79

The live 0.35.30 thread exposed a missed unsupported political assertion after
its retained-finding shortlist had passed. This increment expands that public
fixture and adds a larger-model evidence-basis review before writing. Identifying
a record, supplying a relevant own observation, and providing a practical detail
are separate bases. Code validates exact support spans and record pointers;
an assertion labelled “reference” is insufficient. Bounded same-comment excerpts
preserve support split by extraction; other authors cannot supply its basis.
Both positive and negative decisions remain subject/evidence/model-bound.

Short approved corrections from one comment now render their exact source
quotations directly, with speaker attribution for first-person text. This avoids
unnecessary rewriting that reversed the correction target or lost a value.
Mixed sources, uncertain/truncated text and generated hierarchy prose cannot
use that path. Larger mixed-source briefs retain their bounded writer/repair
path. Explicit numeric replacements can pass without requiring the literal word
“not”; reversed or missing values remain protected. A malformed complete English
comment gets one strict same-source format retry, with no checkpoint before
validation. Source-change checks still reject an obsolete parent interpretation.
Prepared post context and composition receipts bind root changes even without a
cited root finding. None of these checks independently establish external truth.

Final full qualification passes **1,559 core tests, zero failures/errors/skips**.
Debug, non-debuggable release and native test packages build. Release lint has
**zero errors and 149 warnings**. The final isolated API 35 controller,
persistence, language and native UI cohort passes **130/130, zero skips**.
One earlier call-count fixture expected three calls; the intentional bounded
malformed-comment retry makes four. The corrected test preserves all stale-input,
queued-work, earlier-publication and saved-capture assertions.

The final selected-server run rejects the expanded twelve-finding public-noise
case and retains the two useful repair details while rejecting unrelated material.
Three fresh real-model controller runs pass correction retention and unchanged
refresh reuse. Two of those runs deliver the exact short correction without a
writer request; the third combines retained post details while preserving the
corrected value. All **24/24** additional authored selection controls pass.
These are bounded semantic controls, not population accuracy. Real controller
calls now record source-only fixture inputs/results without changing production
provider selection, capability discovery or NetworkGateway transport.

The micro primary/supplement/writer/name-repair instruction bytes remain identical
to 0.35.30. The larger-model-only checks add no micro generation duties. The earlier
0.35.31 native Bonsai run passes the expanded negative and repair controls in
346,416 ms on CPU/protected PQ4 in an x86 emulator; it is not a final-binary phone
speed claim. Known recall and prose limitations remain in CONTEXT_EVALUATION.md.
AI remains optional; ordinary feed, networking and saved-record policies remain.

Evidence, failed candidates, full model traces and predeclared live checks are
retained under `LiberBuild/inference-kv/context-evidence-0.35.31`. Native useful
screens were inspected. Preview replacement retains the UID and a private-state
backup, verifies the installed APK, and keeps versioned APK/GPL source archives.
Existing generated briefs require Refresh; deliberately saved captures retain
their wording. Source availability and unsupported material remain visible in
Sources rather than filling the reader's brief with verification boilerplate.

## Information harvesting and quiet Context refreshes — 0.35.30 / code 78

Context now separates information admission from prose composition. The prepared
English subject, original working quotations, source roles and available reply
context drive bounded relevance/information reviews. Only admitted original
passages reach the writer; candidate paraphrases cannot contribute invented dates.
Empty useful selections clear an earlier noisy brief without generating filler.
Root context remains available when the root contributes no finding. Completed
no-signal post/article reviews resume rather than waking the model again.

Every contribution has its own model/policy/subject/evidence-bound positive or
negative receipt. New rejected arrivals preserve the exact brief and meaningful
update time. Evidence and citation counts follow selected sources; source coverage
and saved reading steps remain distinct. A synthetic hundred-arrival regression
commits all rejection decisions while reusing one writer completion. Changed
sources, reply interpretation and model recipes reopen their own work. Saved
captures remain protected by the existing storage lifecycle.

Micro models review one contribution per call. A bounded supplementary review can
recover subject-related firsthand or quantified conditional observations after a
primary topical rejection of informational value. Code nominates this extra work;
it does not declare an observation useful. Broad pronoun fallback and a wholesale
replacement prompt failed negative controls and were rejected. Each review reserves
its actual small JSON response, separately from the prose output budget. Models
receive no tools or execution responsibilities. AI stays optional, using the chosen
local or remote provider through NetworkGateway; ordinary feeds remain unchanged.

Debug, non-debuggable release and test APKs build. Full core qualification passes
**1,546 tests, zero failures/errors/skips**; release lint has **zero errors and
149 warnings**. The final test-only fixture update verifies literal answers in
the actual admission/reply frame and retains numeric conditions in its joining
stub instead of assuming the earlier relevance-screen call path.

The complete isolated API 35 controller/persistence/language/UI cohort passes
**130/130 native tests with zero skips** on the final product code. Actual native
empty/useful screens were inspected. Failed intermediate runs are retained;
fixture updates follow the changed harvest/admission call path.

Actual model checks on isolated Android pass the reported public-noise control,
receipt reuse, and a positive control retaining both the 56 Wh correction and
qualified connector repair while rejecting unrelated stock material, praise and
a recipe. Both the selected server and Bonsai 2B PQ2_0 pass that limited check.
The server also passes 24/24 authored admission controls and a separate real-model
controller correction/resume test. The phone run uses CPU/protected PQ4 on an x86
emulator and takes 349,123 ms; this is not a physical-phone speed claim or a general
paraphrase-fidelity certification. Its prose is still rough. Host-only micro-model
research and remaining useful omissions are documented in CONTEXT_EVALUATION.md;
valid JSON, finite IDs and passing mechanical tests do not establish semantic
completeness or external fact verification.

Evidence and failed candidates are retained under
`LiberBuild/inference-kv/context-signal-0.35.30`. Preview promotion retains a private
state backup, verifies the installed APK and UID, and records its outcome under
`main-upgrade`. Versioned APKs, corresponding GPL source and checksums remain under
`LiberBuild/artifacts`. Existing Context publications require Refresh to use the
new recipe; deliberately saved snapshots retain their captured wording.

## Compact native reader — 0.35.29 / code 77

The feed title now opens a channel chooser instead of reserving a permanent chip
strip. The header, platform tabs and fixed-height reading toolbar are compact;
filters, review/apply and secondary feed actions use menus. Newest, a conditional
reading-return action, unread counts and pending checks remain reachable. Folder
selection moves into feed options. The floating subscribe button is removed;
subscriptions remain available through feed options and their primary screen.

Post actions occupy one row. Copy, offline-copy saving, filter creation and match
explanations move into a post menu. The authentic source sits beside the date;
comments, platform comment selectors, bookmarks, archive controls and optional
Context/provenance controls remain available. Search filters share its heading,
and filtered-result reveal indexing follows the shorter item list. Popup colours
follow the current app surface. Bottom navigation reserves Android's navigation
inset, with a geometry assertion that its icons and labels stay within their items.

The larger reading viewport exposed native bottom clamping on short feeds when
new rows arrived. Only unused end-of-viewport space becomes bounded trailing
padding (at most one viewport), allowing native keyed anchoring to preserve the
existing card. Row-status space stays fixed so marking a post read never collapses
space beneath a finger. Existing merged-post primary selection remains unchanged.

Final debug/release/test builds and release lint pass: **zero errors,149 warnings**.
Core source is unchanged; Gradle verifies reuse of the complete **1,533-test
qualification with zero failures/errors/skips**. Final Android qualification is
**99 unique checks passed, zero failures/skips**, across a compact-reader check,
11 targeted regressions and 87 non-overlapping checks on the same frozen final
APK. These cover feed memory, merged captions/comment streams, channel rules,
search/discovery, filters, authentic copying, offline saving, linked-video returns,
settings, AI provenance and optional Context visibility. Live public search is
excluded from this UI qualification. Actual light/dark/native menu screenshots
were inspected; the compact-reader check verifies that content occupies most of
the physical screen, including system bars, while secondary tools remain reachable.

Earlier failures remain in `LiberBuild/inference-kv/reader-ui-0.35.29/`. They include
obsolete selectors for moved controls, a test-only unavailable Espresso reference
(replaced with Android's existing key injection), and fixture models initialized
before test settings. Cross-post/fusion fixtures now cancel initial activity work
and await refresh completion before injecting local records; linked-video fixtures
recreate the model while preserving MainActivity's real incoming-intent handlers.
An unnecessary initial-primary override was removed after repeat/quoted-post
regressions; the final checks above retain the existing merge selection behavior.

Versioned APK/source archives retain exact repository correspondence. Incremental
preview promotion backs up private state before replacement and verifies unchanged
UID and preservation of settings, saved material and reader state around replacement.
Android may restart maintenance during a package replacement; changed scheduler,
profile-installation and freshness records require a logical audit rather than a
claim that every private file remained byte-identical. This increment adds no AI requirement or runtime;
the networking, source-availability and sandbox limits of prior increments remain.

## Automatic cleanup of unowned working files — 0.35.28 / code 76

Atomic replacements remove their temporary copies after success and handled
failure, preserving the prior destination on failed commit. Under its writer lock,
the filter subsystem prunes partial writes and removed/disabled source downloads
at startup, update and opportunistic cache cleanup. It retains current compiled
protection, metadata and one last-good text copy per enabled source. A concurrent
filter update makes ordinary cleanup skip this sweep rather than delete active
work or wait behind a network download. Existing retention/byte limits, active
reader leases, saved Context publications and explicit offline/model downloads
retain their own ownership policies. No new user controls or runtime are added.

Debug/release/test builds and release lint pass: **zero errors,149 warnings**.
Core source is unchanged; Gradle validates reuse of the prior complete
**1,533-test qualification with zero failures/errors/skips**. **17 selected Android
checks pass, zero failures/skips**, including failed atomic commit cleanup,
obsolete filter-file removal, cancellation, PDF quota/expiry and reader leases,
saved Context preservation, cleanup races, temporary Context media expiry,
native cached media/settings/library/expiry and working compiled protection.
The earlier full 0.35.27 native qualification remains scoped to that increment.

Evidence: `LiberBuild/inference-kv/cleanup-0.35.28/`. Versioned artifacts retain
exact source correspondence and preview promotion backs up private state before
installing. Development APKs/source archives/backups are intentionally retained
on RedtopStorage; they are separate from the phone's disposable caches. The
source-dependent and sandbox limits documented below remain unchanged.

## Shared static web reading and quiet protection — 0.35.27 / code 75

Native article reading and background Context now share structured, static HTML
extraction: headings, numbered/nested lists, quotations, code indentation and
simple labelled table rows survive without executing page scripts or loading page
subresources. Context receives a compact Markdown subset; native screens render
typed text blocks. Specialized RSS/Atom, JSON, social, media and caption handlers
retain their existing structure. Cleanup works with AI disabled and adds no new
runtime or conversion service. See [protected web reading](docs/WEB_READING.md).

Every explicit browser handoff rechecks the current local URL/filter policy,
including cached destinations. Filter-rewritten destinations are checked again
before sockets, with bounded rewrite chains. Tor remains fail-closed. Independent
daily filter work keeps current compiled protection active while replacing it,
reuses fresh downloads during bounded retries, yields to foreground traffic and
clears busy state on cancellation. Only the main application process initializes
the reader repository. An actual WorkManager database inspection confirms a
24-hour interval, 15-minute initial delay, unmetered connectivity and healthy
battery/storage constraints, without charging or device-idle requirements.

Full core qualification: **1,533 passed, zero failures/errors/skips**, including
pinned native SSE. Debug/release/test builds pass; release lint has **zero errors
and 149 warnings**. The two additional warnings concern intentional durable
SharedPreferences commits on the background update path. **172 unique Android
checks are qualified across the full cohort and one targeted rerun**: 171 passed
initially; one obsolete test expected a remembered feed's collapsed header to be
visible. The corrected test checks the selected platform and repeats its complete
tabs/settings/library/offline-copy/expiry workflow successfully on a byte-identical
production APK. This is not represented as a single 172/172 run. The structured
native reader's actual screen was visually inspected; its screenshot with no
clipboard overlay is retained. Offline bundled language detection also passes.

Evidence retains earlier failed builds/test assertions alongside final results:
`LiberBuild/inference-kv/web-reading-0.35.27/`. The verified schedule is not an
observed successful 24-hour download; Android and network conditions may defer it.
Static extraction is not a full OS sandbox: PDF/media workers share the app UID.
An external browser controls its own subsequent redirects, page execution and
Tor use. Source availability and physical-phone performance remain unverified
outside the documented observations. Delivery retains versioned APK/source
archives, exact source correspondence and private-state-backed preview promotion.

## Older Context snapshot link recovery — 0.35.26 / code 74

The first upgraded live preview still had only six direct destinations: its old
Context snapshot had already discarded the original RSS heading, despite a fuller
current source record in the local cache. Refresh now consults that exact original
post's cached record when it adds complete destinations. The recovery is local,
binds a new current snapshot and preserves archive variants and saved publications.

Full debug/release/test build and release lint pass (zero errors,147 warnings).
Unchanged core qualification remains **1,523 passed with no failures/errors/skips**,
including pinned SSE; the Gradle core test task validates reuse. Final native:
**145 passed, zero failures/skips**, including lost-heading recovery and archive
separation regressions. The initial new test fixture omitted the required byte
accounting field; its failed compiler log is retained, and the corrected full
build/native run above passes. Source/model logic from0.35.25 is unchanged.

Delivery retains versioned APK/source archives, exact repository correspondence
and private-state-backed incremental promotion. Live root replay and complete-link
coverage are recorded under `LiberBuild/inference-kv/context-legacy-links-0.35.26/`.

## Reader-first Context and complete roundup links — 0.35.25 / code 73

The actual White House / WHOSTP47 “In case you missed it” roundup exposed a
12-link extraction cap and full destinations retained only in its original RSS
heading. The candidate recovers complete literal URLs, folds the labelled list
beneath its introduction, omits a proven RSS caption echo, and shares authentic
destinations between the native inventory and Context. It never guesses shortened
URLs. Twenty unique linked social posts are present in this particular roundup.
All link cleanup is deterministic and works independently of optional AI.

The primary Context view is a combined concise prose brief; evidence and original
quotes are expandable. Direct linked material precedes comment reads. Exact
permalink selection excludes neighbouring posts/replies. Complete, short,
prepared English linked text can reach composition intact without a separate
paraphrase call. Deferred comment reviews cannot veto available post/article data.
Source clocks remain in evidence, outside the brief prompt. Narrow source-term
checks bound one rewrite of a completed response introducing unsupported names
or explicit acronym definitions; these are not general semantic verification.
The bounded earlier-publication slot, immutable citations, held updates and saved
snapshot protection participate in the migrated database/cache accounting.

Full final core: **1,523 passed, zero failures/errors/skips**, including pinned
native SSE. Debug/release/test APKs build; final release lint: **zero errors,
147 warnings**. Final native qualification: **143 passed, zero failures/skips**,
covering Context controller, storage/migrations, UI, collapsed link inventories,
article/PDF reading/cache and capability persistence. Actual native screens were
visually inspected. Two additional selected-real-model Android checks pass; the correction/resume fixture
takes **3.225 seconds**, retains the rude factual correction and reuses unchanged work.

The public roundup run takes **13.123 seconds on the selected remote server**:
all 20 direct link streams complete, 22 documents are acquired, and 18 findings
are available to the brief. One passage review remains pending; image text tools
are off in this fixture and original-platform discussion is intentionally isolated.
The acquired post descriptions do not establish that embedded videos were watched
or transcribed. The individual-instance, remote-server and laptop observations
are not physical-phone or universal source-availability guarantees.

Earlier failed builds and live candidates remain in evidence. They exposed stale
finding-first UI expectations, a timestamp-in-prompt contract, cold metadata
recovery, invented officials/acronym definitions, and a MockWebServer shutdown
race in the cancellation fixture. The last fixture now leaves a request pending
until cancellation instead of racing two equal five-second timers. A previous
candidate passing structural tests is not represented as semantically qualified.

Versioned debug/preview APKs and source archives include corresponding pinned
native sources and notices. Delivery verifies exact repository/source-archive
correspondence and backs up the existing preview’s private state before an
incremental install. Evidence: `LiberBuild/inference-kv/context-reader-brief-0.35.25/`.

## Available current findings with protected earlier Context — 0.35.24 / code 72

The actual 0.35.23 reread completed15 units with6 current findings but retained its
earlier799-character brief while25 reviews remained pending. This is deliberate
publication protection, not a fresh composed brief. The UI now reads the current
validated findings separately, with their own source and reply snapshots, while
keeping the earlier publication expandable. It also covers an empty earlier
publication before the first combined brief completes. No model call, classifier,
review admission, schema or source acquisition changes in this increment.

Final native qualification: **135 passed, zero failures/skips**, including the
entire Context UI class, controller/persistence, article/PDF reader and cache
checks. New fixtures cover current and old versions sharing a source/finding ID,
a source edit while its citation is open, held updates with stable paragraph
bounds, saved snapshot protection and an empty earlier publication. An initial
citation interaction dismissed the sheet; the harness now brings both the card
and its source button into view. The complete rerun passes, and actual native
current-finding/earlier-quotation fixture screens were visually inspected.

The additional selected-real-model Android test passes in **2.957 seconds** with
three completed units, three findings, the rude correction retained and unchanged
refresh reuse. Core source is unchanged from the qualified **1,509 tests with no
failures/errors/skips**, including pinned native SSE. Final debug/release/test
builds and release lint pass: **zero errors,147 warnings**. Darwin article routing
and the five real public-source observations from0.35.23 remain unchanged; their
individual-instance, physical-phone and live-Tor limits still apply.

Delivery evidence retains immutable APK/source archives, exact repository/source
correspondence, private-state-backed preview upgrade and the actual current-findings
native screen. Unreviewed material stays pending; this is usable partial progress,
not a claim that every comment or model output has been successfully reviewed.
Evidence: `LiberBuild/inference-kv/context-current-findings-0.35.24/`.

## Shared Darwin article paths and supported Context briefs — 0.35.23 / code 71

Full final core: **1,509 passed, zero failures/errors/skips**, including pinned
native SSE. Debug/release/test APKs and release lint pass: **zero errors,
147 warnings**. Final Android qualification: **123 passed, zero failures/skips**,
including native article/PDF rendering, canonical copy links, retry, storage quota,
active-reader leases, destination navigation and immediately visible supported
findings when the combined brief is not ready.

The selected real model exposed a qualification assumption: its first partial
read completed two units; refresh correctly completed a third pending unit, so
its publication stamp changed. This was not a completed-unit cache miss. The
trace records the malformed answer's deferred source. JSON-only output improved
format but did not force a useful short-post brief. The final engine can instead
retain the complete available English post text after a typed review failure;
comments, articles, uncertain language and declared incomplete sources retain
strict admission. The final selected-model Android fixture passed in **3.051 seconds**,
with three units, the rude correction retained and unchanged-refresh reuse.
Original source quotes, translated
span mapping, question status and deterministic resume have new regression checks.

Production gateway/filter availability checks recovered five exact public URLs:
NPR text (0.56s first run), a public scientific copy (1.26s), the actual WhiteHouse
NYPost link (0.36s), Medium via Small (5.44s) and an exact NYT Wayback capture
(5.64s). This qualifies these observations only. Other individual instances,
CNN/CBC live behavior, physical-phone performance and new-route live Tor latency
remain unverified. Unit coverage verifies fallback ordering, matching identities,
article-specific misses versus provider backoff, validators, finite attempts and
fail-closed Tor behavior.

Native archived article and PDF fixture screens were inspected. Final actual
incoming NPR and NYT links reached the native reader: NPR attribution is readable,
and the NYT capture shows `Archived 02/26` above the cleaned article. A concurrent
test install initially produced a launcher screenshot despite correct UI XML;
that image is rejected. The final sequential screen check initially required
500 visible characters, rejecting a valid NYT viewport containing450. Its corrected
300-character check and visual inspection do not change the app or inference.

Earlier preparation failures remain in evidence: nullable Gson-array conversion,
a test variable typo, missing test setContent import, PdfDocument close handling,
a recursive inferred cache type and one non-void JUnit test method. They were
corrected and are not represented as passing runs. No previous live artifact was
overwritten. Delivery retains versioned APK/source archives, exact repository
correspondence and a private-state backup. The external promotion receipt and
actual-thread replay record the installed version and retained native result.

Evidence: `LiberBuild/inference-kv/darwin-article-routes/`. Donor provenance and
source-dependent capabilities are documented in `docs/CONTEXT.md` and
`licenses/context-evidence-provenance.md`.

## Context response-budget recovery — 0.35.22 / code 70

The actual 0.35.21 replay cleared language preparation but exhausted the remote
model's response budget before committing a unit. This second failure is retained;
0.35.21 did not solve the reported live thread. Candidate22 uses the uniquely
selected vLLM metadata to request direct utility answers, explicitly requires an
available post/article brief, and defers an exhausted passage without stopping
later sources. Generic provider/native protocols and strict quote/JSON checks stay.
Generation mode is part of review identity; old metadata is rechecked on demand.

Full core: **1,485 passed, zero failures/errors/skips**, including the pinned native
SSE encoder. Debug/release/test builds and lint pass: **zero errors, 145 warnings**.
Isolated Android: **116 passed, zero failures/skips**, including response-limit
continuation/retry and metadata-cache migration. One additional real selected-model
Android test passes in **3.476 seconds**: four findings, the rude battery correction
retained, three checkpoint units, unchanged refresh reuses completed work. This is
a synthetic correctness fixture through production transport, not phone speed or
general semantic qualification. A captured fake-model UI frame is blank despite
passing semantics; it is not accepted as visual proof. Live native replay remains
the final user-facing check after promotion.

The first22 build exposed a prompt-wording contract assertion and skipped optional
SSE because its host fixture environment was omitted. The corrected full rerun
above includes SSE and has no skips. An initial host credential-staging command
waited for stdin closure; it was stopped and replaced with a bounded private file
transfer on the isolated emulator. No key enters the APK, logs or source archive.

Evidence: `LiberBuild/inference-kv/context-response-0.35.22/` and
`LiberBuild/inference-kv/context-response-limit-0.35.21/`. Darwin article route audit
is the next requested task after the actual Context replay succeeds.

## Best-effort Context after real language-preparation veto — 0.35.21 / code 69

A protected snapshot of four actual preview reads contains 171 acquired documents
but only three findings. Two briefs are empty. Of the comments, 131 already have
body/title reading views; parent preparation prevents their review. The source
records include 164 uncertain-language review notices. The native screen shows
“Some material needs another model review” with zero findings. This is a pipeline
failure before text inference, not evidence that the text model found no signal.

An archived-0.35.20 Android controller test reproduces the failure: a readable
reply under “Night lights 💡...” never produces its available brief. The candidate
keeps successful translations, retains unconfirmed original fragments explicitly,
and carries body/title/parent uncertainty through cache, review tokens and model
requests. Exact source/citation mapping remains. A title or parent confidence
failure no longer vetoes the available body/comment. Compact English-prose
instructions preserve the prior small byte-counted extraction budget.

Full core: **1,483 passed, zero failures/errors/skips**, including pinned native
SSE. Debug/release/test APKs and release lint pass: **zero errors, 145 warnings**.
Final isolated Android: **109 passed, zero failures/skips**. The original controller
repro and cache/token tests pass; the native UI now displays the combined available
brief. Earlier preparatory runs expose two test-fixture/assertion mistakes: a
missing repository post was pruned, and the substring assertion used whole-value
matching despite displaying the correct combined brief. Both logs are retained;
neither is claimed as a passing test. Final native-screen and live preview checks
are retained with the delivery evidence. No production
semantic classifier or native inference/KV default changes in this candidate.

Evidence: `LiberBuild/inference-kv/context-best-effort-0.35.21/`; actual failure
snapshot: protected `context-user-failures-0.35.20/`. Darwin public-source paywall
routing for native readers and Context is the explicitly queued next task.

## Contextual reaction triage — 0.35.20 / code 68 (2026-10-20)

Four new core regressions first failed against the prior engine. Two Android
controller tests executed and failed against archived 0.35.19: the literal +1
answer never reached contextual review, while the decorated-praise fixture was
held during language preparation before reaching the new gate. A corrected
capture reproduced that precursor; neither praise red proves inference admission.
A separate new core regression then failed when known decorated neutral text
requested the language detector. These are mechanism tests, not model-quality
or phone-performance admission.

The deterministic pass checks the immediate background before skipping a known
whole reaction. A bounded exact phrase lookup and reviewed decoration set avoid
a universal minimum length cutoff. Short facts, mixed substantive prose, standalone
warnings/prohibitions, arrows, flags, keycaps, irony and unknown joined sequences
remain reviewable. Root posts/articles are not discarded. Exact neutral phrases
also pass language preparation unchanged without detector/translator work; nearby
foreign or substantive words do not receive that exemption. Preparation policy
v4 and engine identity v6.10 reprepare/review incompatible work on authorized reads
while original sources, acquisition cursors and earlier/saved publications remain.

Final core: **1,477 passed, zero failures/errors/skips**, including the pinned SSE
pack and actual host JNI. Debug/release/test APKs and release lint pass: **zero
lint errors, 145 warnings**. Isolated Android: **106 tests, zero failures/skips**,
covering the full controller/persistence classes and ten native UI cases. The
added controller checks confirm no model generation for known decorated praise,
exact source retention across reopening/resume, and contextual review plus exact
citation of a literal +1 answer. Native screens and state-preserving preview
promotion evidence are retained under `inference-kv/context-linked-0.35.20/`.

Decorations are removed only for reaction matching; general model-input cleanup
is not implemented. No semantic recipe, inference/KV default, networking or schema
change ships. Rejected linked-group/kind/detail/conjunction experiments remain
research, as documented in docs/CONTEXT_EVALUATION.md. Broader micro-model semantic
retention and native compression/physical-phone qualification remain active.

## Strict Context response admission — 0.35.19 / code 67 (2026-10-20)

Three new core regressions fail before the fix: a duplicate relevance field can
discard a source, a nested duplicate quote can replace evidence before checking,
and a duplicate overview can acquire a compaction receipt. An additional Android
controller regression fails its stale-publication assertion against archived
0.35.18 when a replacement model returns duplicate status fields. The corrected
single-test red run executes one test and is retained separately from an earlier
zero-test preparatory attempt, which supplies no evidence.

The engine now strictly validates the complete bounded JSON document, including
decoded member names at every nested object, before tree parsing or schema
admission. Duplicate fields, non-JSON extensions and trailing documents remain
retryable; a single valid fenced object remains supported. Existing guarded error
handling preserves earlier published/saved sources until a valid retry completes.
Identity `context-source-v6.9-strict-review-json` reopens incompatible model reviews
and compactions on authorized reads. Source/translation caches, cursors and earlier
publications survive. Relevance prompts, model/window/KV defaults, schema and
network policy are unchanged. Strict syntax cannot fix a mistaken valid judgment.

Final core: **1,469 passed, zero failures/errors/skips**, with the pinned SSE pack
and actual host JNI enabled. Debug/release/test builds and release lint pass:
**zero lint errors, 145 warnings**. Final isolated-device regression: **104 Android
tests, zero failures/skips**, covering full controller/persistence classes and ten
native UI cases. Earlier local replies, saved publications and media text limits
remain covered. Screens and state-preserving preview promotion are retained with
the artifact/source hashes in `inference-kv/context-linked-0.35.19/` on LiberBuild.

Research adds a typed post-frame harness control, strict raw-decision validation
and explicit conjunction scoring for separate micro duties. Linked components,
contribution labels and other semantic variants remain research only. Detailed
native comparisons and their limitations are in docs/CONTEXT_EVALUATION.md. Full
Context semantics, native compression and physical-phone admission remain active.

## Guard failed Context reviews — 0.35.18 / code 66 (2026-10-20)

An archived-0.35.17 controller test reproduces a stale error branch: another
preparer installs the updated parent's English reading while an old child request
is in flight; its malformed response then defers the unchanged child instead of
leaving it pending. An initial fixture allowed ordinary preparation to trigger a
valid re-review, and its failure is retained separately, not used as this proof.
The corrected fixture fails the actual pending-source assertion.

Failed batches now validate all source tokens/revisions before atomically writing
retry markers and limited-source notices. One revoked token leaves every member
untouched and queues a new check. Current failures remain visibly limited, and
completed valid units survive retry. Previous publication and explicit saved copy
stay readable. There is no schema, network, model-prompt or inference-default
change. Language-preparation and transport-error handling remain separate.

The five focused Android checks pass, followed by **102 Android tests, zero
failures/skips** across full controller/persistence and ten native UI cases.
Tests cover unchanged-source partial receipts, retry, replaced tokens, all-or-none
failure admission, stale signal/no-signal and stale malformed responses. Native
earlier-Context and dark/light local-source screens were inspected.

Core **1,461 total, 1,460 passed, zero failures/errors, one optional host-native
skip**. Debug/release/test builds pass; release lint **zero errors, 145 warnings**.
DEX owner/name checks find the new guarded failure method in both APKs and not
archived 0.35.17. Test-only passage experiments and their limits are documented
in docs/CONTEXT_EVALUATION.md; no semantic recipe is promoted.

Delivery evidence, exact artifacts, matching source and state-preserving live
promotion are retained under `inference-kv/context-passages-0.35.18` on LiberBuild.
Full Context/native inference qualification remains an active task.

## Guard in-flight source reviews — 0.35.17 / code 65 (2026-10-20)

Two corrected native controller fixtures fail against archived 0.35.16: a parent
changes while its unchanged child is being extracted, then either the old
interpretation reappears or an old NO_SIGNAL result completes that child. The
initial fixture used arbitrary revisions which ordinary acquisition later
replaced, causing a valid re-review; those initial failures are retained separately
and are not used as proof. Corrected parent/child revisions match ordinary
ContentRevisions.signature, exposing the actual stale admission.

A bounded SQLite review window now validates prepared source, English reading and
full typed background inputs, snapshot and processing policy once, then issues
small revocable tokens. Semantic source/alias/reading/known-evidence changes revoke
in-flight work. Unit commits and source completion require a current token in
their own atomic transaction. Unchanged provenance, reading choices and identical
English caches preserve valid work. Earlier publications/saved views remain
readable while rejected work is queued. Completed unaffected receipts and the
existing cancellation-safe checkpoint behavior remain available.

The final candidate passed **99 Android tests, zero failures/skips**: full controller
and persistence classes plus ten native UI cases. New cases cover both stale
signal and stale no-signal responses, another database helper, token replacement
and old-job cleanup, provenance/reader choices, known-source and English-view
changes, policy/snapshot changes, byte accounting and version-5 migration with
published/saved evidence intact. Earlier migration fixtures now remove the new
claim table before masquerading as older schemas and expect current schema 6.
A parent-view fixture prepares English before its review, matching real operation.

Core **1,457 total, 1,456 passed, zero failures/errors, one optional host-native skip**.
Debug/release/test builds pass; release lint **zero errors, 145 warnings**.

Schema 6 adds only bounded review claims; Room and networking are unchanged.
App policy `context-publication-v2-guarded-reviews` reopens legacy unguarded unit
work only on authorized reading. Core model prompts, windows, cache compression,
position settings and speculative-decoding defaults are unchanged. This is input
and storage consistency, not semantic/model or physical-phone admission.

Final release checks, live delivery, original/fixed native logs and a separate
production long-comment comparison are recorded under
`LiberBuild/inference-kv/context-read-guard-0.35.17/`. Long cases add only generic
gratitude before the existing predeclared content, crossing the 480-character
copy threshold without changing its useful facts. No alternate semantic recipe
is promoted from intent or schema-valid execution.

## Parent-first Context review — 0.35.16 / code 64 (2026-10-20)

Actual 2B and 4B native runs reproduce a source-order fault. With the same three
questions and closed answers delivered backwards, each model's three relevance
votes drop a different “No” and the “I don't know” answer. The case object is
identical in the before/after snapshots. Two before-fix core assertions fail, and
the late-parent controller assertion also fails on the archived 0.35.15 debug app
using the new single test on the isolated device. These are retained assertion
failures, not schema, missing-method or unreachable-provider substitutes.

The engine now reviews explicit parents before children; the controller applies
uniquely resolved indexed dependencies before its work limit. Protected questions
and already-reviewed closed answers take their literal-source path before model
batch construction. The question's ignored relevance vote is removed without
weakening the closed-answer relevance safeguard. Parent/child sources cannot share
one independent model extraction batch. Iterative strongly connected components
handle long chains and malformed cycles without recursion or infinite scheduling;
cycle members remain in their original order. This schedules review, not feed
presentation or source certification. No schema/network/model/KV default changes.

Final core result: **1,457 total, 1,456 passed, zero failures/errors, one optional
host-native skip**. New cases cover shuffled parents, stable eligible work, cycles
and descendants, missing/self references, a 10,000-source chain, 100 varied DAGs,
duplicate IDs, larger independent batches and avoiding ignored question votes.
An older router assertion expecting one ignored vote now expects zero, with the
same exact-question/never-answer assertions preserved.

Final Android result: **92 passed, zero failures/skips**. Full controller and
persistence classes plus ten native UI cases include a parent beyond the first
work window, refresh reuse, ambiguity from an already-processed conflicting alias,
and 1,610 sources crossing the API26 bind limit. Existing source/cached/saved
provenance, publication guards, local parent inspection and language-view tests
remain covered. These are isolated fixtures, not physical-phone qualification.
Debug/release/test builds pass; lint has **zero errors and 145 warnings**.

Matched 2B/4B after runs preserve all three answers under their actual questions
with zero generation across initial/resumed/noise/update stages. Both use the same
frozen Java code, fixtures and native binary at 2K/F16/Vulkan; 915 frozen production
class files match the compiled code. Bounded literal rendering proves deterministic
integration, not model reasoning or general semantic accuracy. No timing, memory
or energy claim is made during concurrent engineering.

The same audit snapshot contains eight multipart cases authored before their
model execution. An audit-only control disables the whole-comment screen to test
the existing passage selector. No alternative recipe is promoted to production
from these examples. Results, limitations and the next comparisons are kept under
`LiberBuild/inference-kv/context-order-0.35.16/`, alongside the before-fix core,
Android and native evidence and the delivery receipt.

## Explicit reply meaning — 0.35.15 / code 63 (2026-10-20)

Short and deictic findings retain explicit parent frames in the brief and native
Findings. Identical answers to different questions remain separately attributed;
exact retained questions already quoted in complete answer frames are not printed
again. Known closed answers to already-retained, complete, certain questions can
be preserved deterministically. That check binds the parent's own English reading
quotation, with its original-language evidence left intact. A single paraphrased
finding also keeps its parent instead of escaping through the one-leaf shortcut.

Publication snapshots capture a normalized frame registry and distinct original
parent copies. They use only cached checked English readings, mark unavailable
English preparation, preserve source limitations, and atomically reject changed
parents/reading views. Parent processing bookkeeping alone remains reusable.
Saved local snapshots retain their old parent even after working-source edits and
SQLite reopening. The native “Read earlier reply” action uses the captured copy
without network access. Ordinary cache accounting includes these JSON payloads.
No database schema, networking, root requirement, model window or KV default changes.

Final core result: **1,447 total, 1,446 passed, zero failures/errors, one optional
host-native skip**. Eight reply-context cases cover different questions with the
same answer, unknown parents, shared frames, independent long findings, safe
Unicode truncation/uncertainty, closed-answer model-call avoidance, a lone
paraphrase, and original/English quotation binding. Hierarchical tests additionally
check that changed parent text reaches composition and invalidates old receipts.
The initial short-answer assertion is a real before-fix failure. The other initial
fixture returned the wrong response schema when a missing parent bypassed screening;
that fixture was corrected before its positive regression, and its first failure
is not presented as proof of the missing-parent display bug.

Final Android result: **89 passed, zero failures/skips**, full Context controller
and persistence classes plus ten native UI cases. These include two independently
framed “No” answers, unchanged refreshes, original Spanish/English parent storage,
missing preparation, saved parent inspection after a later edit, atomic publication,
legacy/model changes, 805 cited sources, reopening and accounting. The framed
finding and local-source screens were inspected. These are isolated fixtures,
not a physical-phone, external-source or translation-quality admission.

Evidence: `LiberBuild/inference-kv/context-replies-0.35.15/` including original
red/fixture logs, final core XML, Android release-v2 log, native screen captures
and delivery receipt. Further model and release observations are recorded there.

## Supporting-review cache identity — 0.35.14 / code 62 (2026-10-20)

**Core: 1,439 tests, 1,438 passed, zero failures/errors, one optional host-native
skip. Android: 85 passed, zero failures/skips.** Two red core cases reproduced
reuse of an old compaction after a source qualification or reply-parent context
changed while selected words and quotations stayed identical. The corrected
Android reproduction also failed on the prior 0.35.13 app. Its first fixture
assertion assumed stable row order after re-ingestion; the corrected comparison
uses finding IDs, then exposes the intended missing recomposition. Both failed
logs remain alongside the final results.

Compaction and publication now bind retained findings to their supporting review units.
Those unit IDs bind source spans, model/recipe and supplied post/parent context.
Compaction receipts propagate changed review identity through ancestors;
an unaffected branch is reused. Atomic publication admission checks the captured
review identities too, including a re-reviewed parent with unchanged child text.
Only review units supporting retained findings participate: empty tail reviews,
reaction-only input and unit ordering do not create new published meaning.
The publication/saved-view JSON field is optional for legacy data; source pages,
translations, cursors and earlier/saved views survive recipe rebuilding. No
schema migration, additional acquisition request or model input field is added.
These are validity checks, not proof that a model noticed every qualification.

Full controller/persistence and nine native UI cases pass, including unchanged
refresh/copy reuse, changed reviews, legacy/model transitions, saving during
inference, 805 sources and SQLite reopening. Earlier-publication and local-source
screens were inspected. Debug/release/instrumentation builds pass; release lint
has zero errors and 145 warnings. Evidence:
`LiberBuild/inference-kv/context-review-0.35.14/`.

Actual native 2B/4B 2K/F16/Vulkan runs share frozen Java, fixture and native code.
Both preserve every expected source passage in the two bounded lift/battery
cases, including uncertain witnesses, distinct times, rude 56-versus-65 Wh
correction, conditional shipping and a later batch. They make one/two initial
relevance calls respectively and no generation on subsequent recorded stages.
Code renders the exact attributed excerpts; this is not a broad generative,
phone-performance or whole-thread certificate. No inference default changes.

The completed separate raw relevance experiment logs 270 decisions. On 4B,
boolean matches 18/24 earlier and 20/20 newly predeclared cases; contribution
roles match 23/24 and 19/20, adding one noisy comment in each split while
recovering useful ones. On 2B the role format adds substantially more noise.
Discard-reason prompts lose useful material on both. No alternative is promoted.
These isolated decisions exclude complete-engine question/background safeguards,
passage selection and composition; synthetic criteria are not statistical
accuracy estimates. Frozen inputs and all failures are retained under
`LiberBuild/inference-kv/context-relevance-v1/`. The next semantic evaluation
must cover complete extraction routing and context-dependent short replies.
Full Context and original native inference/KV requirements remain unfinished.

## Consistent Context publication — 0.35.13 / code 61 (2026-10-20)

**Core: 1,436 tests, 1,435 passed, zero failures/errors, one optional host-native
skip. Android: 82 passed, zero failures/skips.** The red Android reproduction
changed a cited comment while composition was awaiting a model reply. The old
publisher checked the merged post revision, then accepted the outdated prose
with a newer source manifest. The fixed controller retains the previous brief,
its original local citations and the saved snapshot, then publishes a coherent
replacement after the changed source is reviewed.

Composition now captures findings and cited documents consistently, including
source labels, dates, scope and quality. Publication compares the captured
findings, source interpretation and processing policy inside the same SQLite
transaction that writes the publication. It retains current saving/scheduling
choices instead of overwriting them with a record captured before inference.
Retrieval-only provenance changes remain admissible. Tests cover another helper
editing the database, changed review findings and policy with unchanged source
words, fresh citation provenance, saving during inference, 805 cited sources
across bounded queries, SQLite reopening and unchanged accounting. There is no
schema change or extra network/model request.

The full controller/persistence run plus nine selected native UI checks retain
old local excerpts, saved/latest views, AI-off controls, source dates and
refresh/stop. Actual earlier-publication and local-source screens were inspected.
Debug/release/instrumentation assemblies pass; release lint has zero errors and
145 warnings. The isolated test emulator had stopped; it was relaunched using
its existing state after confirming its process and ADB surface were absent.
An initial launch used the AVD data directory instead of its INI directory; that
failure and the original red test remain in the evidence. The live preview was
not used as the test fixture. Evidence:
`LiberBuild/inference-kv/context-consistency-0.35.13/`.

A separate local-only relevance audit freezes three decision formats and 45
synthetic comments before execution: one discovery case, 24 previously used
cases and 20 newly predeclared cases. The complete 2B run shows a tradeoff:
contribution labels recover useful reports but admit more noise; explicit
discard reasons lose substantial useful material. Its production boolean prompt
matches 19/20 new cases but misses a limited water-report observation. These are
isolated decisions, not complete-engine scores: question/background safeguards,
passage selection and composition are outside this probe. The controlled 4B
comparison is still running at packaging; no complete result is claimed here.
No alternative prompt is promoted. Native/runtime/fixture identities and all
outputs are retained under `LiberBuild/inference-kv/context-relevance-v1/`.
The full Context and original native inference/KV goals remain unfinished.

## Source scope and fair date previews — 0.35.12 / code 60 (2026-10-20)

**Core: 1,436 tests, 1,435 passed, zero failures/errors, one optional host-native
skip. Android: 76 passed, zero failures/skips.** Red cases reproduced loss of the
latest and undated publication contexts beyond a four-row prompt, media/quality
scope disappearing at the final compaction level, and caption cue positions
being mistaken for content correction values. The fixes retain typed extraction
facts and complete temporal identities, use earliest/latest/undated representatives
plus aggregate bounds, and exclude only extractor-marked leading media cues from
the limited correction guard. Real content quantities remain protected. Changed
source-role associations and instruction modes cannot reuse old receipts.

The full Android controller/persistence suite and eight selected native UI cases
include source-type changes with identical words, 64 merged editions, local
caption limitations with zero gateway traffic, expandable edition dates,
earlier publications, saved snapshots, AI-off controls and refresh/stop. The
initial new UI assertion requested an exact text fragment while the node held
the complete sentence; the fixture now asserts the complete intended prose.
Its failed log is retained. Actual caption-scope and expanded-date native
screens were inspected. Debug/release/instrumentation assemblies pass; release
lint has zero errors and 145 warnings. Exact DEX owners confirm both APKs contain
the new observation type, local description and fair date frame, absent from
0.35.11. Evidence: `LiberBuild/inference-kv/context-scope-0.35.12/`.

Actual micro-model narrative composition remains imperfect: extra scope
instructions regressed the 2B conditional QA claim, and data-only composition
still omitted a later shipping delay. The added narrative guidance is disabled
in production. A separate test-only reading-budget composer preserves all 12
workshop contributions exactly on both 2B and 4B, with the later delay first and
no update/resume generation. The library case preserves all ten contributions
on 4B; 2B's relevance review still omits one useful firsthand report. This longer
reading output is not an equal-length summary or a production default. Frozen
code/fixture/native hashes match across models. Concurrent builds preclude
comparative speed claims; physical-phone quality and performance remain open.
The full Context and original native inference/KV goals remain unfinished.

## Source publication context — 0.35.11 / code 59 (2026-10-20)

**Core: 1,421 tests, 1,420 passed, zero failures/errors, one optional host-native
skip. Android: 73 passed, zero failures/skips.** A pre-fix reproduction accepted
identical “tomorrow” wording on different publication days as extra provenance.
Coverage now includes normalized instants, missing/invalid/unzoned dates, all 64
bounded edition names and unknown dates across metadata chunks, higher-level
compaction identities, changed dates with unchanged finding words, failed source
re-review and resume, old published citations, saved copies, and unchanged refresh
and matching-copy reuse. Native publication/retrieval and expandable known/unknown
edition screens were inspected. Debug/release/instrumentation assemblies pass;
release lint has zero errors and 145 warnings.

Exact DEX owner/field/method inspection confirms the publication codec and pending
review admission are included in both candidate APKs and absent from 0.35.10.
The audit's frozen-code comparison option verifies classpath, fixture and native
binary hashes. Negative controls reject changed classes or fixture bytes before
creating an output or accessing a provider.

The final Android suite corrects two initial fixture assumptions: finding order
can change after a source is re-ingested, and an exact-copy control must use the
same publication instant. A malformed legacy-source test also needed its intended
post kind. Failed logs remain alongside the final results. Evidence:
`LiberBuild/inference-kv/source-time-0.35.11/`.

Actual native 2B and 4B packs at 2K/F16 on desktop Vulkan pass a bounded dated
source case. They preserve two known UTC anchors, undated attribution, conditional
reopening, a limited visitor observation and a later inspection delay. Each makes
one initial relevance call, with no generation on resume, noise, update or a
matching-time copy. Code renders the exact contributions and dates; these are
not generative-calendar, general-thread or physical-phone quality certificates.

Separate 8K/F16, group-16 runs of the pre-change composition code complete both
long jobs on 2B and 4B with the exact same frozen code/fixture/native binary. Both
still fail semantic criteria: 2B omits an essential capacity correction and later
asserts conditional QA as fact; 4B invents capacity disagreement and changes a
limitation of captions into a limitation of the video. Concurrent builds preclude
comparative wall-clock claims. The runs change no model/window/position/cache
default. Full Context fidelity and original native inference/KV scope remain open.

## Context model/cache binding — 0.35.10 / code 58 (2026-10-20)

**Core: 1,410 tests, 1,409 passed, zero failures/errors, one optional host-native
skip. Android: 68 passed, zero failures/skips.** The pre-fix Android reproduction
failed because a newly selected model did not reconsider old reviews. The fixed
suite checks changed model/window/recipe, unchanged diagnostic metadata, repair
from current-profile compaction receipts, unchanged refreshes, exact additional
copies, saved data and SQLite reopening after a failed replacement review, and
a model switch during composition. Previous prose cannot be attributed to the
new model. Full controller/persistence coverage and selected native citation,
saved-view, AI-off and refresh/stop screens pass; the actual dark earlier-brief
and source-excerpt screens were inspected. These use injected models and sources.
No remote-model semantic or physical-phone performance claim follows.

Debug, release and instrumentation assemblies pass. Release lint has zero errors
and 145 warnings. Exact DEX owner/field inspection confirms both tested candidate
APKs contain the new Context binding fields, absent from the previous preview.

Evidence: `LiberBuild/inference-kv/model-binding-0.35.10/`. An initial fixture
compile caught a missing suspend callback annotation; the corrected Android
suite above passes. The separate 9B wider-group composition experiment timed
out without a returned overview. It remains unqualified and changes no default.

## Bounded inference transport — 0.35.9 / code 57 (2026-10-20)

**Core: 1,409 tests, 1,408 passed, zero failures/errors, one optional host-native
skip. Android: 15 passed, zero failures/skips.** A controlled 16-second response
header delay reproduced the old socket timeout, then passed with the fix. The
real Android gateway also reads a body delayed beyond 15 seconds. Public-client
timeouts remain unchanged; provider cancellation, credential/redirect scope,
local-host grants, Tor isolation, user-controlled provider routing, encrypted
keys, export exclusion, AI-off controls and observed-window UI are covered.
Native provider and Context-window screens were inspected. Debug/release/test
assemblies pass; release lint has zero errors and 145 warnings. Evidence:
`LiberBuild/inference-kv/inference-timeout-0.35.9/`.

One earlier provider-settings run saw a traffic counter increase by one. The
isolated case passed; the exact originating request was not captured. The offline
fixture now closes both default and search-Tor routes and retains host/stage
diagnostics without weakening its no-network assertions. The final full suite
above passes. Its delayed-response check passed in the earlier run too.

The authorized Mimir experiment now reaches a response-token-limit failure after
more than 15 seconds, instead of being cut off by the socket timeout. This proves
transport progress, not successful semantic composition. Its underlying token
exhaustion remains unresolved. Source-local 2B/4B composition experiments also
retain scope/actor errors or exceed the fixed overview allowance; they are not
production defaults. The pinned current host Vulkan runner builds, passes 5,000
CPU transform checks and completes the two short real 4B Context jobs. Desktop
Vulkan execution is not physical-phone performance or compressed-cache admission.

## Additional source-copy reuse — 0.35.8 / code 56 (2026-10-19)

**Core: 1,399 tests, 1,398 passed, zero failures/errors, one optional host-native
skip. Android: 60 passed, zero failures/skips.** The controller exercises an
actually generated brief, an identical extra source with another author,
manifest publication and SQLite reopening without fresh extraction/composition.
A changed qualification triggers composition. Full controller/persistence suites
retain source edits, changed speakers, saved publications, resumable receipts and
alias migration coverage. Two native citation checks include the new author's
own local quotation and authentic outgoing link. The dark screen was inspected.
The first new UI assertion expected a bare name rather than the actual name
and platform label; its failed log is preserved and the corrected final suite
passes. Debug/release/instrumentation assemblies and release lint pass with zero
errors and 145 warnings. Evidence: `LiberBuild/inference-kv/copy-reuse-0.35.8/`.

Separate test-only source-selection work now protects the explicit calendar
correction in the bounded 2B library fixture. Coverage remains incomplete and
the production composition default is unchanged. The concurrent 4B CPU exchange
timed out; a Vulkan attempt found its current pinned runner not built. Neither
is semantic admission. A real one-second negative control now proves that the
audit kills a timed-out unnumbered native transport instead of risking late
responses being consumed by the next case. See
`inference-kv/source-selection-protected-review-v2.json`. Physical-phone
performance and the remaining native-inference packet are still unqualified.

## Source quotation recovery — 0.35.7 / code 55 (2026-10-19)

**Core: 1,384 tests, 1,383 passed, zero failures/errors, one optional native
skip. Android: six passed, zero failures/skips.** The quotation regression uses
an actual provider response: exact fragments around an ellipsis recover the
original intervening context. SQLite reopen/reuse, source edits, retry behavior,
AI-hidden controls and native local citations passed. Release/debug/test builds
passed; release lint has zero errors and 145 warnings. Native screens were
inspected. Build/test commands now serialize shared class outputs after a
preserved concurrent-build failure; the final serialized run passed.

Long-discussion experiments exposed further summary errors in 2B and Mimir
routes despite completed jobs and retained source findings. Wider grouping and
larger response allowances remain research controls. They are not promoted as
quality improvements. See [the ledger](docs/AI_FOUNDATION_LEDGER.md) and
`LiberBuild/inference-kv/quote-omission-0.35.7/` for exact evidence and limits.

## Incremental Context qualification — 0.35.6 / code 54 (2026-10-19)

The complete core suite passed **1,380 tests with zero failures/errors and one
optional native skip**. **47 Android checks passed without failures/skips**:
41 on the source-selection candidate, then six after the counted attributed
excerpt path. Actual 2B inference retained an uncertain witness, reused source
receipts and composed four attributed exact-source contributions with one
relevance generation and no additional rewriting call. Native reader and local
source-excerpt screens were inspected. Release/debug/instrumentation builds
passed; release lint reported zero errors and 145 warnings.

The frozen real-ContextEngine 2B F16 jobs retained uncertainty, conditional
shipping, corrected capacity, firsthand attribution and a later development.
Identical and reaction-only refreshes made zero new generation calls. Earlier
2B/4B controls demonstrate that completed summaries can still mix speakers or
correction targets; those failures remain preserved. Compact extractive ledgers
now use counted attributed source prose rather than another weak paraphrase.
Larger generated overviews still require semantic evaluation.

Evidence is under `LiberBuild/inference-kv/live-0.35.6`,
`inference-kv/android/{coherent-source-v1,attributed-source-v2}` and
`inference-kv/pipeline-jobs`. This is a laptop/emulator checkpoint, not physical
phone, general semantic or speculative-decoding qualification. Full required KV
work remains in progress; see [the ledger](docs/AI_FOUNDATION_LEDGER.md).

## Translation release qualification — 0.35.5 / code 53 (2026-10-19)

**Final core: 1,366 tests, 1,365 passed, zero failures/errors, one optional
host-native skip. Final Android: 79 successful executions, no failures/skips.**
The Android total comprises a live-acquired five-language/offline-reading fixture,
three detector/cache/actual-2B checks, an actual-4B check and 74 reader,
persistence, capability and native-UI regressions on isolated API 35 x86_64.
Debug/release/instrumentation builds pass. Debug lint has zero errors and 147
warnings. The final native language-settings screen was inspected.

German, French, Ukrainian, Arabic and Japanese packs were acquired through the
app's NetworkGateway, then translated with networking blocked. Each preserved
the 56 Wh versus 60 Wh correction, the source URL and its exact complete original
citation. Both real compressed Bonsai profiles independently received English
from Spanish sources, retained the correction and unanswered compatibility
question, preserved original Spanish citations and reused source receipts.
Interrupted validated passages and reopened completed views were reused with
translation disabled. These targeted examples are not multilingual accuracy
scores or proof for every one of the 57 catalog directions.

Qualification fixed three additional integration faults. A fluent short English
translation led the next detector candidate by over 3x but scored only 0.3699;
the verified English-output check now admits a bounded relative-confidence case
without weakening unknown-source selection. A distinctive short Japanese label
was missing from the detector's top three; a conservative kana/Han check handles
that ambiguity, excluding shared Han alone and isolated quoted kana. Finally,
ignored code tags fragmented sentences around quantities, while ordinary spans
were duplicated/misassigned by approximate HTML word alignment. Numeric arguments
now remain in the sentence and a separate ordered quantity/unit manifest checks
them before checkpointing. Changed values, units, order, duplication, additions
and a dropped Unicode minus have rejection regressions. Code and links remain
opaque and must return unchanged. English CJK sentence spacing retains exact
original citation ranges. Unverifiable formatting changes remain pending.

The policy is `english-reading-v3-fasttext-8f3472cfe873`; earlier incompatible
reading work is not reused. Summary receipts and publication reuse also bind
original/English quotations and speaker associations, including changed
non-leading/late evidence. Tests first reproduced stale reuse, then verified
ancestor invalidation and retention of unchanged branches. A native controller
fixture exercises the complete publication path with unchanged finding words.

The universal release is 361,758,980 bytes versus the previous 345,540,246 bytes:
an increase of 16,218,734 bytes (4.69%). Language packs remain separate downloads;
Spanish is 37,014,089 bytes. The APK includes both ARM64 and x86_64 translation
libraries. All eleven pinned translation source archives passed preflight hash
checks for inclusion in the corresponding source package. No experimental
Leanmt/fxtranslate runtime or EuroNano model replaced the current engine.

Evidence: `LiberBuild/translation-research/release-checks/qualification.json`,
all four final Android logs and their JSON receipts, final native screens,
`source-preflight.json`, `apk-content-check.json`, and
`final-multilingual/release-qualification-build.log`. Initial and intermediate
failures remain in `final-multilingual/`; a separate overlapping-build test-report
collision was excluded and superseded by the fresh complete core run. Main
preview upgrade and packaged-artifact hashes are recorded separately in
`translation-research/main-upgrade/` and `artifacts/`.

Physical ARM runtime/thermal behavior and a fresh API 26 language-runtime run
remain unverified. Prior native library alignment/network-import audits apply
to the unchanged translation binaries. General Context semantic shortcomings
remain open; this release does not claim that small-model interpretation is
perfect or that the packet's required advanced KV work is implemented.

## Initial offline Context languages — 0.35.5 / code 53 source qualification (2026-10-19)

Context prepares a separately stored English reading view before text and
embedding inference. Source text and original-language citations remain intact.
Signed, size/hash-verified language data is acquired through NetworkGateway;
the private translation worker has no acquisition authority. Language packs are
data-only downloads. Explicit packs are protected; automatic packs have bounded
admission and expiry. Unknown, unavailable or invalid translation remains pending
rather than becoming a successful no-information review.

**Final core: 1,358 tests, 1,357 pass, zero failures/errors, one optional host-native
skip. Final Android: 72 successful executions on the isolated API 35 x86_64
device.** These comprise detector (1), persistence (23), interrupted/complete
translation cache (1), actual Bonsai 2B (1), reader/UI (45), and a subsequent
fresh final-source Bonsai 4B check (1). Both acquired compressed models preserved
the numerical correction, retained an unanswered compatibility question, cited
the original Spanish and reused completed receipts. These small fixtures do not
establish general semantic accuracy or repair the earlier English relevance and
indirect-consequence failures. No Mimir inference was used in this qualification.

The native translation probe checks recovery from invalid model initialization
and subsequent successful translation in the same worker. Android cache checks
interrupt after a committed passage, resume with translation disabled, and reopen
the database to reuse a completed view with translation disabled. This separately
proves passage checkpoints and complete-cache reuse. Native language settings
were inspected in the final build; the inspected screen is retained at
`translation-research/final-checks/native-screens/context-ui/context-languages-dark.png`.

Fixed failures are retained with their original evidence: HTML restoration
aborted without requested alignments; stale reader state overwrote its processing
policy marker; recipe sorting prevented complete translated-cache hits; a short
English detector result incorrectly remained pending; and an otherwise verbatim
2B quotation changed only its initial letter's case. The quote validator recovers
only a unique original span with an initial ASCII-case difference, at least 12
characters long, and every remaining character exact. It still rejects changed
words, numbers, punctuation, internal casing and ambiguous source copies.

Debug, release and instrumentation builds plus debug lint pass. Native x86_64
and arm64 language libraries were rebuilt, audited for 16 KiB load alignment and
checked for absent networking imports. Physical ARM inference, thermal/memory
pressure and all 57 catalog directions remain unqualified; only Spanish was
exercised end-to-end, with detector fixtures also covering English, Arabic and
Japanese. No fresh API 26 language-runtime execution is claimed. An intermediate
UI run observed one global network-counter increment without a captured cause;
that failure remains unexplained and retained. The final complete 45-check
reader/UI run passed.

This is source and isolated-device qualification, **not a published preview
upgrade**. Main emulator/preview and published artifact aliases remain 0.35.3.
No experimental translation engine or EuroNano checkpoint was integrated.
The deeper metadata/fork/paper scrub and actual alternative-engine probes are
documented in [Context evaluation](docs/CONTEXT_EVALUATION.md#translation-research-beyond-the-usual-shortlist--2026-10-19).

Evidence root:
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/translation-research/`:
`build-language-final-fixes.log`, `build-language-final-tests.log`,
`final-checks/`, `final-4B/`, `bugs-verified/native-language-audit.json`,
the retained earlier failures and `deep-scrub/summary.json`.

## Context source relationships and complete input — 0.35.3 / code 51 (2026-10-19)

Context keeps the merged post once and binds comment reviews to that full source plus explicit reply parents. Native Reddit, Bluesky and observed X JSON relationship IDs are retained alongside canonical public URL aliases. Attested out-of-order replies remain eligible without guessing their parent; a branch does not admit unrelated siblings from the same conversation. Missing or ambiguous parents stay unavailable. A model's no-signal decision cannot complete a review with explicitly missing background. Known deleted comments use a deterministic tombstone receipt.

The planner first counts a complete bounded source/post/parent request with output and protocol headroom reserved. It uses that input when it fits, including on a 2,048-token native worker. Fallback frames fairly share prefix excerpts and retain truncation/uncertainty flags. The full stored background fingerprint binds receipts even when an edit falls beyond the visible excerpt. Identical replies to different parents require contextual review. Whole known findings retain their late qualifications when selected for comparison. These changes improve supplied evidence, not model judgment.

The version-4 dependency index invalidates affected reviews after parent changes, deletion or late arrival while preserving published briefs and saved citations. URL/fetch-time provenance and a merged post's changed earliest-edition date do not replay unchanged caption interpretation. Real version-3 migration preserves saved records, receipts, publications and logical-byte accounting. Cleanup captures candidates before waiting on the post-cache query and conditionally deletes only unchanged, unprotected candidates; regression fixtures create, update and protect records during that wait. New nullable relationship metadata remains optional in legacy saved imports and is strictly typed when supplied.

**Fresh core result: 1,320 tests, 1,319 passed, zero failures/errors, one optional host-native SSE skip.** The native binaries are unchanged from 0.35.2, which separately exercised Android SSE. **209 successful Android executions cover 135 distinct methods, with zero failures/skips in those recorded runs:** 73 final Context checks on API 35, 72 on API 26, 61 reader/storage/portability regressions, one settled native-citation capture and one actual acquired-model fixture each for 2B/4B. The reader regressions cover source/storage code unchanged by the final Context packing adjustment; the final Context suites and both model fixtures cover that adjustment. Each real-model fixture retains the complete fitting parent with its late connector qualification, preserves the 56 Wh correction with an exact quote, reuses unchanged work and processes an edited background again. Native capacity settings and citation screens were inspected on API 35/26. A transitional API 26 capture was replaced by a settled native capture with temporary test animation scales restored afterward. Debug, release and instrumentation APK builds pass; final debug lint has zero errors and 145 warnings.

Earlier failures are retained: an unchanged caption replayed after provenance changes; an old cache snapshot deleted a newly created Context; and strict saved import rejected the new nullable fields. Their fixes have targeted regressions plus the successful suites above. One Back-key timeout passed unchanged in isolated and subsequent complete runs. A release packaging failure passed a targeted retry and subsequent coherent builds; its cause was not established. Raw initial failures and corrected runs remain in the evidence.

All 30 native libraries match 0.35.2 byte-for-byte. All native ZIP data and 64-bit ELF load segments retain 16 KiB alignment. Four legacy 32-bit Tor/libc++ entries retain 4 KiB ELF alignment; there is no new 32-bit AI runtime. Corresponding pinned sources and GPL notices remain part of the source package. Consumer features need no root, ADB, custom ROM or privileged installation.

Semantic qualification remains ongoing. Both small models promoted noise and repetition in the 34-case paired-format experiment. One 2B response turned a Spanish question into an assertion despite quoting the question. Mimir's labels and output-budget behavior also varied; a two-case thinking-disable control improved latency but lost a useful question. No experimental semantic or provider protocol was promoted. The selected server reports a configured 262,144-token request window, not free KV-cache memory or a verified architecture. Physical ARM GPU/thermal behavior and live social-platform availability are not requalified by this round. See [Context evaluation](docs/CONTEXT_EVALUATION.md) and [the source-flow guide](docs/CONTEXT.md).

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/context-foundation/context-background/` — `build-complete-input.log`, `validation-summary.json`, final `android-context-fit-*` runs, reader regression/capture logs, actual `android-background-fit-*` reports, `comparison-summary.json`, native audit, settled native screens and separate main-preview upgrade/package receipts.

## Context instruction reuse — 0.35.2 / code 50 (2026-10-19)

The private native worker can reuse complete sequence snapshots of the two exact compiled Context instructions. Snapshots precede all reader-supplied tokens and include hybrid recurrent state. Prompt boundaries are checked against actual token IDs and aligned to unchanged 32-token prefill batches; every request still starts with a fresh sampler and clears its live working context afterward. Both manager and worker gate eligibility. Altered instructions, arbitrary text utilities and caller flags alone cannot enter the cache. At most four entries share a 128 MiB payload ceiling and one sixteenth of available RAM plus the existing reclaimable cache. Memory pressure, a zero budget or worker release clears it; undersized snapshots fall back to fresh prefill. No snapshot is written to disk.

Capacity remains independent: active runtime/server metadata controls the working window, output reserve and compaction. Smaller reported local windows now reserve at most one quarter for output instead of consuming the whole input allowance. Actual Android 2B/4B workers report 2,048 tokens; the selected live hosted model again reports 262,144 through NetworkGateway. Settings alone make no metadata request. Fixed-instruction reuse reduces prefill work and never enlarges the context budget or grants tools to a model.

**Fresh core result: 1,304 tests, 1,303 passed, zero failures/errors, one optional host-native SSE skip.** Actual Android SSE execution passed separately. **130 final successful Android executions cover 67 distinct methods**, with zero failures or skips: 63 Context checks on API 35, 62 on API 26, one real cache fixture for each acquired 2B/4B model, and three native worker regressions. Those model fixtures compare cold/warm answers byte-for-byte, change the source from 56 Wh to 72 Wh, confirm only 74 of 266 prompt tokens require fresh prefill on a hit, deny modified instructions, clear cached bytes and recover after cancellation. Native settings and local-citation screens were inspected on both Android versions. Debug/release and test APK assemblies pass; final debug lint has zero errors and 145 warnings.

One initial API 35 suite run did not reflect a requested global accessibility Back action. The unchanged fixture passed alone. The test now sends Back to the focused Android window, and both final complete suites pass. The original failure and isolated control remain recorded. No production dismissal handler was changed.

Twelve isolated cold/warm comparisons across consistent host 2B CPU, 4B CPU and 4B Vulkan configurations were byte-identical. Seven additional trials against the actual production cache on desktop Vulkan pass parity, budget shrink, eviction, repopulation and clearing. The rebuilt ARM64/x86_64 text libraries match their packaged outputs and expose no socket imports; the other 28 native libraries match 0.35.1. All 30 native ZIP entries align to 16 KiB, as do all 64-bit ELF load segments. Four unchanged legacy 32-bit Tor/libc++ ELF entries retain 4 KiB alignment. Physical phone GPU performance and thermals remain unqualified.

Semantic qualification remains ongoing. The 20-case holdout exposed missed useful questions/indirect context and an unsupported safety interpretation even in syntactically valid responses. Separate relevance checks matched 19/20 labels for both small models; joint relevance/repetition fell to 17/20 for 2B and 14/20 for 4B. Raw labels are not end-to-end app acceptance or fidelity scores. No experimental semantic protocol was promoted. See [Context evaluation](docs/CONTEXT_EVALUATION.md). This round does not requalify live social-platform availability.

The non-debuggable release upgrade retained UID 10116 and all 52 private files byte-for-byte before first launch, including settings, databases, saved files and no_backup. Its installed SHA-256 matches the built release: `d41ff09e7872a4c1f395124baa5dfa69d6705aa9c41ac79dd4738d931f7f9e54`. Tools, test devices, model trials, builds and evidence use build storage; the system filesystem retains more than 15 GiB free. Darwin runtime/profile state was not changed.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/context-foundation/context-selection/` — `validation-summary.json`, `android-context-final-*.log`, `android-cache-*`, `android-worker-regression.log`, `heldout-*-assessment.json`, `prefix-parity-summary.json`, `production-cache-summary.json`, `native-release.json`, `native-screens-final/` and `main-upgrade/`. Private retention snapshots are restricted.

## Context budgets and receipts — 0.35.1 / code 49 (2026-10-19)

Each native Context job now reads its selected worker's actual allocated window before planning. The current 2B and 4B runtimes both report 2,048 tokens. Selected remote metadata retains its bounded privacy-scoped cache; absent metadata retries during authorized jobs after five minutes. Combining an active slot report with a smaller reported input limit now retains the smaller restriction. Reports are bound to the selected model again before constructing its budget. HTTP 400/413 inference rejection pauses with sources/checkpoints intact and invalidates metadata for the next request, without automatically replaying the rejected content or switching provider. The live Mimir path again reported 262,144 tokens and reused three completed receipts on refresh.

A controlled model experiment exposed useful information outside the finding ledger: a small model placed the wheelchair-access addition in a free-standing `note`. The v5.1 validator now leaves unexpected root/finding payload, contradictory review flags and non-string prose pending instead of silently committing a partial interpretation. Repeated-finding references require exact supplied aliases. Regression cases cover these failures without depending on model inference. The extraction instructions and native sampling profile remain unchanged.

**Fresh core result: 1,301 tests, 1,300 passed, zero failures/errors, one optional host-native SSE skip.** **129 successful Android executions cover 64 distinct methods:** 63 Context checks on API 35, 62 on API 26, actual native 2B and 4B checks, and two final capacity-screen followups. Final runs have zero failures/skips. The 4B model correctly hit memory admission at the initial roughly 2.5 GiB emulator allocation; it passed after the isolated device was restarted with a 4 GiB allocation. The tiny-model tests verify actual native window diagnostics, retained numerical correction, counted output reserve and receipt reuse, not broad semantic accuracy. Native capacity, reader and local-citation screens were inspected on both Android generations; the API 26 IME capture was allowed to settle before its final screenshot.

The versioned 14-case synthetic collection compares current instructions, two shorter recipes and a passage selector using the acquired 2B/4B models and the authorized hosted model. A separate 2B comparison tries greedy decoding without presence penalty. None of the alternatives justified promotion. The hosted current-recipe run passed all 14 fixture screens, but small-model runs still misclassified noise, repeated known facts or omitted useful additions. A 512-token hosted trial exhausted output on reasoning in 37/42 calls, reinforcing the separate remote output reserve. These are focused experiments with inspectable raw evidence; general semantic reliability, physical-phone GPU/thermal behavior and live social-platform availability remain unqualified. See [Context evaluation](docs/CONTEXT_EVALUATION.md).

Debug/release and instrumentation APK assembly passed; debug lint has zero errors and 145 warnings. All 30 native binaries match the previously audited release and retain uncompressed 16 KiB APK data alignment. The pinned Kotlin daemon registry path was corrected and checked against its actual runtime getter so future build state stays on build storage. The non-debuggable release upgrade retained UID 10116 and all 52 private records byte-for-byte before first launch; its installed hash matches the built APK. Darwin runtime/profile state was not changed.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/context-foundation/context-refinement/` — `build-final.log`, `validation-summary.json`, `native-context-final-*.log`, `native-phone-final-*`, `native-window-final-*`, `comparison-assessment.json`, `native-release-final.json`, `native-screens-final/` and `main-upgrade/`. Private retention snapshots are restricted.

## Context — 0.35.0 / code 48 (2026-10-19)

Optional Context reads the displayed merged post, available public discussion streams, first-hop linked pages and available captions. App code controls acquisition, routing, budgets, continuation and storage; models receive no tools. Readability4J extracts ordinary pages locally. Captions and embedded PDF text precede explicitly enabled local OCR/speech processing. All app exchanges use NetworkGateway, including selected model metadata and optional pack downloads; selected Tor fails closed. AI-off reading, chronological feeds and ordinary filters remain independent.

The engine adopts the selected endpoint's reported active window, not a parameter-count or training-limit guess. The attached Mimir record reports 262,144 tokens; the actual local text runtime allocates 2,048. Local requests count the real formatted template; unknown remote tokenizers use conservative UTF-8 budgeting. Native JSON-object constraints enforce syntax, while exact source quotes, validated references, durable per-unit receipts and hierarchical summaries preserve inspectable evidence. Completed unchanged material is reused; malformed model answers remain visibly unreviewed while later sources advance. Short ledgers are assembled deterministically without a second paraphrasing pass. New publications wait behind Updates ready.

Read-ahead and saved automation default off. Saved automation includes untouched local saves, scans at most 32 bookmark keys, processes at most three owners within one shared allowance and rotates fairly with retry floors. Verified new source membership can extend a previously read sibling without fetching or interpreting it again. Dated locally saved threads can supply evidence when fresh routes fail; they never count as a successful live check, replace newer cached replies or introduce unrelated roots. Bounds and incomplete coverage remain visible. Ordinary data expires with the bounded reader cache; saved Context and its first protected publication survive ordinary eviction. Inactive temporary media expires after 24 hours. Consumer features require no root, ADB or privileged installation.

**Fresh core result: 1,297 tests, 1,296 passed, zero failures/errors, one optional host-native SSE skip.** Actual Android SSE parity passed separately. **208 successful Android executions cover 145 distinct methods**, with zero outstanding failures/skips: final Context plus real installer suites pass 61 on API 26 and 62 on API 35; 69 reader regression checks pass on API 26; 15 real model/media checks and an additional 4B Context profile pass on API 35. Debug/release and instrumentation APK assemblies pass; debug lint reports zero errors and 145 warnings. Earlier failures are retained in the evidence alongside the repaired runs.

The tests exposed and repaired a real reading-anchor edge: LazyColumn can report an offscreen preceding row when only its spacing intersects the viewport. Checkpointing, observed anchors and read-ahead visibility now share actual card-overlap geometry, retaining partial cards and negative content padding. Exact identity/pixel regression checks pass. Citation gesture fixtures now scroll their actual targets into view; their earlier API 35 failures were offscreen test taps. The real optional-tool install also exposed an invalid Chinese pack slug; the signed catalog data is corrected and core tests read the actual catalog. No model URLs, sizes or hashes changed.

Real public English OCR installs were exercised through the production manager on both Android versions: signature/hash verification, native activation, accurate synthetic text with source SHA, disable while retaining bytes, and removal. Actual local OCR, audio decoding, source-time slices, cancellation/recovery, PDF handling, native 2B/4B generation and SSE parity were exercised separately. The final Mimir check retained the 56 Wh correction with attribution while preserving the separate Friday event and reused three receipts. This is focused synthetic model qualification. Tiny-model host probes still misclassified some irrelevant material and repeated facts; valid JSON and exact quotes do not establish semantic fidelity, and generated prose can repeat attribution. These judgments do not suppress ordinary feed items. Physical-phone GPU/thermal performance and multilingual accuracy remain unverified; live social-platform availability was not requalified by fixture success.

All 30 native libraries have uncompressed APK offsets aligned to 16 KiB. All 64-bit ELF libraries and all seven offline text/media/embedding runtime libraries pass the corresponding ELF alignment checks. Four legacy 32-bit Tor/libc++ entries retain 4 KiB ELF alignment. Runtime binaries match their audited builds; pinned QVAC, whisper.cpp, Tesseract and Leptonica archives, patches and notices are included by the verified source-packaging workflow. ARM Vulkan compiled but was not run on a physical phone.

The non-debuggable release was installed over 0.34.2 in the visible laptop preview. UID 10116 and all 50 current private records were byte-identical before first launch, including files, databases, preferences and no_backup. Installed APK SHA-256 matches the built release. Retention snapshots are access-restricted. The selected test provider file was deleted after use; Tor preference and Android permission grants were restored. Darwin runtime/profile state was not changed.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/context-foundation/evidence/` — `gradle-final-4.log`, `validation-summary.json`, `native-context-final4-26.log`, `native-context-final4-35.log`, `native-reader-regression-final2-26.log`, actual model/media reports, `native-release-audit.json`, `native-release-final.json`, final native screens and `main-upgrade/`. The source-dependent behavior, controls and limits are documented in [the Context guide](docs/CONTEXT.md).

## Compact filter icons — 0.34.2 / code 47 (2026-10-19)

Social-site choices are icon-only 48 dp targets with accessible names and highlighted selection. The picker observes the reader's actual chosen platform order, including updates while open. Every chosen feed tab appears without the former six-site cutoff; a chevron exposes other sites. Saved rule selections and a directly inspected post's site remain reachable even outside the chosen feed. Rule-summary marks follow the same order. Filtering conditions, recipes, networking, saved material and reading state are unchanged.

**26 Android instrumentation checks passed, with zero failures or skips**: 13 filtering cases, both native light/dark icon galleries, four platform-preference cases and seven settings cases. The filter case verifies live reordering, removal, display beyond six chosen sites, icon-only presentation, preserved off-feed selections and source-specific rule behavior. Actual dark native filter screens were inspected. The unchanged core task uses its up-to-date successful 1,207-test result; no fresh core execution is claimed for this presentation-only change. Debug/release assemblies pass, and release lint has zero errors and 123 warnings. All 28 bundled native libraries are unchanged from 0.34.1. The non-debuggable release upgrade retained UID 10116 and all 47 private records byte-for-byte before first launch; the installed APK hash matches the built artifact. Build and lint evidence are under `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/filter-icons/evidence/`. External-source availability was not requalified by these local fixtures.

## Site-aware filters — 0.34.1 / code 46 (2026-10-19)

The reader-facing editor now starts with Feed/Search pictographs and bundled social-site logos. Choosing a site tailors its wording and controls: Reddit exposes subreddit matching; Mastodon, Lemmy, Pixelfed and Misskey expose original-server matching; photo sites use caption/photo terms, Telegram uses messages, and RSS uses article text. Website-link matching remains separate under More options and accepts full pasted links. Media controls use pictographs. Existing details remain visible after changing sites, with incompatible choices flagged and rejected before saving. Site selection dismisses the keyboard; old save notices no longer cover a reopened editor.

Rules match each original edition's social platform, independent of its frontend, archive or search provider. Subreddit membership requires an original Reddit post URL with a matching r/community/comments path; mentioning or linking a subreddit does not establish membership. Federated server conditions compare exact original post hosts. Unknown membership stays unmatched, and absent source-dependent fields such as flair are not guessed. These conditions are ANDed with the existing phrase/media/account conditions within one edition. No networking, model, permission or root dependency was introduced. Recipe version 2 documents platforms/subreddits/servers; strict version 1 imports preserve their original meaning. Older readers reject version 2 rather than discard a new condition.

**1,207 core unit checks passed**, with zero failures/errors/skips, including 30 filter/revision cases and the actual native SSE runtime check. **85 distinct Android checks passed; one optional live-search case was skipped** because its explicit live flag was absent. The initial 86-case regression run covered filtering, feed memory, merged posts/comment streams, personal channels, channel feeds, portability, search and settings. One validation-row assertion in that run failed. All 13 filter UI cases subsequently passed on the final production code, and a focused rerun passed after adding explicit synchronization on the validation message. Latest results have no outstanding failures. Debug/release assemblies and release lint pass; lint has zero errors and 123 warnings. All 28 bundled native libraries are byte-identical to 0.34.0.

Inspected actual native dark screens for the site/search picker, subreddit/server controls, photo/caption controls, rule management, preview and filtered search, plus the installed release's light-mode editor. Installed the non-debuggable signed release over 0.34.0 in the visible emulator: UID 10116 retained, all 47 private settings/database/file records byte-identical before first launch, and installed APK SHA-256 matches the built release. This verifies retention without resetting subscriptions, rules, reading progress or provider/model settings. Runtime behavior on live public sources was not requalified by offline fixture success. Existing AI qualification limits remain unchanged. Darwin runtime/profile state was not changed.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/filter-polish/evidence/`: `build-3.log`, `build-test-5.log`, `native-2.log`, `native-3.log`, `native-4.log`, `native-summary.json`, `validation-summary.json`, `screens-final-ui/`, `main-editor.png` and `main-upgrade/main-upgrade.json`. Private retention snapshots are access-restricted.

## Feed filters — 0.34.0 / code 45 (2026-10-18)

Implemented native feed/search rule authoring, bounded cached previews, account/channel/search/global scopes, literal phrases/hashtags/domains/media conditions, keep exceptions, expiry, collect/collapse/hide actions, filtered-item review, reveal navigation, deletion undo and versioned recipe import/export. Native state exports carry rules with channel remapping; source-only packs remain source-only. Rules and progress stay local. No model or account is required. Comments, direct-post views and the saved library are outside this first filtering release.

The matcher compiles once per rule snapshot, shares normalized source observations and uses direct literal matching with Unicode word boundaries. Cancellable batch evaluation and revision preparation run away from the UI thread. Explicit Apply adopts edited/expired rules; the reader keeps a coherent snapshot meanwhile. Collapsed/hidden/pending cards never acquire read marks. Exact copies may inherit read state; added passages, media, documents and revised content do not. Memory/card admission commits without suspension. Search completion revisions prevent fast empty batches from stranding later valid candidates when Android coalesces loading frames. All-filtered batches stop automatic pagination and offer an explicit next batch. Unavailable subscriptions still remain pending.

**1,200 core tests passed: zero failures, errors or skips.** This includes 23 content-filter/revision cases, a fast-empty-search completion regression and the acquired native SSE runtime case. **81 Android checks passed; one optional live-search test was skipped** because the optional live-search flag was not enabled for this fixture run. The 82-case native run covered the nine new filtering cases plus reading memory, fusion/comment streams, personal channels, channel feeds, reader portability, search and settings. The final runner reported `OK (82 tests)` with zero failure statuses. Debug/release APKs and instrumentation APK built successfully; release lint reported zero errors and 123 warnings.

Actual native dark screens were inspected for preview, rule management, collapse/reveal, review and filtered search. The installed release's light-mode reader and filter sheet were inspected too. A desktop stress fixture (1,000 approximately 1,100-character cards, two unmatched phrases per rule) improved from approximately 6.7–8.7 seconds under 128 rules to 69–93 milliseconds after replacing the regex scan; this is host evidence, not a phone latency or battery claim. Android batch matching is additionally asynchronous. Ordinary cache and explicit saved material remain separate; the revision ledger shares the existing 4 MiB / 90-day reading-memory budget with a 1 MiB stamp allowance. Rule documents are capped at 1 MB of UTF-8 on both save and import, including multilingual recipes.

Installed the non-debuggable, signed release over 0.33.0 on the visible laptop preview (`emulator-5560`): UID 10116 retained, installed APK hash matched the built APK, and all 47 private settings/database/file records were byte-identical before first launch. The snapshot includes `no_backup` where present, preserving model/provider credentials as well as ordinary preferences. All 28 bundled native libraries are byte-identical to 0.33.0. No local AI worker was started by filtering. The test emulator was stopped after checks; the visible preview remains open in the reader. Darwin runtime/profile state was not changed; the system filesystem retained approximately 18 GiB free.

Decision-model inference is deliberately not enabled: Android cost, domain retention and calibration have not been qualified. The existing text/embedding foundation is unchanged, and filtering makes no AI requests. Promotion presets describe literal wording rather than claiming to detect advertising intent. Public source availability is not established by fixture success: the visible preview retained its prior pending subscription state, and live search behavior was not reverified.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/content-filters/evidence/` — `build-8.log`, `native-8.log`, `validation-summary.json`, `screens-final/`, `main-reader.png`, `main-filters.png`, and `main-upgrade/main-upgrade.json`. Private state snapshots are access-restricted. Recipe fields and behavior are documented in README.

## Provider choice — 0.33.0 / code 44 (2026-10-18)

Standard OpenAI/ChatGPT, Anthropic/Claude, DeepSeek and Z.ai slots now coexist
with a local server and multiple custom providers. Text and embeddings retain
independent selections, encrypted per-slot keys and Local first (default),
Device only and Use selected provider modes. An explicit provider choice never
requires deleting a local model. A failed/pending local choice does not silently
become a cloud request. AI remains off by default.

All **1,176 core tests passed**, with zero failures/errors/skips, including the
acquired native SSE case and seven new cloud protocol/routing cases. **79 Android
checks passed**: eleven provider/local-server checks and 68 reader, feed-memory,
fusion, settings, links, video, saved-library, portability and provenance
regressions. Native provider and embedding screens were inspected. The actual
app manager honored an explicit provider override through NetworkGateway without
resetting the pending local choice; Device only made no remote request.

Tests cover separate keys/selections, legacy single-provider migration, reader
export exclusion, endpoint-change credential consent, damaged-key replacement,
Anthropic headers/system/text blocks, modern OpenAI token parameters, direct
DeepSeek/GLM utility requests, unsupported embedding choices, no inference from
settings, no automatic selection after forgetting a service, and Tor fail-closed
local hosting. Named cloud responses are local protocol fixtures: live account
access, billing, quotas and service availability were not exercised. Local model
weights/kernels are unchanged from 0.32.0; physical-phone qualification limits
still apply. See [the architecture](docs/AI_FOUNDATION.md) and
[the evidence ledger](docs/AI_FOUNDATION_LEDGER.md).

## Optional AI foundation — 0.32.0 / code 43 (2026-10-18)

1,169 core unit checks and 81 Android checks passed with zero failures or skips.
The latter include actual acquired-model JNI/private-worker inference, an actual
one-click public embedding download through NetworkGateway, encrypted credentials,
Tor/local-server isolation, off-state deletion, and 68 reader regression checks.
Debug/release assemblies passed; lint has zero errors and 119 warnings. Native
settings screens were captured and inspected. All three offered Bonsai PQ2_0
packs passed host source-fidelity and lifecycle reference checks. ARM64 Vulkan
compiled with 16 KiB alignment; physical Android GPU/thermal qualification and
experimental KV compression remain unverified. AI remains off by default; TLDR
and semantic user features are not introduced in this foundation release.

[Detailed evidence and limitations](docs/AI_FOUNDATION_LEDGER.md).

# Validation

Current completed local validation: 2026-10-19.
Public-source observations carry their own dates.
Toolchain: JDK 21, Gradle 9.3.1, AGP 9.1.1, Android SDK 37 / build tools
36.0.0, NDK 27.3.13750724 and Compose BOM 2026.09.00. App checks used AOSP Android 29
x86_64, 720 × 1280, 320 dpi, host GPU and initially software CPU emulation. The
2026-10-15 software profile used 576 × 1024 / 256 dpi / 30 Hz. KVM is now verified
working, with four virtual CPUs / 3 GB RAM and 720 × 1280 / 320 dpi / 60 Hz.
Physical-phone speed and ARM runtime behavior remain untested.

## Current checks

### 0.31.0 Readable names and collected updates

Version **0.31.0/code 42** fixes punctuation misses such as `@WhiteHouse:` and
`.@VP:`, extends name presentation to author/profile labels, repost attributions,
public biographies, original-edition captions and saved material, and supports
exact linked Facebook mentions. Recognized unresolved accounts remain linked
using an identifier without mention notation. Exact handles remain in account
details. Source text, email/code/URL exclusions and canonical links remain intact.
Saved readers do not start missing-name traffic; supplied public author names do
not require a second profile request.

The merger now aligns shorter picture-corroborated shared passages and literal
phrasing inside differently addressed sentences. Related quoted X coverage can
also collect when its publisher/proven curator, quoted account, subject wording,
nearby interview frames and six-hour interval agree. Every pair must qualify.
Shared passages appear once; different passages retain their source attribution,
and each original discussion and URL stays distinct. The actual cached Bessent
pair is a regression fixture: one card retains both complete different statements,
both comment streams and the different second scene while showing the repeated
interview frame once. This is bounded local evidence, not universal semantic
classification or an online AI service.

The comparison bank can quietly rebuild from up to 128 already-cached pictures,
yielding to foreground feed/media work and making no supplementary requests.
Captured `Media.visualFingerprint` observations travel with protected saved
editions; an offline collection can reopen after the disposable bank is cleared.
Source snapshots remain immutable. Saving supplements only missing local visual
observations from otherwise identical snapshots and preserves previously captured
wording. The optional native-format field is documented in `docs/PORTABILITY.md`.

Final debug, instrumentation and locally signed non-debuggable release builds
passed. The complete core run passed **1,130 tests in 86 suites**, without failures,
errors or skips. Current affected Android 35 runs passed **203 distinct cases**:
35 account-name cases, 60 feed/reading-state/subscription/channel cases, 24 outgoing
link/discussion/detail cases, 44 saved-library/portability/RSS cases, 33 search
cases and seven image-comparison cases. The Bessent case ran both in its focused
check and in the 60-case feed batch and is counted once. Lint has zero errors and
101 warnings. A further 58 linked-video/scope/settings cases and one real public-
profile smoke check passed against the final production code, for **262 distinct
Android cases** overall. The successful final runs have no failures, errors or skips.

Actual native Android 35 screens were inspected for post names, offline copies,
the collected Bessent card and both source discussions. The final public-name smoke check
retrieved a real Bluesky account through NetworkGateway,
waited for essential work and reused the cached label without another profile
request. That check does not establish availability for every public source.
The Android 29 laptop preview is installed with the same UID (10116), with all
46 private preference/database/file entries byte-identical across the final
install. Its actual Bessent reading screen shows the proper public name and
collected source context. Its reading progress and settings were retained.

Intermediate failures are retained separately: overly permissive first-pass
phrase matching was tightened against changed claims and symbols; older raw-name
and unmerged-text assertions were updated; large-card and lazy-comment test
selectors were corrected. Native saving exposed and fixed an actual loss of the
new comparison observation while coalescing duplicate source snapshots. Existing
source-immutability assertions now independently verify local AI/image observations
against actual fixture bytes, then compare every original source field; optional
observations are not required for captures made without decoding an image.
Failed runs are not counted as successful final runs.

The Bessent content/discussions use captured public-source records, real thumbnail
bytes and controlled reply fixtures. They do not prove that its original public
comment endpoints or CommentAnywhere are currently available. Physical ARM-device
behavior and a new live Tor benchmark remain untested. Evidence, native screens,
retained-data receipts and intermediate logs are under
`LiberBuild/evidence/mention-coverage-2026-10-18/`.

### 0.30.0 YouTube as linked media

Version **0.30.0/code 41** removes YouTube's social tab, channel subscription
creation/discovery and standalone search. Its platform ID, native video reader,
optional video comments, SponsorBlock, protected saved material and exact-ID recovery
remain. Darwin remains the routing donor; all app networking uses NetworkGateway.

Older channel records remain local/exportable in a collapsed management section,
but do not refresh, populate social feeds or keep caught-up boundaries pending.
Older tab choices exclude YouTube while retaining the other sites' order; a
YouTube-only selection uses social defaults. Android's video-link alias remains
available independently of social tabs and does not claim regular-domain channel,
search or playlist URLs. Import preview skips channel sources with an explanation
and still accepts saved video text/media metadata. Opening and leaving a linked
video preserves the previous social reader's state.

Debug, instrumentation and locally signed non-debuggable release builds passed.
The complete core run passed **1,114 tests in 85 suites**. Android 35 checks passed
**250 distinct cases**, including three explicitly enabled public-source checks.
The final player-layout revision rechecked 85 affected native cases: 17 scope/link/
legacy-data cases, 24 YouTube/SponsorBlock cases and 44 saved-library/portability/RSS
cases. There were no failures, errors or skips in these successful runs. Lint has
zero errors and 101 warnings. An intermediate core assertion that still expected
YouTube among default social tabs failed before being corrected; that failed run
is retained separately and is not counted as passing evidence.

Native Android 35 screens were inspected, including social-only pickers, scope
explanations, return navigation and the player with a long folded description.
The player precedes the description without changing the retained source text or
collected context. The Android 29 laptop preview was upgraded to 0.30.0/code 41
with the same application UID (10116); all 46 private preference/database/file
entries were byte-identical immediately across the final install. A real shared
YouTube video displayed its native play control without scrolling or autoplay;
Back returned to All subscriptions. Package-manager checks on Android 29 also
confirmed the video alias handles video links while regular-domain channel/search
links are not claimed. The preview was left on the social reader.

Public-source tests exercised original extraction/playback and archive recovery;
they do not establish universal availability for arbitrary videos or frontends.
This scope change adds no community-post reader, physical ARM-device result or
new live Tor benchmark. Android default-link approval remains a system choice.
Evidence, distinct test identities, native screenshots and private retained-data
receipts are under `LiberBuild/evidence/youtube-linked-medium-2026-10-18/`.

### 0.29.0 Readable account mentions

Version **0.29.0/code 40** adds a local public-name bank and deferred mention
presentation. Darwin supplies the app's routing; no Darwin runtime/profile state
is changed. All new requests use NetworkGateway and respect original-access and
optional fail-closed Tor policy. Stock, unrooted Android remains supported.

Final debug, Android-test and locally signed non-debuggable release builds passed.
Evidence is under `LiberBuild/evidence/mention-names-2026-10-18/`.

| Check | Result |
| --- | --- |
| Core unit tests | 1,107 passed in 84 suites; no failures, errors or skips |
| Android account-name settings | 2 passed |
| Android persistent name bank and native mention UI | 29 passed (18 bank, 11 UI) |
| Android YouTube reader and SponsorBlock | 27 passed |
| Android video, settings and platform marks | 18 passed |
| Android feed memory, merging and channels | 53 passed |
| Android outgoing links, folded details and discussions | 24 passed |
| Android local library, portability and RSS | 44 passed |
| Android search, scopes and discovery | 32 passed |
| Explicit live source checks | 3 passed: public name lookup, original YouTube playback and exact-video archived playback |
| Android total | 232 passed; no failures, errors or skips |
| Android lint | No errors; 100 warnings recorded in the report |

The live production bank resolved `@bsky.app` to the supplied name **Bluesky**
through alternate routes. The priority hold made zero profile requests, retrieval
made three gateway requests, and cached reentry displayed the name immediately
with zero extra requests. This is a functional check, not a latency benchmark.
Native screenshots of the resolved paragraph, nested settings and restored laptop
feed were inspected. The account sheet's name, original handle and authentic URL
were checked through native Compose interactions.

The API 29 laptop preview was upgraded to **0.29.0/code 40**, retaining its UID
10116 and all 44 existing private data files byte for byte before launch. Its
installed APK hash matches the packaged non-debuggable preview. Both new defaults
are enabled; existing platform selection and reading state are retained. After
refresh the feed showed 18 new posts and one visibly pending account. Development
ADB/root access was used only to verify this upgrade; app features require neither.

Intermediate failures are retained rather than presented as passes. Validation
found a federated `@user@www.server` recognition edge, the production SQLite helper's
result-bearing PRAGMA, and a stale Android-host viewport during a loading-to-reader
resize. These now have regressions. SQLite consumes the PRAGMA cursor; visible
mention geometry resamples after Android View layout commits the new host size,
without polling. A settings fixture was corrected to restore the real app toolbar.
One incremental release packaging attempt failed with a splitter error; its
unchanged retry and the final release build succeeded.

The implementation keeps source captions, deduplication, source URLs and saved /
exported text unchanged. Exact platform/server account keys prevent identical
handles on different services from sharing names. Public display names require
explicit metadata evidence; fallback usernames and generic titles do not qualify.
A name-only profile route continues past metadata-only profiles without changing
ordinary PROFILE health. Fused passages retain one actual edition's account context.

Only visible glyph spans lease missing names. Folded inventories, truncated and
offscreen text, prefetched cards and backgrounded readers do not start lookups.
Feed/search activity and image/video requests take priority over the one deferred
lookup, including callers that use the gateway's shared media client. Supplemental
traffic remains filtered and cannot bypass Tor or content budgets. Cached labels
can display offline; saved-library views allow no background account traffic.

The standalone disposable Room bank is limited to 1,024 records / 1 MiB bounded
payload, with TRUNCATE journaling and compaction. Names unused for 30 days expire;
frequency and recency rank eviction. View writes coalesce over ten seconds. Stale
labels refresh on a later view after seven days. Persisted misses back off six
hours to seven days; changing Tor selection or original permission can retry in the
new policy class. Neither transient readiness nor a SOCKS-port change resets backoff.
The bank counts toward ordinary storage and clears separately from protected saved
material. Native exports carry the two preference booleans and exclude this cache.

Physical-phone/ARM performance and live full-Tor name retrieval remain unverified.
External sources can omit display names or refuse public access; those mentions
stay readable in their original form with their account identity available.

### 0.28.0 YouTube, SponsorBlock and quieter post details

Version **0.28.0/code 39** adds native YouTube video/channel reading, public
comments, content-stream selection, SponsorBlock and exact-video archive recovery.
Darwin remains the routing donor. All application networking, including NewPipe
extraction, separate audio, stream probes and recovery, uses NetworkGateway.
Stock, unrooted Android remains the supported user environment.

| Check | Current result |
| --- | --- |
| Core JVM tests | **1,064 passed**, 80 suites, zero failures/errors/skips |
| API 35 controlled native regressions | **198 passed**, zero failures/errors/skips after fixture corrections |
| API 35 opt-in live native playback | **2 passed**: public original and exact-ID archive, both with frames, audio/video tracks and advancing position |
| Android total | **200 passed**, zero final failures/errors/skips |
| Debug, release-preview and Android test builds | Passed (`build-seventh.log`, final test-only rebuilds `build-eighth-test-evidence.log` and `build-ninth-native-runtime-test.log`) |
| Android lint | Zero errors/fatal issues; 97 warnings |
| Release packaging/checksums | Packaged debug/release-preview APKs and source ZIP; SHA-256 manifest and source-byte checks pass |
| API 29 preview upgrade and retained-state check | Installed **0.28.0/code 39**; unchanged UID and all 44 checked preference/database/app files retained before first launch |

The current core results cover exact YouTube identities, original/alternate source
selection, source/query-bound continuations, public comment sorting, separate
audio/video selection, rejected transports, filtering and unavailable-Tor failure.
They also cover SponsorBlock parsing and skip decisions, bounded recovery/backoff,
strict archive identity, redirect-aware media-prefix sharing proof and lossless
leading/trailing reference folding. A generic player error, login requirement or
region restriction is not evidence that an original was deleted. API-only Piped
roots never become invented public web links. CommentAnywhere receives the official
query-free `youtu.be/VIDEO_ID` permalink, preserving distinct video discussions.

Completed live reads through the actual NetworkGateway implementation are recorded
in `gateway-verified/gateway-audit.json`:

| Selected operation | Observed result |
| --- | --- |
| Original public video | One exact **Me at the zoo** post; 3,173 ms |
| Routed playback preparation | Permitted original route selected a combined MP4; its bounded stream proof passed; 2,889 ms |
| NASA channel uploads | 101 records, 58 with supplied dates; a real continuation added 60 distinct older records and retained a further cursor; 1,958 ms for the recorded operation |
| Public video search | 20 actual posts with a supplied continuation; 507 ms |
| Same-ID archive recovery | Wayback recovered `dQw4w9WgXcQ` with separate WebM video/audio and bounded prefix proof; 4,539 ms |
| SponsorBlock | Four exact-video segments across `music_offtopic`, `poi_highlight` and `filler`; 5,620 ms |

These are individual developer-host source observations, not phone-speed,
full-Tor or whole-file playback benchmarks. A successful media prefix does not
prove that every later range survives or that an entire recording decodes.
Unknown publication dates remain unknown. The continuation observation establishes
that the next supplied NASA page added records, not complete channel history.

The isolated API 35 KVM/SwiftShader emulator also ran two explicit live playback
cases. The public original `jNQXAC9IVRw` exercised Android NewPipe extraction,
Darwin routing and the actual selected MP4 transport. It rendered an AVC frame,
selected AAC audio and advanced to 1,089 ms; the final playing URI matched the
verified original stream (13,204 ms for the whole check). Wayback's exact
`dQw4w9WgXcQ` recording rendered VP9 with Opus audio from separate tracks and
advanced to 1,025 ms; the initially verified recording remained selected, and all
observed hosts were `web.archive.org` (16,405 ms for the whole check). Neither
case proves playback of the entire recording. Evidence includes native PNGs,
selected track types, final-URI agreement and gateway events under
`native-verified/youtube-live` and `native-verified/youtube-original-live`.

The 198 controlled cases comprise 27 YouTube/SponsorBlock, 18 video/settings,
53 reader/catch-up/fusion/channels, 24 sharing/details/comments,
44 saved/portable/RSS and 32 search/directory/scope/privacy cases. Earlier failed
runs are retained. Corrections include main-thread player access for SponsorBlock,
explicitly stopped Tor controllers for unavailable-Tor fixtures, and observing
identity reset through the store's own vault mutex instead of racing deletion
through a second fixture vault. The final settings batch and both live cases
passed using the final test build; production APK code remained unchanged.

Unavailable source observations are retained as failures rather than advertised
as healthy pools. The initial Invidious probes returned HTTP 403 on inv.nadeko.net
and yewtu.be, and HTTP 401 on invidious.nerdvpn.de. Piped's selected endpoint
returned HTTP 526. An HTTP 200 original watch page for `BaW_jenozKc` declared the
video unavailable and did not count as readable content. The later successful
original sample used a different exact ID. Earlier archive research found a
Ghostarchive same-ID MP4 prefix, an Internet Archive metadata-only item,
PreserveTube HTTP 403 and a selected Hobune HTTP 404; none establishes universal
availability or a shutdown of the whole service.

Feed cards collect detached leading and trailing references, link-only calls to
action and extracted attachment links into one expandable inventory, including
fused posts. In-sentence references and meaningful prose remain in place. The
source caption, saved copy and portable export retain all original characters.
The read-only White House cache review found seven extracted-link inventories
among 38 posts: six had one link and one had two, explaining why all escaped the
previous three-link folding threshold. Actual native collapsed/expanded post details, paired-track playback and both remote playback screens were inspected.

The non-debuggable release preview was installed in the existing API 29 laptop
emulator without a data clear. UID **10116** is unchanged. All **44** files checked
under shared preferences, databases and app files were byte-identical immediately
after upgrade, before first launch. The installed APK SHA-256 is
`0ff23deba3ab769c6b58fd9d77081e834b7d075661139fa20a8eddcfa9dd6ba4`.
The actual native SponsorBlock settings screen was inspected with its default
skips enabled. The restored feed retained its reading position, offered Continue
and Newest, and displayed 17 newly collected posts and one visibly pending account
at inspection. It did not declare the incomplete refresh caught up. Existing
selected tab preferences were preserved; YouTube can be added in Platform tabs.
The isolated API 35 test emulator and build daemons were stopped after validation.
Source, debug APK and locally signed release-preview APK are under
`LiberBuild/artifacts/`; the checksum manifest is `liber-0.28.0-checksums.json`.
The verified routing/extraction/player lessons were recorded in Bifrost as a
reported-success capture, with the receipt in the evidence directory.

Evidence root:
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/youtube-and-post-details-2026-10-18/`,
including `gateway-verified/gateway-audit.json`, `youtube-research-probes.json`,
`post-noise-review.json`, recovery observations and the final test/build logs.
Physical-phone/ARM behavior and live full-Tor YouTube playback remain unverified.

### 0.27.0 Collected reading, copied posts and resilient sharing

Version **0.27.0/code 38** adds automatic complete-window admission, cross-account
copy collection, display-only reference folding and explicit-action live link
selection. Darwin remains the routing donor; no Darwin runtime/profile state was
changed. Networking continues through NetworkGateway, including availability
checks and cursor reads. The app requires no root or privileged installation.

Completed checks for the delivered source/build:

| Check | Result |
| --- | --- |
| Core JVM tests | **972 passed**, 70 suites, zero failures/errors/skips |
| API 35 reader/catch-up/fusion/channels | **53 passed** |
| API 35 share routes/reference folding/public links/comments | **21 passed** |
| API 35 saved library/portable exports/RSS | **44 passed** |
| API 35 native search/directory/scopes/privacy/image search | **32 passed** |
| Android total | **150 passed**, zero failures/errors/skips |
| Debug, release-preview and Android test APK builds | Passed |
| Android lint | Zero errors/fatal issues; 94 warnings |

The Android runs used the isolated API 35 emulator with KVM CPU acceleration,
four virtual CPUs and 3 GiB RAM. Its host graphics driver crashed during an earlier
run; the complete passing run used SwiftShader rendering. This is validation of
native behavior, not a phone-speed or hardware-GPU benchmark. Earlier failures
and the interrupted log remain in the evidence directory. Actual native feed,
reference expansion, author/original sheets and the manual-refresh snackbar were
inspected. The separate API 29 laptop preview retains its existing hardware setup.
Physical-phone/ARM behavior is still untested.

Reader checks require the entire fetched newer prefix to be admitted without Show
or repeated Continue taps, retaining the visible post and exact pixel offset.
The visible body/media stay frozen while offscreen cards acquire complete merged
context. Newest/Continue, read identities, stable stopping boundaries, source
membership and channel exclusions remain covered. A real pull gesture away from
the head must not refresh; a pull at the newest edge performs one refresh and a
successful no-new-content check shows exactly **Slop consumed, go be productive.**
Pending, cancelled and unrelated checks cannot announce that result.

Repository tests use actual Room queries and supplied opaque cursor pages. No
partial generation may escape before the source completes its catch-up attempt.
A repeated cursor retains available records but stays pending. After a reading
memory reload, a retry must still reach the original unresolved head, rather than
mistaking newer partial cache rows for completion. Frontier metadata is bounded
and cleared only by a successful check. Empty startup windows cannot erase the
accepted reading window. Tests do not restore transient running flags or rewind
refresh generations.

Copy tests prove that a confirmed missing original selects a readable exact
frontend URL verbatim; readable originals remain canonical. Login walls do not
prove deletion, offline copies are explicitly unverified, disabled original access
prevents its probes/opening, and no discovered readable copy leaves the clipboard
unchanged. Saved-copy links use the same resolver without rewriting captured
originals. Parser tests reject homepages, generic success shells, mismatched post
IDs and API/RSS responses as HTML permalink proof. Availability checks are bounded
to three route reads/HTTP exchanges and 1 MiB, with 10-second direct/20-second Tor
budgets. Evidence expires and is limited to 128 records/256 KiB. Rendering cards
performs no sharing probes. Clipboard, browser and toast effects explicitly return
to Android's main thread after network work.

Copied-content regressions cover different publishers, complete-link groups,
nontransitive similarity, older/undated media-backed copies, true original
references, earliest known chronology, exact context attribution and preservation
of every original/comment root. Conflicting claims, uncorroborated short slogans,
previews/replies and unmatched correction/rebuttal framing stay separate, including
the legacy same-account/linked-account fusion path. Shared fact-check wording is
allowed. Native saved/export tests preserve distinct authors, unknown dates,
captured threads and local full-text search after reimport. Trailing reference
folding reconstructs every source character and does not alter saved/exported text.

The live/dead and fallback scenarios above are controlled fixtures. This round did
not certify current upstream availability for every site, archive or frontend.
An exhausted source window, page/post/time budget, login wall or unavailable source
remains qualified/pending rather than claiming complete platform coverage.

The non-debuggable **0.27.0/code 38** preview was installed in the API 29 laptop
emulator without clearing data. UID **10116** remained unchanged and all **44**
files under shared preferences, databases and app files were byte-identical before
first launch. The installed APK matches the packaged preview SHA-256
`6957de4f5bb3b8d59ca811dfc1efd4934e788c5241c02130716777982fe2743e`.
The actual restored feed and Newest/Continue controls were inspected. One source
remained visibly pending; the app did not label that incomplete check caught up.
An active startup refresh can defer the first coherent cached feed presentation
until the refresh finishes; existing visible sessions keep their current cards.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/reader-noise-and-catch-up-2026-10-18/`,
including `build-delivery.log`, `core-summary.json`, `lint-summary.json`,
`android-delivery-*-summary.json`, native screenshots and upgrade receipts.

### Previous 0.26.1 account and community discovery

The preceding preview was **0.26.1/code 37**. Search displays genuine entity
metadata in a horizontal shelf with platform-specific labels, including Facebook
**Pages & people** and Reddit **Subreddits & users**. A directory result is separate
from a matching post: native Facebook extraction accepts explicit `ENTITY_PAGES`
and `ENTITY_USER` result edges, rather than inferring matches from post authors,
nested profiles or recommendations. Cards retain canonical original profile or
community targets and supplied names, descriptions and avatars. Empty connections,
login pages and unrelated entity links do not establish working directory data.

The directory runs in an independent ViewModel job and overlays the current
search snapshot without replacing posts, post loading state or the post cursor.
Post publication does not wait for directory completion. Stop, navigation and a
replacement search cancel both jobs; an unfinished cancelled directory remains
retryable, while a completed lookup runs once per session. Scoped searches skip
this broad lookup instead of silently expanding their selected scope. Existing
native Facebook post retrieval, selected-provider post/broad-query discovery,
overlapping destination reads and source-bound post continuations remain in place.

Facebook's directory requests at most two first windows concurrently: `PAGES_TAB`
and `PEOPLE_TAB`. Each category has its own deadline inside the enclosing source
budget, so a successful category survives its companion's timeout. Parent
cancellation still propagates. Native deadlines are 12 seconds on the ordinary
direct connection and 25 seconds with full Tor; Phice category deadlines are
6/12 seconds. No directory cursor is returned to the post reader. Entity-only
directory success does not update post/timeline source health.

On **2026-10-18**, two separate anonymous developer-host requests used the current
NetworkGateway, URL cleaner and bundled native filter. The exact query
`melbourne organic food` returned **7 explicit page results in 6,794 ms** and
**8 explicit person results in 1,787 ms**. No social account, account token,
copied browser cookie or paid API key was supplied. These are separate public
source availability observations, not an installed-app timing, a combined result
count, a latency distribution or a phone benchmark. Facebook's directory ranking
does not prove that every returned entity is in Melbourne. Raw public responses,
request variables and probe reports are retained under
`LiberBuild/evidence/search-discovery-shelf-2026-10-18`.

Reddit has a separate community directory through Redlib-family sources and
permitted original subreddit search; Bluesky uses its public actor search.
These newly independent Reddit/Bluesky paths have not yet been live verified for
this release. Phice directory extraction follows its public `.search_card`
template; live Phice directory access remains unverified. Primary protocol
references remain [Phice's extractor](https://github.com/Willie169/phice/blob/main/src/lib/extractor.py),
[entity parser](https://github.com/Willie169/phice/blob/main/src/lib/parsers.py)
and [read-only operation definitions](https://github.com/Willie169/phice/blob/main/src/lib/api.py).
The implementation remains independent, and Darwin/GPL provenance and source
notices are preserved.

The fallback uses only the selected public index to discover permitted original
account/community destinations. Up to eight candidates are hydrated through the
router, with two simultaneous reads; a supplied profile/community record must
match the destination identity before display. Index titles and snippets do not
become directory metadata or fabricated posts. The shelf has a 40-entity cap and
shares the displayed text/metadata budget; separately retained directory metadata
is bounded too. The three-session search cache and ordinary cache limits/expiry
remain; saved records and explicit offline downloads retain their protection.
All requests use NetworkGateway and existing privacy filters. Alternate sources
precede permitted originals, commercial indexes require Tor and fail closed, and
a provider change cannot silently redirect a pending directory query. Full-Tor
live directory access, physical-phone/ARM behavior and future anonymous source
availability remain unverified.

The completed core run passes **905 tests / 64 suites**, with no failures, errors
or skips. Isolated API 35 runs pass **25 search/scope/privacy/discovery tests plus
54 integration regressions: 79 native tests total**, with no failures, errors or
skips. Debug, release and AndroidTest builds pass. Lint reports **0 errors /
93 warnings**. Coverage includes separate directory and post publication,
Stop/navigation cancellation, scope boundaries, genuine entity hydration,
source/query binding, partial category failure, forged targets making no requests,
fail-closed Tor/original access and cached-back directory retry without refetching
posts. Results are retained in `core-summary.json`, `android-search-summary.json`,
`android-integration-summary.json`, `lint-summary.json` and their adjoining logs
under `LiberBuild/evidence/search-discovery-shelf-2026-10-18`.

The ordinary native Facebook search UI on the main API 29 preview was used for
the exact query `melbourne organic food`. It returned **16 genuine page/person
records alongside 11 public posts**, with **0 index-snippet cards**. The pre-upgrade
snapshot contained **0 entities**. All 16 entity URLs were authentic Facebook
profile targets, and all 16 carried supplied avatar references; this does not
claim that every avatar file was downloaded. The directory shelf, post feed and
horizontal browsing were inspected on actual native screens, including three
horizontal gestures. `main-native/live-query-verification.json` records the
point-in-time result and original Facebook GraphQL provenance. The retained
screens are `facebook-pages-and-people.png`, `facebook-posts-and-shelf.png` and
`facebook-shelf-swiped.png` in that same `main-native` directory. The installed
count differs from the separate host probes above; neither establishes exhaustive
coverage, geographic correctness or a stable result count.

Offline replay of both retained host responses through the rebuilt production
parser preserves **7 pages and 8 people**, with every canonical identity
round-tripping as a profile, no fabricated posts and no returned post cursor.
`native-directory-offline-replay.json` retains the result. The earlier parser
kept only one person because seven explicit person IDs used Facebook's opaque
`pfbid` form. `before-pfbid-replay.json` preserves that failure. A narrow fix now
accepts bounded numeric or observed alphanumeric `pfbid` profile IDs and keeps
their supplied original URLs; it does not guess numeric account IDs or establish
that every person's timeline is readable.

Earlier logs retain missing-import compilation failures and fixture/harness
failures: an HTTP mock's relative post URL conflicted with the HTTPS-only parser,
an X test username exceeded the real 15-character limit, a Reddit test compared
raw trailing-slash URLs rather than canonical targets, and clipped Compose
semantics bounds gave false visibility results. The fixtures were corrected,
and native assertions now inspect full bounds and real horizontal gestures.
The final functional assertions pass; earlier failure logs remain alongside them.

The non-debuggable **0.26.1/code 37** main preview is installed under unchanged
UID **10116**. All **44 private preference/database/file records** were byte-identical
before and after installation, before launch. Its installed APK matches the
packaged SHA-256
`90752fbf2c2b5e2ce4419b8130024a6590f69399de0369fc10ca0654f1fe6026`.
`main-state-retained.json` and `main-upgrade.json` retain the checks. The isolated
API 35 test emulator was stopped after pulling eight discovery screenshots, and
the Gradle daemon was stopped. The main preview remains running on the exact
Facebook query. System free space was **18.4 GiB**, above the required 15 GiB
reserve; tools, builds and evidence remain under LiberBuild.

### 0.26.0 Native public Facebook search and concurrent discovery

Facebook now has an anonymous public post-search reader informed by Phice's
public protocol research. The read-only operation uses NetworkGateway and the
existing filters, with no social account, account token, copied browser cookies
or paid API key. Only explicit public-post result edges become native posts;
sponsored/recommended objects, nested comments and index snippets do not become
extra search cards. Public group permalinks retain their supplied group context.
Native and Phice continuations remain bound to the selected source, query and
search operation. Public protocols and anonymous access remain source dependent.

If Facebook discovery reaches the selected public index, two bounded queries
can run concurrently through that same provider: a post-focused query and the
broad query. Actual post destinations can return before a slower companion;
profile-only results wait for the post lane instead of repeatedly cancelling it.
Unfinished lanes retain bounded source/query-bound continuations, with at most
four attempts per lane and a ten-second request bound. Commercial indexes still
require Tor and fail closed; no query silently switches providers. Native
Facebook search follows the ordinary content connection and global Tor setting.
Alternate paths precede permitted original fallback.

The search feed overlaps one returned discovery cursor with up to two destination
reads and publishes genuine readable records progressively. It prioritizes posts
over profile candidates; filling the 40-account shelf does not stop post discovery.
Per-session limits are 240 destination candidates, 20 discovery pages, 200 posts
and approximately 1 MiB of text/metadata, alongside the existing three-session
memory bound. Valid search cursors can advance past filtered-empty or duplicate
index blocks without the old two-unreadable-block cutoff. A recognized empty
Facebook connection can also retain its next cursor. Empty navigation progress
stays **EMPTY** in source health; it never certifies working content. Repeated
cursors, source-specific end conditions and the existing budgets still stop
traversal. Successful empty native scoped searches retain their own stopping
policy. **Stop** preserves results and displays a neutral paused state with
**Continue search**. Source failures and unreadable destinations are reported as
unavailable rather than proof that no matching post exists.

The installed main API 29 preview was searched through its ordinary native UI for
the exact query `melbourne organic food`. It retrieved **12 newly fetched,
non-preview Facebook posts across two native blocks**, with **0 profiles and
0 index-snippet cards**. All 12 records carried publication dates and media
references; this does not establish that every attached media file was downloaded
or playable. The first real post was observed by **5,092 ms** in this single run.
That sampled UI visibility upper bound is not a latency distribution or a phone
benchmark. The exact-query result, pause state and a later readable Facebook post
after failed candidates were inspected on actual native screens.

`main-native/live-query-verification.json` records original Facebook GraphQL
provenance, no cached page delivery, and all 12 post keys absent before the upgrade.
The device clock was approximately **13.34 seconds behind the host**. The retained
report adjusts fetch/start comparisons using the measured clock-offset interval;
unadjusted host/device timestamps are not used as evidence of freshness. A separate
developer-host probe returned six genuine public post records in **8,153 ms**.
It is a separate availability observation, not the main UI timing. Full-Tor live
Facebook search, physical-phone speed, ARM runtime behavior and future anonymous
source availability remain unverified.

The final core run passes **879 tests / 61 suites**, with no failures, errors or
skips. The isolated API 35 runs pass **19 search/scope/privacy tests plus
54 feed/merger/channel/image/public-pathway regressions: 73 native tests total**,
with no failures, errors or skips. Debug, release and AndroidTest builds pass;
lint reports **0 errors / 93 warnings**. Coverage includes later readable hits
after failed candidates, overlapping discovery and destination reads, bounded
duplicate/empty pagination, pause/resume, account-quota independence, source/query
cursor binding, forged continuations making no requests, provider challenges,
cancellation, strict scope boundaries, and fail-closed Tor/original-access policy.
These deterministic checks do not establish that every external route works.

Early build logs retain obsolete stop-policy expectations, a real credentialed
pagination regression and a pretty-printed-JSON/NDJSON fixture mismatch. Jsoup's
URL resolver strips user information, so credential rejection now inspects raw
navigation attributes before resolving them; the credential assertions remain.
The fixture was corrected to its intended response framing. The first native run
also exposed a Compose test-clock wait issue; the harness was corrected, and the
final functional assertions pass. The post-focused-lane regression additionally
proves that a fast profile result cannot starve a slower post result. Earlier
failure logs remain beside the passing runs.

The non-debuggable **0.26.0/code 36** main preview is installed under unchanged
UID **10116**. All **44 private preference/database/file records** were byte-identical
before and after installation, before launch. Its installed APK matches the
packaged SHA-256
`18d45a44d7beaa80cd24f98c934b56b98f1692797a3d6de00c23e70fb0370845`.
The isolated test emulator and Gradle daemon were stopped after validation; the
main preview remains on the exact-query results. About 19 GB of system space
remains free, above the required 15 GiB reserve. Tools and builds remain under
LiberBuild; this task's backups, retained public responses and screen evidence are under
`LiberBuild/evidence/facebook-search-audit-2026-10-17`.

### 0.25.1 Continuous reading toward newest

Entering Following, combined feeds, creator feeds and personal channels now admits
the complete currently fetched local window before restoring the saved post and
pixel offset. An initial empty Room emission does not consume that admission.
Existing unread posts remain distinct from new arrivals, shared read marks and
independent Continue points survive, and temporarily missing accepted identities
use only the remaining bounded metadata budget after all available keys.

Active arrivals still wait behind the reserved banner while the reader is idle.
An upward-reading gesture near the head, including a pull at the old top with no
consumable distance, admits its waiting batch once scrolling settles. Preparation
continues saving the latest viewport. The visible original retains its lazy-list
key even if a merged group selects a different primary, so native keyed anchoring
preserves the viewport without a synthetic scroll. Automatic admission preserves
the session's stopping baseline. Removing the actual reading point through cache
or rule changes requires Show/Newest instead of moving its frozen card. Initial
navigation buttons wait for anchor restoration; raw gesture intent resets at idle.

Newest jumps to the fetched head and keeps Continue; neither that action nor
admission marks skipped posts read. Source checks still require successful refresh
ledger entries. This change adds no requests, source pagination, background polling
or cache expansion. The ordinary source-scoped 1,000-post window and expiry remain;
sources can omit older history, and unavailable/out-of-window anchors resume nearby
with a notice after explicit navigation or a new session.

The core run passes **854 tests / 59 suites**, and the isolated API 35 run passes
**47 feed/merger/channel tests plus 26 portability/RSS/migration regressions**,
with no failures, errors or skips. The 17 feed-memory cases include new-session
arrival admission after an empty startup emission, real upward swipes through the
newer prefix, idle-banner stability, a zero-consumption pull at the old head,
and exact Newest/Continue restoration across a local store reload. Native screen
captures were inspected. These deterministic local fixtures establish reader
behavior, not new claims about live public-source coverage or physical phones.
Debug, release and AndroidTest builds pass. Lint reports **0 errors / 93 warnings**.
The non-debuggable 0.25.1/code 35 preview updates the main API 29 emulator under the
same UID; all 44 private preference/database/file records are byte-identical before
and after installation, before launch. Its installed APK hash matches the package.
The actual light-theme combined feed was opened and inspected on API 29, including
ordinary scrolling toward older content and back toward newer content. Existing
pending-source status stayed visible. The API 35 test emulator and Gradle daemon
were stopped afterward; the main preview remains running. System storage retains
more than the required 15 GiB free.

The first native run exposed an index-request race at the old head, an obsolete
first-empty-batch expectation and loss of absent accepted identities at session
entry. All three are addressed in the final passing run; failure logs remain in
the evidence directory. A build client disconnected during final lint; the same
frozen sources were rebuilt and checked again. Evidence is under
`LiberBuild/evidence/feed-catch-up-2026-10-17`.

### 0.25.0 Collected originals from the same source

The previous matcher deliberately rejected every same-platform pair, and native
readers kept only one thread per platform. Verified same-source repeats now form
bounded complete-link cohorts before cross-platform ambiguity is evaluated. Groups
retain up to 64 exact original post identities, with positive known dates spanning
at most 48 hours. Repeated originals use the earliest original for chronological
placement; a later copy does not republish a read card. Each candidate still needs
strong caption evidence and an author-bearing canonical account URL, or a durable
explicit curator source. Matching display names or local subscription membership
alone do not establish that source. Unknown dates, replies, previews, incompatible
claims, unrelated authors and ambiguous matches remain separate.

Read-only inspection of the preview cache found a specific useful case: the
RapidResponse47 border quotation explicitly reposted by White House, and White
House's shorter quotation, carry the same supplied Twitter video-thumbnail clip
ID and the same Nitter /WhiteHouse/rss provenance. Both original authors remain
intact. The quoted-excerpt path requires matching curator provenance/validated
repost attribution, the exact supported video ID, a unique literal quotation of
at least ten words/eight distinct words/fifty characters, and no substantive
outside commentary. Generic images and word overlap do not enter this path.
Other cached Vance posts discussing different subjects and using different clips
are not treated as duplicates. Only two such cached clip originals were established;
three-original behavior is separately tested with explicit synthetic fixtures.
No live-source lookup or authorship certification is implied by this local evidence.

One card shows the shared or fullest caption, with unique aligned context retained.
A small originals control occupies the existing fixed-height discussion row and
opens every original caption, date, attribution, supplied count and authentic URL.
The sheet's header/Close stay pinned while its original records scroll. A shortened
clip quotation cannot displace the complete caption. Exact later copies can enrich
controls/read aliases without moving or changing the frozen body; new wording and
regrouping wait for Show. This applies to platform Following, combined feeds,
creator feeds and channels. Existing exclusion checks inspect every known original.

All combines obtained replies from every original; each platform tab appears once
and combines its original threads. Jobs, pages, pagination, shared-comment rooms,
drafts, retries, failures and captured replies use exact original identities.
Duplicate comment identities are shown once, while separate comments with the
same wording remain distinct. Native reply copying uses the actual comment URL.
The post copy control prefers the largest supplied nonnegative reply count on its
primary platform; missing counts use a stable earliest-original fallback, never
score or the number of currently loaded replies. Shared participation still chooses
an exact original page, with its URL visible while writing. No real service write
or change in external comment-service availability is claimed.

Saved records, their selected media references, available reply streams and paired
archives preserve every original. All original URLs/dates/identity/archive checks
and repeated-source evidence are revalidated on native import. A short quote that
cannot fully align can use a validated per-platform fullest-caption projection;
this presentation never removes its stored original. Source-edition chips select
exact identities even for several X posts, and a full-text result from a later
original opens that exact edition. Source packs/OPML do not acquire saved content.
Reader format compatibility is documented in docs/PORTABILITY.md. Ordinary cache,
protected downloads, NetworkGateway and fail-closed Tor policy remain intact.

The final core run passes **848 tests** across 59 suites, with no failures, errors
or skips. Debug/release/AndroidTest builds pass; lint has **0 errors / 93 warnings**.
The final isolated API 35 run passes **48 repeat/discussion/library cases** and
**92 integration regressions**, with no failures, errors or skips. The latter cover
hybrid fusion, feed memory, channels, portability, RSS, settings, link opening,
icons, database migration and AI provenance. Tests use local fixtures/in-memory
readers, not a claim of new public-source reliability. Real native dark screens
showing the actual cached clip-shaped pair, three retained originals, combined
replies, exact reply links, saved editions and source-specific search were captured
and inspected, along with the upgraded light preview's actual cached repeat group.

The first core run exposed a boxed Integer/Long comparator fallback when counts
were missing; it now explicitly uses -1L. Lint caught two composition reads of
StateFlow.value; child controls now receive the already-collected parent state,
without suppression. The first native batch passed 45/48: two failures exposed
the scrolling Close control, fixed by pinning the sheet header, and one source-
search wait needed to account for merged clickable-card semantics. The latter now
also asserts the actual backend result's exact source identity. Original functional
assertions remain, and failed logs are retained beside the final passing runs.

The non-debuggable **0.25.0/code 34** preview is installed under unchanged UID
10116. All **44 private files** match a fresh pre-install backup byte for byte before launch;
no main app clear/restore is used. Installed/package APK SHA-256 match. Root is used
only by development tools for emulator backups; the app requires no privilege.
The isolated compatibility emulator and Gradle daemon are stopped afterward. Tools,
cache, builds, backups and evidence remain under LiberBuild in
evidence/same-source-repeats-2026-10-17/. System free space remains over 18 GiB.
Physical-phone/ARM performance and future external discussion/source availability
remain unverified. Routes without author-bearing URLs or sufficient durable curator
provenance keep ambiguous repeats separate.

### 0.24.0 Portable readers, source packs and ordinary feeds

Settings → Import & export uses Android's document picker for documented native
JSON and ordinary-feed OPML. Reader files preserve subscriptions, folders,
explicit creator bundles, personal-channel rules, filter-list choices, collections,
saved text and available captured threads. Reading progress is omitted by default
on export; reader preferences and progress are separate unchecked import choices.
Connection/privacy policy, commenter identity, compiled filters and downloaded
media bytes do not transfer. Copied AI declarations remain readable, while copied
credential verification and signer trust reset for local rechecking. Saved media
manifests show missing files and import never queues their downloads.

Source packs contain only selected authentic sources and public labels, without
local bundles, folders, paused state, rules or social frontend overrides. A channel's
Share action first opens the individual-account chooser. A recipient previews a
pack with nothing checked and explicitly selects sources. Existing subscriptions
keep their local settings. Standard OPML transfers ordinary RSS/Atom feeds and
folder labels, including standard FreshRSS lists; it does not execute FreshRSS
scraping/cURL/cookie/proxy extensions or follow remote outlines. NewPipe's separate
subscription export and full database backup informed the design; its video service
exports are not accepted as social subscriptions. Primary research, exact schemas,
limits and examples are in docs/PORTABILITY.md. No NewPipe implementation was copied.

Strict bounded UTF-8, JSON and OPML validation runs before mutations. Saved-model
fields, exact scalar types, URLs, source editions, available attachments and paired
archives are checked before Gson constructs saved objects. A social source's
retrieval identity and kind are derived from its displayed original URL; an imported
hint cannot display one creator while fetching another. Positive channel references
to missing/unselected sources remain stable nonmatching placeholders, never an empty
All rule. Exclusions still map to an already subscribed source when it is unchecked.
Repeated imports reuse matching rules and collections, existing saved content wins,
and accepted new text/threads are indexed locally. Progress merges preserve existing
anchors and remap source/channel contexts; successful-refresh ledgers are excluded,
so the receiving device checks sources itself and unavailable sources stay pending.

Ordinary public HTTPS RSS 2.0, RDF RSS and Atom sources use NetworkGateway for
feed/media traffic and preserve selected Tor/filter policy. Adding, previewing and
importing do not fetch a source. Opening an obtained entry reads its exact cached or
saved feed text; it does not scrape the full article. Linkless entries have stable
internal keys, while copied outgoing URLs use the authentic feed link. Dates remain
unknown when absent and sort after dated entries. Native channels, reading memory,
source attribution, saved copies and dated substantial cross-post fusion include
RSS; an undated entry cannot establish contemporaneous fusion. Empty, HTML/login
or unavailable feed responses remain failed/pending. Feed parsing never follows
pagination, external content, hubs or executable markup. Ordinary-feed domains are
not learned as original social-site domains: a blog and its media work with social
original fallback disabled, while known original social hosts and unavailable Tor
retain their existing restrictions. RSS adds no global search route, social link
alias or route-heartbeat pool.

The final core run passes **833 tests** across 58 suites, with no failures, errors
or skips. Debug, release and AndroidTest builds pass; lint has **0 errors / 93
warnings**. The final isolated API 35 run passes **25 portability/RSS native tests**
and **80 channel/feed-memory/fusion/settings/discussion/library/link/icon
regressions**, with no failures, errors or skips. Coverage includes real native
preview choices, source-only selection, whole-file rejection, collection/search and
thread restoration, media-missing states, URL-authoritative identities, stable
missing rules and unchecked existing exclusions, optional progress, RSS provenance
and unknown dates, saved merged RSS/social copies after cache eviction, and the
real gateway's original-host and fail-closed Tor policy. Network fixtures use a
terminal in-memory interceptor after the real privacy interceptor and do not reach
DNS or sockets. No new live-public-feed availability or full-article behavior is
claimed.

The real Android CreateDocument/OpenDocument flow exported a reader with progress
absent, imported the documented two-source pack through a zero-selected preview,
and selected only its Bluesky account. A second real file export confirmed exactly
that original URL was added and the unchecked RSS source was absent. The native
channel Share entry opened its source chooser. Dark native feed/RSS/Why screens,
reader import/export and options, pack preview, Android file pickers and the light
release settings/subscription screens were captured and inspected.

Initial checks found a pubDate case-normalization bug and corrected it. Test
fixtures/assertions also needed a valid Atom XML quote, explicit social provenance,
and a substring assertion for the complete HTTPS help text; assertions were retained.
A lint run overlapped source editing and failed in FIR analysis; the frozen final
runs succeed with no suppression. Integration review corrected saved-fusion URL
recognition and platform-count bounds for RSS. Earlier failed outputs are retained
alongside successful runs rather than reported as passing.

The non-debuggable **0.24.0/code 33** preview is installed under unchanged UID
10116. All **44 private files** match fresh pre-install backups byte for byte before
launch; no main app clear/restore was used. The final installed and packaged APK
SHA-256 match. Root access was used only by development tools for emulator backups;
anti social still requires no root or privileged installation. Compatibility testing used
its own emulator, stopped afterward. Tools, caches, builds, backups and evidence
remain under LiberBuild in evidence/portability-2026-10-17/. System free space is
over 18 GiB. Physical-phone/ARM behavior, FreshRSS server synchronization and future
external-source availability remain unverified; FreshRSS account sync is not an
implemented capability.

### 0.23.0 Explicit personal channels

Following now has local channel chips and a pinned management control. The native
editor selects creator bundles, literal words/phrases, exact hashtags, content
types, a supplied minimum video duration and negative terms/tags/creators/types.
One creator can be selected in several channels. Alternatives within a field use
OR; filled positive fields use AND on one actual source edition. No interests,
location or same-handle identity are inferred. Local source membership is read
from the feed store, rather than guessed from the rendered author.

Any exclusion in a known cross-post edition vetoes the whole collected card.
The channel also shadows the global counterpart sidecar with admitted groups, so
excluded text cannot return through fusion or discussion controls. Matching uses
the authentic text and links presented to the reader. Patterns and source maps
are prepared once per snapshot, with matching off the main thread. The card's
Why disclosure records readable source/word/tag/type/duration reasons at batch
admission. It describes known fetched copies, never proof about unseen editions.

Every channel/rule fingerprint/platform scope has a separate bounded reading
context. Posts remain chronological; arrivals and changed membership wait for
Show. Invalid active rules never silently widen to All. Manual channel refreshes
fetch only eligible subscriptions and unavailable selected accounts remain
pending. Background ordinary subscription scheduling stays shared. Source IDs are
selected before the repository's 1,000-post limit; a quiet selected creator is
covered behind 1,000 unrelated newer rows. Switching platform, opening a post and
returning from post/settings all retain the channel instead of restoring an old
platform target. Channel evaluation makes no requests and changes no Tor policy.

Optional Media.durationMs is retained in saved snapshots and extracted only from
selected supported attachment records: Reddit playable media, X video info,
Instagram/Threads media, Mastodon-compatible attachment metadata and Media RSS.
Invalid or omitted runtimes stay unknown; unknown video cannot match a minimum.
Current Nitter HTML/Bluesky readers and other routes can omit runtime. There are
no added downloads/playback probes or claims of exhaustive history/source support.

Versioned local rules and selected IDs participate in settings backups. Strict
syntax/schema/limits are checked before any import mutation. Missing saved source
IDs remain explicit in the editor. Ordinary cache expiration and protected saved
records/downloads retain their existing behavior. No database migration is needed.

The final core run passes **793 tests** with no failures, errors or skips.
Debug, release and AndroidTest builds pass. Lint has **0 errors / 93 warnings**.
The final API 35 native run passes **20 channel tests** and
**36 feed-memory/fusion/settings/discussion regressions**, with no failures,
errors or skips. Coverage includes real editor touches and saved exclusions,
strict/atomic imports, true subscription membership, any-edition exclusions,
staged arrivals, source-pending boundaries, duration omission and platform/Back
navigation. Actual dark native channel/feed/Why/editor screens and the light
release channel entry were captured and inspected. Channel tests use local fixtures
and block unexpected networking; no new live-source capability is claimed.

An initial parallel Gradle attempt conflicted over incremental output; it was
stopped, and the single-owner final build/test run succeeded. Two editor tests
initially tapped offscreen nested chips: the old Activity-token helper did not
hide the sheet's IME, and materializing a tall lazy item did not make its nested
horizontal chip visible. The test helper now checks the IME and reaches the actual
visible target through real scrolling; all original rule/persistence assertions
are retained. No semantic-only click bypass is used.

The non-debuggable **0.23.0/code 32** preview is installed under unchanged UID
10116. All **44 private files** match a fresh pre-upgrade
backup byte for byte before launch. Installed and packaged SHA-256 match. No main
app data clear/restore was used. Root access was used only by development tools
for the emulator backup; anti social still runs on stock unrooted Android. Compatibility
testing used its own emulator, stopped afterward. Tools, caches, build output,
backups and captures remain under LiberBuild, including the relocated Gradle and
Kotlin project caches. System free space remains 19 GiB. Evidence is in
`evidence/personal-channels-2026-10-17/`. Physical-phone/ARM performance and future
external-source availability remain unverified.

### 0.22.4 Feed-opened discussions keep service failures out of the reading area

The follow-up report exposed two remaining visible failure labels in All: the
source summary and the collapsed participation row. A retained draft or action
status could also automatically expand the editor when returning to a post.
The original feed-card Comments action uses the same DiscussionSheet, but prior
failure tests opened that sheet directly instead of testing the card entry.

All now shows a reply count, a small accessible unavailable-source indicator and
Source details. Loading, individual errors and retry controls remain behind that
disclosure. The compact participation row has a neutral Join/Continue label.
Fresh discussions and returns from a platform tab start with the editor collapsed,
including retained text, reply targets and unconfirmed-action state. Drafts remain
in the reader; explicit Reply actions request expansion through a session UI event,
including selecting the same reply after minimizing. Original-platform tabs remain
read-only. The dedicated shared tab still exposes its own service failure.
Unavailable and supplied-empty discussions remain distinct; no failure becomes an
empty successful result. Networking and Tor policy are unchanged.

The final unit suite was explicitly rerun: **745 passed**, no failures or skips.
Debug, release and instrumentation APK builds passed; lint reports **0 errors /
89 warnings**. The isolated API 35 run completed **29 deterministic tests** in
41.208 seconds with **two opt-in live cases skipped**. Three new regressions cover
the real PostCard Comments button during a shared-service failure, reopening that
card with a retained draft and failed service, and deliberately replying to the
same comment after minimizing. The prior failed-service case now asserts immediate
reply visibility without seeking or scrolling. Source errors remain asserted after
opening Source details; cancellation, shared/original stream independence, sort,
local identity and saved-thread regressions also pass.

Twenty-one native captures were pulled and six inspected, including the feed
entry, retained draft, explicit reply and honest all-unavailable states. No live
writes or extra service availability tests were enabled. The service audit remains
an upstream failure observation, not proof of shutdown or successful participation.

The non-debuggable **0.22.4/code 31** preview is installed under unchanged UID
10116. All **44 private files** match the fresh pre-upgrade backup byte for byte
before launch. Installed and packaged APK SHA-256 match. Compatibility testing used
its own emulator, which is stopped afterward. Tools, backups and evidence are in
`evidence/feed-comments-2026-10-17/` under LiberBuild; system free space is 19 GiB.

During initial inspection of the old release, the main preview briefly displayed
an Android ANR dialog. Its captured main stack was in native font/text measurement
and lazy prefetch with I/O pressure, not a network or discussion lock. Rendering
resumed, and Wait dismissed it. This observation is retained separately; no font
or emulator performance fix is claimed here. Physical-phone/ARM performance and
future public-source availability remain unverified.

### 0.22.3 All shows available comments before failed-source diagnostics

The reported release screen was reproduced on the actual GTA-themed cross-post.
All contained the original source tabs, and X could supply **27 public replies**,
but Instagram's unsupported-comment status and the large fixed shared-comment
editor filled the initial viewport. Full per-source/community status blocks were
also placed before every reply. Separately, original readers and the shared
service competed for the same two permits, allowing a slow shared request plus
one slow platform to hold up the other platforms.

All now renders one compact source summary followed by obtained replies.
**Source details** expands individual failures, notices and retry controls.
Unavailable and supplied-empty sources have different explanations; neither is
presented as proof that the original post has no comments. The All composer starts
as a compact participation row. Opening/minimizing it preserves the draft, and
switching its original-page edition keeps an opened editor open. Platform tabs
remain read-only; the community tab retains its full editor. All is still the
leftmost/default tab, and combines original-platform and shared replies.

Two original streams now run independently of one serialized shared stream.
Shared timeouts start after acquiring that stream's permit. Leaving All cancels
queued shared rooms; promoting a room cancels its old secondary job, resets its
canceled pending state and rejects stale completions. Previously obtained shared
replies survive a failed active refresh only for the same original URL and sort.
Such a retained read-only page does not authorize publishing before the active
refresh succeeds. Closing/changing order prevents canceled original results from
repopulating another session's state or memory. Networking continues through
NetworkGateway and keeps the selected Tor policy.

The first native batch exposed two issues: an aggregate-text fixture needed an
explicit substring assertion, and the editor's URL-scoped expansion state closed
it when the user changed platform editions. Both were corrected without removing
the failing assertions. The final focused run passes **26 deterministic cases**
and skips two explicitly opt-in live cases; **34 reader/fusion/library/link
regressions** also pass. Eleven new native methods cover request isolation,
cancellation/edition/sort guards, failed-refresh retention, immediate no-scroll
reply visibility, progressive arrivals, honest empty/failure states and draft
preservation. The core suite was explicitly rerun: **745 passed**, zero failures,
errors or skips. Final debug/release/AndroidTest builds and lint pass with
**0 errors / 89 warnings**. Actual dark native tests and light release screens
were captured and inspected.

The non-debuggable **0.22.3/code 30** release is installed on the main API 29
preview. Before first launch, all **47 private files** match the fresh pre-upgrade
backup byte for byte; UID is unchanged and no main-app clear/restore was used.
The authentic X post was opened through anti social's normal routing. Its matched
Instagram edition was present, All was selected, and **27 replies loaded** with
the first X reply visible without scrolling past status blocks. The capture took
about 1.5 seconds after opening comments; this is a warm/cache-affected observation,
not a general network or phone latency claim. The final preview is left on that
All view with source details collapsed.

Three bounded public service checks (its homepage discussion and both original
post roots) returned identical **HTTP 503** HTML before JSON parsing. The current
distributed 0.14.0 client's read parameters match anti social. This was not a valid empty
discussion or missing-room 404, and no identity or public write was attempted.
The release isolates and presents the outage; it does not establish working live
participation. Current Instagram readers still do not supply public comments.

A separate read-only audit found an existing cold-start matching limitation:
a previous discussion refresh had replaced the X parent record's media URL with
an uncached WebP variant, while its former JPEG remained cached. Image fingerprints
currently live in process memory; viewing the current X image restored the short-
caption match. Subscriptions and source identities were intact. This release does
not add persistent image proof or claim every counterpart is discoverable before
its image is observed. The private diagnosis records possible future cache-only
hydration/validated association work without weakening matching or adding requests.

Evidence: `evidence/comments-all-2026-10-17/` contains final build/core/native logs,
backend protocol/HTTP observations, native screenshots, the real release UI receipt
and private upgrade/cold-linkage evidence. API 35 tests ran on the isolated emulator;
only the main visible preview remains running. Physical-phone/ARM speed and future
external-source availability remain unverified.

### 0.22.2 AI declaration coverage and unknown inspection status

The reported GTA-themed post exposed an expectation mismatch: anti social reads supplied
AI declarations and credentials, not image appearance. The exact already-cached
X and Instagram JPEGs were inspected without another image request. Their SHA-256
digests are `422d8b761489303d729bad63cdc00c34c0cbd6c89a84a2ed3b35f97fc10334d9`
and `72ed19b74cf8ac6b7235781a9eda82180317443b8fe7fa57963ee4236f8d3d86`.
The real Android C2PA reader and embedded metadata parser found no readable AI
declaration, credential, removal evidence or inspection limitation in either copy.
The selected original Instagram public record has a null
`ai_label_info.attribution_label_text`; checked public X records supply no AI label.
These observations do not establish the image's production method or authenticity.
The post is **not automatically identified as AI by this release**.

A gray information control now exposes **AI status unknown** after a real local
inspection, including incomplete-format/size explanations. Empty DTOs do not
pretend to be inspections. Neutral reports survive saving without selected media
and are described as earlier saved observations when the bytes are absent.
Bitmap-memory hits restore evicted reports from existing local files. Video poster
observations are explicitly scoped to the preview image. GIF, HEIF/HEIC, AVIF,
QuickTime and supported audio container routing now reaches the credential reader;
unknown formats are incomplete rather than silently clean. PNG compressed comments
count as a metadata family without treating arbitrary compressed prose as an AI
declaration. Three exact Schema.org digital-source enumeration URLs are accepted
inside selected structured records. Original Instagram records can attach three
known official English labels only after exact shortcode/PK, source and canonical
checks; unrelated recommendations/comments and unknown labels are ignored.
Live non-null Instagram label extraction remains unverified; positive cases are
synthetic fixtures using official label meanings. No extra app request or upload
was added, and absence never produces an AI or stripping accusation.

**745 core tests pass**, with zero failures, errors or skips. The first central
run found one newly added fixture using an unregistered fake frontend host; using
its authentic post URL fixed the fixture while retaining the separate-post and
no-label assertions. Debug, release and AndroidTest assembly and final lint pass:
**0 errors, 89 warnings**. API 35 instrumentation has **85 passing cases** across
three batches: 31 AI cases, 53 reader/library/fusion/community regressions and one
opt-in exact-local-file probe covering both JPEGs. One Android-8-only case and two
explicitly opt-in live checks are skipped. Saved-copy assertions compare all
original fields plus independently SHA-bound neutral observations; later backup,
eviction and re-save checks preserve the complete captured snapshot exactly.
Actual dark native declaration, unknown, incomplete, saved and preview screens,
plus the exact JPEG probe and light release feed, were captured and inspected.

External-only visual-model experiments do **not** justify shipping an automatic
classifier as this fix. The small EfficientNet model misses both target copies and
flags known camera photos; its weights also carry noncommercial restrictions.
The official MIT CommunityForensics model likewise misses both copies. Its FP32
export was checked against every original tensor, and the five original authors'
DALL-E2 example scores reproduce to four decimals, ruling out a reversed-label or
preprocessing explanation. Detectra-v3 still misses the target at its published
threshold and flags the human-made CGI. A newer Frontier model flags it but also flags a NASA camera photograph
and human-made 2008 Blender CGI. FerretNet B/S also miss the target and flag a
known cat photograph. A small distilled candidate is similarly inconclusive.
These are bounded convenience checks, not an accuracy benchmark, proof of this
post's origin or Android latency measurements. No model, inference runtime,
classifier upload or visual verdict has been added to the app. Visual detection
remains unresolved; the metadata improvements must not be described as solving it.

The non-debuggable **0.22.2/code 29** release is installed on the main API 29 laptop
preview with the same UID. A fresh private backup before replacement verified all
**47 private files byte-for-byte unchanged before first launch**; no clear or
restore was used. Its exact GTA card shows the gray information control and the
unknown manifest. Evidence stays outside the repository under
`evidence/ai-gta-audit-2026-10-17/`, including build/test logs, private original-byte
copies, native inspection reports/screens and model evaluation scripts/results.
Public-source availability, ARM behavior and physical-phone speed remain outside
these local checks.

### 0.22.1 authentic outward links

A presentation/action boundary now converts known frontend addresses to the
original social site before displaying, copying or opening them. This covers
attached link rows, URLs inside ordinary/fused captions, profiles, replies,
CommentAnywhere text and local saved readers. Long-pressing an attached link
copies its authentic address through an accessible Copy link action. Equivalent
frontend/original forms collapse to one attached link. Selected sites still open
through Darwin's normal routing; other links retain the external-browser policy.

Conversion is local and performs no resolution request. All bundled frontend
services, registered compatible pathway aliases and the historical `nitter.net`
alias are covered. Transformed search/tag paths, archive replays, escaped path
separators, query identities/case and meaningful comment fragments are preserved.
Nitter's `#m` transport marker is removed. ClearURLs cleaning runs on the genuine
destination. Shared API/RSS hosts, deceptive host suffixes, ambiguous embedded
URLs, wrong/nondefault legacy ports, unknown hosts and opaque frontend utilities
are not fabricated as another site's content. Raw posts, source provenance,
media transport, saved attachments and encrypted backups remain unchanged;
technical route diagnostics retain actual source information.

The previous private 160-record cache snapshot contains 69 frontend attached
links and four bodies with reader URLs. **All 69 links convert**, with **zero
frontend hyperlinks remaining** in that sample; the four bodies are normalized
at rendering. No new repeated title/body echoes arise in the diagnostic. This
is a local snapshot check, not a claim to identify arbitrary unknown frontends.

**725 core tests passed with zero failures, errors or skips.** Eleven new link methods
exercise every bundled frontend round-trip, real cached profile/search/broadcast
patterns, dynamic aliases, tracking/identity parameters, HTTP(S)/ports, archive
anchors, shortened/proxied artwork, protocol-relative and bare reader labels,
prose punctuation, email exclusions, idempotence and raw-record preservation.
A separate real-preview check exposed a verified `Post by <author>:` heading
that repeated its complete caption with an added ellipsis. One additional test
covers the narrow fix, both ellipsis styles, wrong-author/conflicting-text guards
and raw JSON preservation. Plain editorial ellipses remain unchanged.
The first link run found a test that expected a fragment already removed by the
network request converter. The corrected test separately proves conversion of
an actually supplied fragment and the unchanged network-route behavior.

Debug, AndroidTest and release assembly and lint passed: **0 errors, 89 warnings**.
The broad API 35 run passed 90 deterministic cases and skipped two opt-in live
checks; its new navigation case timed out because the fixture watched a direct-
post loader while opening a profile (profiles use the source pool). The corrected
focused run passed both link cases in **9.537 seconds**: exact original profile,
direct-post and native search destinations, encoded hashtag clipboard, saved
original copy/open and unchanged raw source/provenance. This validates 91 distinct
deterministic cases across the runs, rather than claiming the initial broad run
was entirely green. Actual dark native feed and saved reader screens were
captured and inspected. No external write or live-source
availability claim is made by these fixtures.

After the final caption guard, the API 35 reader batch passed **20/20** in
**56.472 seconds**, covering public-link display/copy/open, cross-post fusion,
source comments, saved editions and stable feed memory on the final build.
The actual API 29 preview shows the previously repeated Instagram caption once
and no generated echo heading. Its current process reported no fatal exception.
A fresh backup immediately before the final install verified all **47 private
files unchanged by per-file SHA-256** before launch, with UID 10116 retained.
Cold startup took 339 ms. No clear/restore was needed, and development backup
access does not introduce an app root requirement. Only the user's ordinary
API 29 preview remains running; system storage retains 19 GiB free.

The final non-debuggable release is **0.22.1 / code 28**. Its APK SHA-256 is
`63c9e6a294eb888ceb01ee61f3938c4daa2f4cf6fa2ecde0a2bd59402316ac12`.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/original-links-2026-10-17/`.

### 0.22.0 cross-post alignment and caption echoes

A fresh, private snapshot of the existing preview contained 160 cached posts.
The audit found generated `RT by @WhiteHouse:` headlines repeating their full
body, source-specific trailing Nitter video labels, play-glyph heading echoes,
and distinctive short captions below the old matching thresholds. Presentation
now removes only proven echoes and retains a small repost attribution; original
cached and saved records remain intact. Against the same snapshot, 19 redundant
headlines disappear, 15 of them retaining repost attribution. Local grouping
changes 157 cards to 156 by joining the missed First Responders pair. This
comparison does not include image-appearance hints or new network requests.

Partial fusion now aligns all shared whole sentences/quoted clauses in source
order. Shared openings and conclusions appear once around attributed additions;
context shared by two of three editions appears once with both sources. Ambiguous
repetition, reordered passages and competing edits stay separate. URLs contribute
no prose to minimum-overlap thresholds. A shortened link label is equivalent only
when its unique supplied destination proves it. Explicit HTTP/HTTPS distinctions,
URL path/query case, numeric signs, negations, operators and meaningful symbols
are preserved. A bare cross-link cannot establish duplication. Short complete
captions require linked accounts plus decoded image evidence, or a tightly timed
seven-word caption when one source omits media. Matching videos alone cannot stand
in for matching images.

Independent read-only research compared public EFF Bluesky/Mastodon posts linked
from its official site. Observed cases included differing inline attributions
between shared quotations, shortened versus full link labels, and different
updates pointing at the same article. Three literal public examples passed in the
intermediate 710-test run. The shipped regressions use independently authored
synthetic counterparts with dated primary-source provenance; literal social text
and third-party quotations are kept only in external research evidence. Bluesky
extraction now retains validated HTTPS facet/embed destinations without another
request or changing caption text.

**713 core tests passed, with no failures, errors or skips.** New coverage includes
multi-passage/two-of-three alignment, immutable raw records, claim/URL/symbol
safety, short captions, image-only corroboration, explicit-link false positives,
source heading artifacts and all three research patterns. The indexed 1,000-post
fixture produced 500 cards in **168 ms** on the build host. Grouping the actual
160-record snapshot took **592 ms**. These are single local host observations,
not phone, network or Tor latency benchmarks.

Following and combined feeds freeze their visible wording and reading anchor
while late source context waits for Show. Adoption includes updated known editions
outside the current retrieval window. Quick Save retains validated source editions
and loaded replies, including late exact/short matches, without implicitly
queuing media downloads. The live gallery still uses the primary edition; selected
saved media and original editions retain their existing controls.

**Native Android validation:** the API 35 regression passed **80 deterministic
cases** in 174.045 seconds (82 reported, two opt-in live checks skipped). It covers
comments/All/composer restrictions, Settings, subscriptions, fusion, saved local
copies/search/backup, database migration and exact feed reading memory. An
additional multi-passage case passed in **9.383 seconds**, checking one shared
opening and conclusion, both attributed middle additions in order, original
comment roots, the saved Combined reader and byte-for-byte original payloads.
Actual native dark feed/save screens were captured and inspected. No physical
phone or ARM runtime was tested. No live social or community writes were made.

Debug, AndroidTest and release assembly and lint passed: **0 errors, 90 warnings**.
The ordinary, non-debuggable **0.22.0 / code 27** release is installed in the
API 29 laptop preview with UID 10116 retained. A fresh backup immediately before
upgrade verified all **47 private files unchanged by per-file SHA-256** after
installation and before launch; no data clear or restore was needed. The actual
cached Utah repost screen now shows its caption once and the small repost credit,
with the same reading card and incoming/pending banner. Cold startup took 353 ms.
The installed APK matches the packaged release SHA-256:
`0402ff2f9735dd91b6a9cc8dbcc5ead6b5283d59fd534213b7bb931026a78b69`.
Development-only emulator backup access does not add any app root requirement.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/fusion-audit-2026-10-17/`.

### 0.21.1 shared participation and All

All is now the leftmost/default tab for single-platform and merged posts. It
combines original-platform replies with the icon-only community service's rooms.
Only All and the community tab render the shared composer. Original platform
tabs have no draft, publish, reply or vote controls that imply platform posting.
The shared destination and original edition are explicit in the composer. Each
original page retains its own room and drafts; loaded rooms can be saved together.
Newest preserves reply trees; Slop interleaves source ranks without comparing
scores across platforms. Reads share two concurrency permits.

Settings → Comments now contains the local service toggle, connection status,
explicit read-only connection check, content/Tor settings link and confirmed
identity reset. Reading/checking does not create an identity or require a key.
Disabling the service prevents gateway requests. HTTP status survives gateway
errors so server failures have a useful explanation. Cooldown starts when a
failed request finishes, including after timeouts.

**683 core tests passed, with no failures or skips.** The added HTTP-500 fixture
checks retained status, Retry-After and no anonymous registration. Final debug
and release assembly and lint passed: **0 errors, 90 warnings**.

The final API 26 focused run passed **16 deterministic cases** in 31.782 seconds;
API 35 passed the same **16 deterministic cases** in 35.185 seconds. Each
runner reported 18 tests including two separately opt-in live checks skipped
by assumption. This covers mixed All content, default/leftmost/icon-only tabs,
composer removal on platform tabs, original-root separation, shared publication,
partial service failure, ordered reply trees, saved captures, encrypted ownership
and local settings/reset. Actual dark reading/composition and light Settings
screens were captured and inspected, including the keyboard on Android 8.

A first API 29 run was interrupted when the host-GPU emulator exited; it is not
counted as a successful suite. Its log contains EGL/context failures. Subsequent
compatibility emulators are run separately. A fresh private backup preceded all
preview testing; it contains no community participation identity. The completed
initial API 29 regression exposed a default-tab preference bug: changing the
selection order could change an unstored active tab. Selection changes now
preserve the active site, and the Settings test checks it after recreation. A
reply test also needed to scroll its button above the fixed composer before
tapping; it now verifies the actual reply target before entering text. The
API 26/35 focused runs preceded only this final preference fix and test adjustment;
the final API 29 run covers the resulting build.

A fresh read-only production GET again returned **HTTP 500** from `/api/list` on
2026-10-17. The public extension package remains 0.14.0 with the same SHA-256
recorded below. No live posting, voting, signup or token refresh was performed.
External write acceptance remains unverified; a native fixture passing does not
establish that the service's hosted backend is available.

A separate native public connection check passed in **1.911 seconds**, displaying
HTTP 500 in Settings without creating an identity. Its actual screen was inspected.

The final API 29 regression passed **60 deterministic cases** in 83.217 seconds
(62 reported including two opt-in skips). It covers shared discussions, Settings
and recreation, source-tab selection, subscriptions, feed fusion, protected local
saves/search/backups, database upgrades and default tab preferences.

**Release preview:** anti social **0.21.1 / code 26** is installed as an ordinary,
non-debuggable app (UID 10116). All **47 backed-up private files** were restored
with exact per-file SHA-256 equality before launch. The installed APK hash matches
the packaged release. Startup completed in 316 ms; the actual restored Instagram
post screen was inspected and AndroidRuntime reported no fatal exception. Only
the visible API 29 preview remains running. The system filesystem retains 19 GiB
free. Development-only root access was used for backup restoration, never by an
app feature.

Evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/comment-settings-2026-10-17/`.

### 0.21.0 native CommentAnywhere

Static protocol research used CommentAnywhere.app's public website/privacy page
and its distributed Chrome extension **0.14.0**. The package SHA-256 is
`84fff1135aee7c132a5e028f59d90965e12239db438c64c3722f8d37d842edeb`.
Only the downloaded package was inspected; no browser extension was installed or
executed and Darwin runtime/profile state was not touched. No reusable upstream
source license was identified, so no extension source/runtime is distributed.
The native implementation and provenance record are independent anti social additions.

**682 core tests passed.** Fourteen new protocol cases cover original-page keys
across all 21 platforms, known frontend reversal, archive unwrapping, federated
server separation and rejection of identity-bearing queries. MockWebServer
checks cover list/subtree parameters, New/Top order, lazy anonymous identity,
refresh without ownership loss, post/reply/vote/edit/delete fields, credential
stripping on ordinary reads, exact service scope after filter rewrites, stopped
redirects, one-shot writes after a dropped connection, fail-closed Tor, bounded
trees/text and rejection of HTML/error/missing-list responses. Authentication
responses and comment bodies are excluded from gateway response diagnostics.

**Android native checks:** the API 29 regression batch passed all **46 cases** in
70.083 seconds, including the opt-in read-only live availability check. This
covered subscriptions, cross-post fusion, original platform/All comment streams,
saved library/search/backup and database upgrades, plus the initial native
CommentAnywhere suite. After the Android 8 focus fix and final feed-entry check,
the focused API 29 run passed **11 deterministic cases** in 21.756 seconds; API 35
passed the same **11** in 23.746 seconds. Both runners reported 12 cases with the
separate live-service case skipped by assumption. API 26 passed **10 focused
deterministic cases** in 16.424 seconds (11 reported including that same skip),
before adding the final feed-button/count-label case. No physical phone or ARM
runtime was exercised.

Native cases verify the primary tab, deferred platform reads, per-edition URL and
draft isolation, Newest/Slop requests, one explicit reply during an in-flight
action, cancellation of obsolete reads, uncertain-write draft protection,
transparent service failure, original-vs-frontend/archive identity, local saved
comment search after cache eviction, capture merges that preserve older text,
foreign-root capture guards, encrypted no-backup credentials and explicit reset.
The final feed case taps the actual post-card comment button and checks that an
original platform reply count is not advertised as a community count.

Actual native screens exposed two corrected problems: duplicate IME padding and
a full comment header squeezed the composer; Android 8 dialogs automatically
focused their first editor while browsing. The sheet now detects keyboard
visibility inside its dialog, compacts its header while typing, and starts with
reader focus. Draft/reply/publish controls were inspected on actual emulator
screens; dialog screenshot capture waits for native window animations. Keystore
storage and reset passed on APIs 26, 29 and 35 without privileged app features.

**External limits:** read-only NetworkGateway production probes on 2026-10-17
returned HTTP **500** from `/api/list` and **503** from `/api/top/sites`. A native
live read displayed an unavailable conversation, not an empty working thread.
No live comments, votes, edits, deletes, anonymous signups or token refreshes were
performed. Live write acceptance, backend URL aliases and public Tor reachability
remain unverified. Native fixtures exercise the implementation, not the external
operator's availability. No Chrome-extension origin is forged.

Debug/release assembly and lint passed: **0 errors, 89 existing warnings**.
The release APK is **216,001,001 bytes**, SHA-256
`e351bd852fb41bf9684654b2bfc4534eef2e7bda36ecf2a55e92be69ae6dfc71`.
Builds, upstream package/hash records, live read outcomes, unit/native logs and
screens are under
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/commentanywhere-2026-10-17/`.
The preview's 44 original private files were backed up before tests; the restored
file hashes are verified before launch. Development emulator backup access is
not an app/root requirement. Additional test emulators are stopped afterward;
the ordinary laptop preview remains on its existing KVM setup. System storage
retained approximately 20 GiB free, above the 15 GiB floor.

### 0.20.0 individual public source cartography

The dated atlas covers **21 platforms, 268 reviewed pathways/projects and 225
primary references**. Each platform has its own research record. Request formats,
content scopes, credentials/fees, working samples, blocked/gated approaches and
abandoned/client-only ideas stay explicit in `source-discovery.json`. This does
not claim exhaustive enumeration of the entire web. The independent runtime
catalog adds **171 registrations** to Darwin's 69 sources; some are format
variants, shared-backend aliases or selected-origin templates, not independent
servers. Every candidate starts UNKNOWN. Failed samples remain failures, never
bundled HEALTHY flags. Official Nitter's project-notice page is excluded.

Four bounded NetworkGateway raw-response batches and a production-reader audit
used the bundled native network filter, HTTPS validation, two global workers and
one request per host at a time. **19 of 24 live reader cases** returned matching
native cards; five returned typed failure/empty results. A subsequent captured
TikTok bridge replay produced ten real undated cards for on-demand reading only.
Actual TikWM comment JSON produced twenty exact-post-bound comments. Timings
are single-connection direct-mode observations, not Tor, phone or media-playback
benchmarks. Representative extraction samples: Sotwe 408 ms, Pinterest widgets
394 ms, two-request OGInstagram 365 ms, Pixelfed 473 ms, Fx X/Bluesky about 1.3 s.
Signed media URLs were extracted, not all downloaded or played.

Eleven large-platform captured payloads, seven protocol payloads, a one-request
Arctic base-post replay and three generic feed/error captures were checked
through the real parsers/Gateway fixture transport. Empty Meta oEmbed script
shells and Atom-formatted error feeds are rejected. Pinterest widgets and TikTok
creator/bridge documents with unknown source dates cannot establish a
chronological subscription refresh. GETTR's older successful feed does not
establish recent account activity. Restricted original APIs, private data,
allowlisted OAuth services, expired TLS hosts and unverifiable reply APIs remain
explicit limits; no credentials, cookie harvesting, remote scripts or root are
introduced.

The source pool keeps alternates, permitted originals and historical recovery in
separate tiers. Scoped `accepts` checks prevent inappropriate requests; shared
feed-renderer hosts cannot become reverse social-link aliases. CACHE data is
excluded from freshness, heartbeat winners and live-versus-old archive pairing.
Arctic post reads use one request; comments have their own reader. Unknown
capture dates show **Archive copy**, never a fabricated month/year. Existing
verified replay captures retain **Archived MM/YY**. Tor selection fails closed
for every added transport under local tests. Public Tor/source reachability was
not separately verified in this pass.

All **668 core tests pass** with zero failures, errors or skips. Debug, release,
instrumentation APK builds and lint pass; lint reports **0 errors / 89 warnings**.
The native regression batch passes **42/42 on API29**, covering source scopes,
archive labels, galleries/local saves, heartbeat, feed memory, incoming links,
archive recovery and saved collections. **9/9 pass on API26 and 9/9 on API35**.
Actual native screens were inspected, including a live NASA X profile delivered
through the new FxEmbed reader on API35. Late presentation changes only shorten
healthy-source notices; account/key/protocol details remain in source diagnostics
and the atlas.

Preliminary instrumentation against retained preview data exposed legitimate
startup reads racing a quiet-heartbeat assertion and shared progress text. The
isolated assertion passed; the final complete native batch used clean fixture
state after verified backup, then restored the preview. API35's initial screenshot
evidence helper encountered optional external storage before it was ready; it
now supports internal app storage as a fallback and the nine-case rerun passes.
No product checks were weakened to accept an unavailable route.

The non-debuggable 0.20.0/code24 release runs under ordinary app UID10116. All
**44 original private files were restored byte for byte** before preview launch.
No Darwin runtime or profile state changed. Evidence and tool/emulator state live
under `LiberBuild/evidence/source-cartography-2026-10-17`; system storage retains
more than 15 GiB free. External source availability, Tor timing, complete media
playback and physical ARM performance remain unverified beyond stated samples.

### 0.19.0 adaptive route heartbeat and routing audit

All **561 core tests pass**, with zero failures/errors/skips (63.158 s). The
22-case clock-controlled heartbeat suite covers stable plans extending toward
24 hours, volatile winners, dense passive traffic, independent failed-path
backoff/recovery, request/host budgets, stale leases, clock bounds, same-tick real
reads, federated origin/capability/Tor isolation, private persistence and expiry.
Five real local HTTP integration cases check recent-read suppression, one due
canary, persistence before opening its socket, foreground preemption without
cancelling its maintenance caller, unavailable Tor, server pauses surviving
restore, and a missing account not becoming a site-wide outage. Existing hedge,
usable-content and source-tier regressions remain included.

The Android 29 regression batch reports **40 cases: 39 passed and one opt-in live
Tor/direct-exit case skipped** (106.584 s). Connection controls, feed memory,
settings, cross-post fusion, saved AI provenance and all six new heartbeat cases
are included. After the final immediate-rescheduling and fixture-isolation edits,
**30/30** relevant heartbeat/connection/settings/feed-memory cases pass on Android
29 (55.731 s); the final heartbeat six also pass on Android 35 (1.155 s) and
Android 26 (1.165 s). The six controller tests inject device conditions; they do
not directly test the platform's battery intent extras. These
are deterministic local checks; they do not claim a new public-service or Tor-exit
benchmark. Actual native Following and connection screens were inspected on both
APIs, with an additional native startup inspection on Android 26. Debug, release
and instrumentation APKs build; lint has zero errors and
89 warnings, including an inlined newer low-battery intent key whose absent-key
fallback on older Android is false; battery percentage remains checked separately.

Normal requested reads establish per-site demand and the actual winning route.
Probes update usable-content health, without training real delivery latency or
hedge timing. Plan identity uses the two leading eligible paths in privacy-tier
order, rather than raw timing changes or response text. Each pulse considers at
most three promising paths and sends only one canary. Target-specific empty,
private and missing-account failures remain scoped; they do not establish a
shared-site outage. Selected federated servers retain separate origins/ports.
Recognized redirects to original data are not credited as independent alternate
health, and their content waits behind usable alternates. That redirect-tier rule
was inspected in code; a new live redirect-host test is not claimed.

Automatic work shares persisted two-per-five-minute/twelve-per-hour limits,
one-per-host/fifteen-minute spacing and one in-flight lease. Its consumed budget
is committed before networking. Foreground reads cancel only the probe child;
caller/outer timeouts do not train source outages. Each automatic sounding has
a four-HTTP-exchange limit including redirects/transport retries, a shared 512 KiB
decoded-text limit and an eight-second direct/sixteen-second Tor timeout.
The gateway counter runs before request headers, not before DNS/TLS setup; this
is not a claim to cap all wire bytes. Over-budget or target-specific soundings are
inconclusive. HTTP 429/503 Retry-After deltas and HTTP dates are bounded to 24 hours
and survive restore; rate-limited RSS does not immediately retry as HTML.

Android queues one earliest-due WorkManager job with a minimum 15-minute delay,
unmetered connectivity, device-idle and battery-not-low constraints. A foreground
quiet opportunity shares the same policy and rechecks connectivity/battery/Tor.
Only recently activated scopes from selected, enabled profile/community follows
participate; cold tabs, custom RSS, posts, searches, comments and media do not.
Plans are coalesced before source lookup, so many followed authors on one site do
not cause per-author route enumeration. Subscription/selection/transport edits
reschedule the local plan. Background-refresh-off cancels background heartbeat.
A heartbeat-only process configures local privacy policy without starting Tor or
search preconnections; unavailable selected Tor defers without waiting or direct
fallback. Ordinary UI and authorized feed refresh still start their chosen
transport. No privileged Android feature was introduced.

The ordinary heartbeat cache is capped at 512 scope/512 path records and a 1 MiB
file, with 30-day state expiry and seven-day demand expiry. It stores hashes,
hosts and federated origins, not account URLs, queries or post content, and is
excluded from saved copies/backups. Android may defer or skip constrained work;
no exact wakeup, universal frontend availability, measured battery saving or
physical-phone/ARM performance improvement is claimed. Primary scheduling and
server-delay references are linked in README. Evidence is under
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/route-heartbeat-2026-10-17/`.

The non-debuggable 0.19.0 release (code 23) is installed in the laptop preview;
its installed APK SHA-256 matches the packaged release. All 44 pre-test private
data files were restored with exact content hashes before launch. The universal
preview APK is 215,799,210 bytes. The initial Android compilation had a generic
type/default-argument spacing error, corrected before the successful builds.
The first development restore command had an ADB shell-argument quoting error;
separate absolute-path commands restored the verified on-device archive, followed
by the complete file-hash comparison. Neither issue is reported as an app failure.

### 0.18.0 AI disclosures and metadata history

All **523 core tests pass**, with zero failures/errors/skips (44.554 s). The 51
added cases cover source/attachment scope, deterministic prose, metadata loss,
unsigned/captured verdicts, container boundaries, namespace identity, malformed
XML/DTD rejection, decompression bounds and image-identity safeguards. Debug,
release and instrumentation APKs build; lint reports zero errors and 88 warnings.

The Android 29 regression batch reports **90 cases: 89 passed, one API-26-only
case skipped** (100.628 s). It includes existing feed memory, cross-post fusion,
saved library/media, migration and archive storage/dwell checks. Following final
JPEG comparison guards and captured-device-note changes, the focused 24-case
feature suite passes on Android 29 (**23 passed, one skipped**, 12.286 s), Android
35 (**23 passed, one skipped**, 17.828 s), and Android 26 (**17 passed, seven
newer-native-validator cases skipped**, 12.194 s). The Android 26-specific case
actually executes and confirms that the app installs/runs without loading the
API-28 native validator. Native screens were inspected on all three APIs in light
and dark themes, including the actual feed, fused-source/archived editions and
offline saved-copy reader. No physical-phone/ARM or 16 KiB page-size runtime is
claimed tested.

The official C2PA Android SDK 0.0.14 is SHA-pinned; the published AAR contains Rust
SDK 0.91.2. Native tests use original local artwork and a test signer: a genuine
signed AI declaration passes asset/signature checks while remaining untrusted;
changing a pixel invalidates the credential despite the signed declaration still
being readable. Used action templates and reachable ingredients are read, while
unrelated manifests, generic software names and ordinary edits never establish
AI use. A real redaction fixture retains the earlier AI history and explicitly
records metadata removal. A remote-manifest TCP canary receives no connection.
SDK networking, OCSP, identity decoding and default remote-manifest resolution are
disabled, with a denying HTTP resolver as a second guard. No signer trust list is
configured or fetched by anti social. Signature integrity remains separate from signer
trust and from truthfulness of the depicted content.

Unsigned IPTC/XMP and exact TC260 namespace/Label-1 declarations come from bounded
container metadata, not arbitrary strings inside pixels, captions or URLs.
Matching schema.org records retain post versus attached-media scope, including
gallery URLs; foreign records, authors, thumbnails and recommendations do not
label the post. TikTok's public `ShowAIGC`/`AIGCDescription` fields were found in
official SEO-generation code, so these and unverified label enums are excluded.
China's normative GB 45438-2025 format was downloaded from a government host;
the image-guide namespace detail was indexed from TC260 but its complete current
PDF endpoint returned an HTML shell. Both exact namespace spellings are accepted;
this is not a claim to validate the regulation or decode all regional markers.

Missing metadata alone gives no warning. **METADATA REMOVED** reflects an
explicit credential redaction and remains described as a declaration, with
validation status. **METADATA GAP** requires another observed copy with the same
encoded picture/color key and additional metadata. It does not establish the
direction, actor, intent, AI use or authenticity. Re-encoded pictures are not
matched for this purpose. Color profiles and opaque JPEG APP11 picture extensions
remain in comparison keys; extended JPEG XMP is recognized as present even though
it is not reassembled. Validated PNG/JPEG, compressed XMP, WebP and TIFF fixtures,
changed pixels/color profiles, corruption, foreign RDF subjects and hostile XML
are covered by core tests. The store tests cover exact-copy differences,
VALID-to-INVALID byte replacement, bounded expiry/eviction and clearing queued
work without revival. The final explicit-file inspection compares the actual
byte hash before reusing a signature verdict.

The feature inspects only existing files, up to 32 MiB, on a 16-entry background
queue; observations are bounded to 256 entries/seven days in memory. No extra
image/stream downloads or third-party detection requests are introduced.
Asynchronous badges occupy a fixed footer and native geometry tests prove no
feed-anchor/card-height movement before explicit expansion. Each fused source
and archived edition keeps its own declarations. Saved copies retain readable
claims through backup, but serialized signature verdicts lose verified/trusted
status; available protected bytes are checked locally again. A historical
unsupported-device note cannot misdescribe a different device after import.
SDK licenses and provenance are preserved in source and bundled APK notices.

The non-debuggable 0.18.0 release (code 22) is installed in the laptop preview;
its installed APK SHA-256 matches the packaged release. All 44 pre-test private
data files were restored with exact content hashes before launch. The universal
APK is 215,766,442 bytes (about 206 MiB); its four-ABI native provenance libraries
add about 86 MiB. This storage cost is documented rather than equated with the
smaller compressed SDK download.

The initial 24-case native run had one fixture failure: the real Tor controller
legitimately became ready while a test manually held an unavailable gateway
policy. The fixture now stops/waits for the controller before installing its
offline test policy, including after importing settings, and restores Tor using
fresh controller state afterward. Production privacy checks were not weakened.
External survival of metadata through every platform/frontend, proprietary
watermarks, streamed-video credentials and unavailable remote manifests remain
source-dependent or unverified. Evidence and primary research references are in
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/ai-metadata-2026-10-17/`.

### 0.17.0 cross-post fusion

All **472 core tests pass**, with zero failures/errors/skips (42.925 s of
execution). Nineteen added cases cover echoed/truncated attribution titles, the
reported cached-source shapes, shared-span alignment, typography, competing
claims, numeric signs/operators, image evidence, ambiguity and input bounds.
An audit of the actual cached **Securing the border** X/Instagram pair changes
from two groups to one. The original cached fields are unchanged: one source
expanded a visible domain into an HTTPS link in its echoed title, while the
other prefixed the author and truncated that title.

Partial-caption fusion requires linked subscription ownership, known timestamps
within 48 hours, matching picture evidence and a substantial shared passage.
Native presentation keeps shared text once and attributes additional wording
above/below it. Original post IDs, URLs and per-platform discussion roots remain
separate. **All**, platform tabs and **Newest / Slop** stay comment controls.
Image evidence comes from exact media URLs or bounded local fingerprints of
already-decoded images; it adds no network requests. Cropping, watermarks,
substantially edited images and weak/simple artwork are outside its reliable
coverage. Image similarity never establishes account identity.

Frozen feed bodies adopt new context and regrouping only through **Show**.
Saved copies preserve all captured original editions, selected attachments and
paired archives, with local full-text indexing and backup roundtrips. A later
valid archive attachment may enrich a saved source without changing its captured
text or hiding the original editions. No database schema migration is required.

Debug, release and instrumentation APKs build successfully; lint reports zero
errors and 87 warnings. **58 distinct Android 29 cases have passing results**:
the 58-case batch passed 57 (99.894 s), then all eight cross-post cases passed
(15.645 s) after correcting the remaining Instagram reply fixture. The three
fusion flows, six image-identity cases, late-archive capture and discussion-save
flow also pass on Android 35 (11 cases, 24.116 s); the corrected cached-border
regression passes there separately (one case, 4.437 s). These are passing results
across recorded batches, not a claim of one uninterrupted passing 58-case run.

The checks exercise original comment roots and **All**, both comment orders,
unchanged source fields, quick saving and discussion saving, local editions,
full-text search, cache eviction, archive discovery after saving, backup import,
bounded/expiring fingerprints and reading-position preservation before/after
**Show**, including counterparts outside a feed's current source window. Actual
native feed/comment screens were inspected on both APIs, with the saved original
edition reader inspected on Android 29.

Initial failures remain recorded. Eight synthetic bitmap fixtures initially used
immutable pixels with Canvas; tall-card controls needed vertical feed gestures
as well as horizontal-row exposure. The next run exposed real resize/JPEG hash
aliasing: horizontal distance 15 despite almost identical spatial colors. The
fix progressively downsamples and derives both hashes from one prefiltered
thumbnail, preserving every matching/complexity threshold and negative fixture.
A shared refresh-loader test gate was tied to its exact fixture account/job to
exclude an Activity's earlier refresh. Finally, a trailing slash produced an
Instagram reply URL with the root post's identity; that fixture now constructs
and asserts a distinct reply identity. Production identity validation was not
weakened. External frontend or comment availability was not revalidated by these
deterministic local checks; physical phones remain untested.

The non-debuggable 0.17.0 release (code 21) is installed in the laptop preview;
its installed APK hash matches the packaged release. All 44 pre-test private
data files were restored with exact content hashes. The streamed restore first
omitted its last four files; pushing a verified archive onto the emulator before
extracting resolved that development-tool transfer issue. The actual reported
border card now shows its caption once and retains Instagram/X comment choices
plus **All**. Instagram explicitly reports that current readers do not supply
public comments; this release does not claim newly available Instagram threads.

Evidence is under
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/border-dedup-2026-10-17/`.

### 0.16.0 native image discovery

All **453 core tests pass**, with zero failures/errors/skips (44.605 s of test
execution), including 14 new session and 23 provider/upload/routing cases. The
complete debug/release/test APKs build successfully; lint reports zero errors and
85 warnings. Archive Gateway regressions are included in the full run.

The image viewer's **Search image** action uses the current gallery page. The
standalone app submits a new white-composited JPEG with metadata removed, at most
1024 pixels per edge and 2 MiB. It never submits the source post/media URL or
mutates the shared displayed bitmap. Ordinary search uploads and result pages go
through NetworkGateway on Tor, with bounded bodies, no credential/cookie jar,
origin pinning and cancellation. Lens's cross-origin 303 is captured as a result
location; the image body is not forwarded, and the adapter validates the exact
provider origin/path before a separate protected GET. Archive scope protections
remain intact. Yandex's two-origin pool stops on policy refusals and rate limits;
only actual readable matches can promote an origin.

Discovery reads actual provider result structures rather than all page anchors
or arbitrary script URLs. Supported destinations are canonicalized and deduplicated
with a platform prefix before routing. Exact readable post/profile identity is
verified after fetching, and previews/empty/login pages never become cards. The
session bounds provider pages, targets, text and concurrent reads, publishes fast
results independently, resumes after cancellation and exposes explicit retries.
Shared pictures never establish account identity. Existing cross-post grouping
keeps alternate platform comment streams. Native navigation reuses hydrated data
and returns to the existing image-search session without repeating discovery.

The **11 new native cases pass on Android 29 and Android 35**. They cover image
metadata/alpha/dimension/byte bounds; real cached and saved images without network
requests; selected gallery page, progressive native accounts/posts, duplicate
engine hits, identical numeric IDs on different platforms, failed-engine retry,
unreadable previews, Stop/Continue, late-result rejection and result/back
navigation. Three existing native search-feed cases also pass on both APIs; an
opt-in old live-search case is skipped. The final deterministic batches report
15 cases each including that skipped case: Android 29 **16.251 s**, Android 35
**17.023 s**. The first Android 29 run had one test fixture error: its synthetic X
username exceeded the existing 15-character public-profile rule. Correcting that
fixture and adding an explicit validity assertion resolved it; production
validation was not loosened. Screens were inspected in dark and light themes.

Live checks use an explicitly supplied public NASA Earth image, never a user's
private photo. The first native Tor check returned no usable results (8.676 s):
Bing/Lens supplied unreadable pages and Yandex returned 403. Research then found
SauceNAO's public upload route and Yandex's second public origin. The later native
Gateway check completed **Tor upload → actual social-link discovery → existing
routing → readable X post → native card** in **13.762 s** (15.171 s test total).
SauceNAO supplied the one supported post; Yandex refused both origins on that
connection, and Bing/Lens remained unavailable. Separate research requests through
the bundled app Tor returned real Yandex .com/.ru cards, but this does not establish
reliable availability from the app on every exit. Yandex's next-page check hit a
SmartCaptcha; it remains a failure, not an empty successful page.

Source coverage is limited. SauceNAO's own status page dates its Twitter index to
December 2019; it also returns visually similar images. It does not establish
account ownership or current comprehensive coverage. Bing/Lens public uploads
accept bytes but their currently observed interstitial/classification responses
do not expose readable matches. TinEye/ascii2d challenge and RepostSleuth's public
503 were researched and excluded, rather than advertised as working providers.
No browser session, browser companion, paid API/key, cookie/IP rotation or CAPTCHA
solving was introduced. A temporary developer-only ADB SOCKS forward used for
provider research was removed; it is not an app dependency. Evidence, raw public
responses and primary source references are under
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/image-search-2026-10-16/`.

### 0.15.0 source labels, platform artwork and native video

Post/discussion attribution identifies the original social platform, plus the
canonical server for federated services. Retrieval host, cache and archive route
remain data/diagnostic provenance rather than replacing that identity. Existing
archive date labels are unchanged, with provider information retained for
accessibility. All 21 platform marks are bundled vectors with matching light/dark
ink, optical sizing and restrained tiles. The six former text fallbacks are now
vectors. Exact artwork provenance and licenses are in `licenses/platform-icons.md`;
the APK also carries the complete artwork notices and licenses as an asset.

All **416 core tests** pass (zero failures/errors/skips), including six attribution,
15 video extraction and seven format/probe cases. Source fixtures cover Nitter's
lazy and signed proxy streams without rewriting their routes, one selected video
alternative with poster/MIME, Reddit HLS/DASH/progressive metadata, X extended
attachments/malformed variants, nested RSS/Telegram sources, Open Graph stream vs
HTML embed distinction, and old snapshots without the new nullable MIME field.
Unknown extensionless endpoints use at most a 4 KiB body prefix through
NetworkGateway; known types need no probe. A 416 Range refusal gets one bounded
ordinary GET through the same filters/Tor policy. Denied or unavailable-Tor probes
do not reach their fixture server.

The **13 new native checks** pass on Android 29 (**9.334 s**) and Android 35
(**13.619 s**): three real player-control cases, two complete icon galleries, five
DASH privacy/parser cases, a remote URI-scheme guard and two adaptive decoders.
Native playback verifies play/pause, sound, seeking, the same Player/position
across expand/collapse, ON_STOP pausing and retry after corrupt local bytes are
replaced. Screenshot inspection found the stock controller fading out briefly
during expansion; disabling its animation before attachment removed that flash,
and tests now assert that the stock controls are never shown. Synthetic HLS and
static DASH render a real first frame with both audio/video tracks selected while
Tor is unavailable. They verify the packaged modules, not playlist downloading.

The selected-media/library suite passes **25/25** on Android 29 (**17.000 s**) and
Android 35 (**21.841 s**). Its added real-worker case rejects known extensionless
HLS/DASH downloads before connecting. Existing cross-post/reading-memory/
subscription/storage checks pass **32/32 (46.944 s)** on Android 29. Android 35
also passes the actual application Library and captured multi-platform discussion
flow **1/1 (7.118 s)**. Native icon and player screens were inspected in both themes.

The remaining 80-case regression run recorded 73 passes and seven failures. A
visible Android **System UI ANR** dialog left by BOOT_COMPLETED held window focus,
correctly preventing five archive dwell tests and one keyboard test from running.
The seventh expected the old visible archive-provider footer; it now checks that
the badge exposes that provider accessibly and the footer says **X**. After
dismissing the system dialog, those exact **7/7 cases passed (25.592 s)**. Across
the runs, **149 distinct Android 29 and 39 Android 35 cases have passing results**;
this is not a claim of one uninterrupted passing 80-case batch. An earlier batch
ended in a host `gfxstream::TextureResize` / RenderThread SIGSEGV, and the emulator
was cold-booted with KVM and host graphics. The host crash, System UI window/input
focus evidence and initial failures remain recorded. No production dwell guard
was weakened to get a passing test.

Remote manifests, keys, segments and redirects use the NetworkGateway client;
remote playlists cannot select file/content/asset/data URI readers, including
through the video cache. Saved playback stays file-only. DASH uses an explicit
factory with a manifest parser that replaces every dynamic UTC timing entry with
direct device time, preserving periods and metadata. This avoids Media3 1.8.0's
independent SNTP fallback for missing/NTP timing. Static manifests stay unchanged.
See the exact [Media3 DASH implementation](https://github.com/androidx/media/blob/1.8.0/libraries/exoplayer_dash/src/main/java/androidx/media3/exoplayer/dash/DashMediaSource.java)
and official [HLS](https://developer.android.com/media/media3/exoplayer/hls) /
[DASH](https://developer.android.com/media/media3/exoplayer/dash) MIME/module guides.

These are deterministic emulator fixtures, not a certification that every public
platform exposes a playable video. Expired/signed URLs, login-only content, DRM,
unexposed streams and legacy VMAP can remain unavailable. Live DASH depends on an
accurate device clock. Codec support and physical-phone performance remain device
dependent; HLS/DASH offline downloads remain unsupported. Evidence lives under
`LiberBuild/evidence/reader-polish-2026-10-16/` and adjacent `reader-polish-*.log` files.
The initial overlapping Gradle report and zero-case method selection are excluded
from the passing checks and retained as diagnostics.

Final debug/release/instrumentation builds and lint complete, with **zero lint
errors and 84 warnings**. After testing, all **48 original data files** were
restored byte-for-byte, including subscriptions, saved data, history, preferences
and reading memory. The reusable transport/clock findings are recorded durably in
Bifrost as `capture.revision.2e9fd0a46a5b4f32a049d79232fd0d5e`; this is an
attributed engineering report, not an independent execution attestation.

The laptop runs the signed, non-debuggable **0.15.0 / code 19** preview under its
existing UID. Installed APK, packaged preview and launcher stamp match SHA-256
`dda86ce00f7024d1f7dd6863275db522df57afd2fe69a969d9a299b4d37cd3a0`.
The actual restored native feed was inspected with its new marks and original
platform attribution. Debug/preview APKs, source archive and hashes are packaged
under `artifacts/`; no external distribution was performed.

### 0.14.0 local saved copies, collections and full-text search

The library now reads durable post snapshots and explicitly selected files, with
loaded public replies and verified archive pairs retained as data. Text commits
before queued media work. Existing bookmarks backfill without a source request.
Room's additive v1→v2→v3 migrations preserve subscriptions, ordinary posts, saved
records, history, health and protected media; the v2→v3 test reconstructs a real
v2 layout before upgrading. New tables hold copy details, collections, selected
assets, optional retained history and an external-content FTS4 index.

All **388 core cases** pass, with zero failures, errors or skips. Six new query
cases cover bounded Unicode/prefix words, literal operator input and conversational
fillers. Real Android testing caught a portability issue hidden by the original
single-word fixtures: an explicit `AND` can be interpreted as the literal word
“and” by standard FTS4 builds. Queries now use the portable implicit intersection
described in [SQLite's standard query syntax](https://www.sqlite.org/fts3.html#section_3_2).
The native regression searches multiple nonadjacent words in a reply containing no
“and”, both before and after ordinary cache cleanup.

The new focused native suite passes **24/24 on Android 29 (18.472 s)** and
**24/24 on Android 35 (23.983 s)**. This includes the existing v1 migration case,
four library schema/search cases, seven saved-library integration cases, three
native library/settings cases and nine selected-media/playback cases. Coverage
includes case-insensitive collection links and cascades, a 301-document owner
remaining one result, preserved initial root/reply editions with newly appended
replies, selected gallery bytes, captured reply search/attribution/URL, bounded
opt-in history, history clear preserving saved copies, stale transfer rejection,
and seven invalid backup type inputs rejected before data/settings mutate.

The existing native regression groups also pass **32/32 (45.920 s)** and
**80/80 (127.242 s)** on Android 29: **135 distinct native cases** including the
23 additions. Android 35 additionally passes the real application Library and
cross-platform captured-discussion flow (**1/1, 6.614 s**), giving 25 distinct
modern-Android checks. Debug, release and instrumentation packages build; final
lint reports **zero errors and 83 warnings**. One interrupted regression batch
ended when the host emulator's `gfxstream` RenderThread crashed in `TextureResize`
(host SIGSEGV, recorded by systemd-coredump), rather than an Android app exception.
The second emulator was stopped and the interrupted 80-case group passed after
a fresh KVM boot. Initial failures and the host crash metadata are retained.

The actual save sheet creates a collection, saves a selected image and loaded
reply, clears ordinary cache, finds a two-word match in that reply, opens the
local reader and explicitly opens the original only on request. Native screenshots
were inspected for the save sheet, search, gallery, captured replies, retained
history and independent video/history controls. The collection test exposed a
focus/keyboard callback resuming on a Room worker in the instrumentation context;
view effects and save callbacks now dispatch explicitly to Android's main thread.
Lazy-list fixtures scroll their parent before interacting with uncomposed choices.

Local playback uses a file-only Media3 data source. A real native player renders
the saved fixture while Tor is unavailable and remains the same player across
four unrelated library revisions. Missing media never prepares a network client.
Media checks cover selected-file reuse, Tor waiting, a full video allowance,
playlist refusal, HTML/JSON/text masquerading as successful media, runtime cache
limits and independent age expiry. Recognition proves a media container, not
support for every codec. Downloads use NetworkGateway, guarded staging/rename and
per-copy asset tokens; interrupted transfers retry the whole file. Removing videos
rotates intent metadata atomically and retains text/images/replies/collections.

Already-viewed ordinary images are validated and staged under the media lock,
then published through the existing owner/token-guarded archive→media commit.
The old path stays available to active decoders and ordinary cleanup reclaims it.
The worker has no connection prerequisite: local preservation happens first, while
missing remote files wait/retry with backoff. A separate real-worker check passed
in **1.928 s** with emulator Wi-Fi/mobile data disabled, Android reporting no
active default network and required Tor unavailable. The protected PNG matched
the original bytes, survived ordinary cleanup, and produced zero fixture-host
gateway events. The emulator's original network settings were restored exactly.

List/search work loads at most 200 matching owners and their media metadata;
captured-thread payloads parse only when opening/editing a copy. Icons use saved
keys, history quotas use byte/time projections, token checks use indexed asset
metadata, and already-indexed copies are not rebuilt every startup. Backup import
invalidates the affected owners' indexes before backfilling changed material.
Ordinary cleanup protects saved payloads/files; deselecting a formerly downloaded
attachment releases its bytes when no other saved copy references them. Larger
media/history deletions are split below older SQLite parameter limits.

These are deterministic local/native tests, not a claim that every public source
currently supplies a full post, gallery, thread or downloadable video. No new
platform frontend was certified healthy by this feature. Full-text history starts
off; its payload allowance excludes SQLite index overhead. Saved copies are
protected until explicitly removed. HLS/DASH offline playlists remain unsupported;
encrypted metadata backups preserve text/replies/collections, not media bytes.
Physical-phone/ARM behavior and long-running downloads across OEM power managers
remain unverified. Evidence is under `LiberBuild/evidence/local-library-2026-10-16/`
and the `local-library-*.log` build/test logs beside it; diagnostic state copies are
private and excluded from source artifacts.

The pre-feature laptop state was restored after the test runs: all **48 original
data files** matched their backed-up bytes, including database/WAL, subscriptions,
saved data, history, preferences, reading memory and ordinary content files. The
reusable findings were recorded in Bifrost as
`capture.revision.be44844aafc642538e6d1a137f993be5`; its receipt reports durable
storage and 9/9 semantic units indexed. That receipt is indexing evidence, not an
independent execution attestation. The offline cache-promotion follow-up is
`capture.revision.048b8a7581fa4284af66006f8bdf49c9`, durable with 10/10 units indexed.

The 0.14.0 delivery installed the signed, non-debuggable **0.14.0 / code 18** preview under
its existing UID. The installed APK, packaged preview and launcher stamp share
SHA-256 `40177354ac4f8b19a9f78dfbc105772aaab8fc864474490c2281fb6456ae4a88`.
Its actual native feed was inspected after restoring the original data; newly
fetched posts remain behind the banner and unavailable subscriptions stay pending.
Debug/preview APKs, source archive and checked hashes are under `artifacts/`.

### 0.13.0 dynamic cross-posts and RSS caption echoes

The actual laptop diagnostic contained 38 cached post rows and two followed roots.
The visible White House X item had one canonical database identity, with its
caption repeated verbatim in both RSS title and body; it was rendered twice inside
one card. The other followed root was an unrelated TikTok post several days older.
No acquired live Instagram counterpart was present, so this is not evidence of a
live cross-platform match. Private read-only diagnostic copies remain outside the
source package under `cross-posts-2026-10-16/private/`.

All **382 core cases** passed, zero failures/errors/skips. The 35 additions are
20 matcher, 12 presentation and three reading-alias cases. They cover repeated RSS
headings, entity/Unicode/punctuation variants, linked-source membership for opaque
post URLs, conservative unlinked content, trailing tags, corroborated explicit
truncation, host/ownership checks, replies/previews, dates, repeated same-platform
IDs, canonical snapshots, ambiguity and nontransitive compatibility. Numeric
regressions retain signs, currency, comparisons, decimals and percentages. Common
homepage/article/profile/stock-image buckets cannot disable a precise text match.
The 1,000-post JVM fixture retained 1,000 distinct source threads in 500 cards in
**337 ms** on this host; this is not a physical-phone benchmark. Inputs above
16 KiB or oversized ambiguous candidate sets stay separate. See `core-summary.json`
and `core-tests/` in this feature's evidence folder.

The initial **32 focused native cases** completed on API 29 in **49.027 seconds**
and API 35 in **59.801 seconds**, passing 31 each. Their only failure requested a
31-pixel offset on a single short final card: LazyColumn correctly clamped to the
feed end. The fixture now supplies a real older tail and registers the alternate
subscription before its late post arrives, retaining the exact 31-pixel assertion.
No product code or timeout changed. All **seven new cross-post cases** then passed
in **11.783 seconds** on API 29 and **17.352 seconds** on API 35. Together with the
25 unchanged focused cases, all 32 are verified on both versions. Evidence:
`android*/focused-first-offset-fixture.log`, `cross.log`, exact preference/reading
file restoration receipts and `screens/cross-posts/`. The fixture-only final
Android test build passed in three seconds.

Actual AntiSocialApp screens verify one caption/merged card, inline platform choices,
selected-platform-only comment loading, All plus Slop, detail/Library continuity,
matched saved aliases, late duplicates preserving exact position and actual read
state, genuinely new posts waiting for Show, the 1,000 accepted-identity cap, and
old per-feed history not being republished into the bounded shared read ledger.
Every screenshot was inspected on both versions. Fixtures inject local records
and comment responses; they do not establish upstream availability.

The other **80 native regression cases** passed in **124.085 seconds**. Combined
with the 32 focused cases this verifies **112 distinct API 29 cases**, with the
32 focused cases also verified on API 35. All five real subscription rows and
saved-record rows hash identically before/after testing. See `android/regression.log`,
`protected-data-verified.json` and exact preference/reading-memory receipts.

The native matcher consumes coherent Room content/membership snapshots. It groups
off the UI thread and waits for the initial current index before freezing cards.
Late known counterparts extend reserved-height controls and metadata only; they
do not change the current primary, body, order or anchor. Reading one confidently
matched message carries its read state across copies. Genuine arrivals still
require explicit adoption. Regrouping two already visible rows after an edit also
waits for adoption. Source post bodies, canonical IDs and provenance are retained;
presentation-only caption suppression applies to cached/saved rows without a
migration. No new request path, image fetch, remote classifier or privileged user
feature was introduced; app networking continues through NetworkGateway/Tor policy.
Matching separately hosted or re-encoded pictures by appearance is not implemented.

Debug/release builds, instrumentation APK, all core tests and lint passed; lint
reported zero errors/77 warnings with no suppressed check. See
`evidence/cross-posts-final-build.log` and `cross-posts-final-fixture-build.log`.
Physical-phone/ARM behavior remains untested. Darwin runtime/profile state was
untouched; system free space remains above 15 GiB.

The cache-eviction regression cases intentionally cleared ordinary posts. Before
release review, the 38 pre-test cached samples, two source memberships and their
seen markers were restored from the private diagnostic copy into the current
schema with SQLite integrity/foreign-key checks. Settings, subscriptions and saved
records stayed intact. This is test cleanup, not a runtime cache exemption; see
`ordinary-cache-restored.json`. The installed release's actual cached White House
caption has exactly one native text node, independently of fixtures; see
`delivery/release-caption-verified.json` and `release-native-screen.png`.

Bifrost retained this method as `capture.revision.2fc1221e2cea43cc906d8af411a65cbb`.
The durable/indexed receipt covers that revision (11/11 semantic units), not
execution attestation or upstream availability. Authored input and receipt are
`bifrost-record.json` and `bifrost-receipt.json` in this feature's evidence folder.

Release 0.13.0/versionCode 17 uses preview SHA-256
`14e2a357ace98b9488f8dd0e5b85c40ff311d414b39ece03203f4cd6db296a21`.
The ordinary nondebuggable package remains UID 10116; signature, installed APK
and desktop launcher stamp match. The extra Android 15 emulator is stopped, and
the visible laptop preview is running. Final APK/source/checksum packages are
`artifacts/liber-0.13.0-*`.


### 0.12.0 chronological reading memory and stopping boundary

All **347 core cases** passed, with zero failures/errors/skips. Sixteen new policy
cases cover identity/signed-offset restoration, explicit arrival adoption, older
backfills, unread holes, separate Continue anchors, bounded history, defensive
records and refresh tokens. Six more cover fresh feed qualification and rejection
of cached, preview, indexed, wrong-platform and empty responses. A resolved native
post from indexed discovery can be retained without certifying a latest timeline.
Evidence: `feed-memory-2026-10-16/core-summary.json` and `core-tests/`.

The final **19 focused native cases** passed on API 29 in **25.649 seconds**
and API 35 in **35.127 seconds**: thirteen reading/refresh cases, five storage
cases and the manual Room upgrade case. The real AntiSocialApp/Scaffold cases verify
source removal/multiple membership and fresh ViewModels restoring All, person
and grouped Following at the same post/pixel offset. A stale last URL cannot steal
the restored reader; actual group-chip selection persists, including exact
round-trip support for existing long/multiline group identities. Screens include
`native-person-*` and `native-*-restored-after-new-model.png`. See
`android*/focused.log` and `screens/feed-memory/`.

An earlier 87-case regression run passed 84 in **133.561 seconds**. Three
ConnectionSheet cases injected a Page into target-null Following rather than
using subscribed database records; updating those fixtures to isolated real
follows/posts retained every body/context/Tor assertion. All five then passed in
**9.777 seconds** on unchanged product code. Historical evidence remains in
`android/regression-first-following-fixture-failure.log`, `connection.log` and
`*-before-navigation.log`; final navigation regression results follow below.

The final 87-case navigation regression run completed in **141.148 seconds** with
81 passing and six requiring an environment rerun: five archive-dwell timeouts
while an Android **System UI isn't responding** dialog owned window focus, plus
one keyboard-visibility timeout. Screenshot and `dumpsys window` identified
`Application Not Responding: com.android.systemui`, not a anti social crash. Dismissing
that boot-time dialog restored anti social window focus. An earlier attempt was
interrupted when emulator processes exited; its only reported assertion failure
was a selector matching both the new feed Newest control and comment Newest
sorter. The fixture now uses existing discussion-order tags while retaining exact
text/visibility assertions; all six Subscriptions cases passed in the final run.
The emulator interruption's exact preference/reading-file restoration is recorded
in `android/interruption-state-restored.json`. Test evidence is retained in
`android/regression-interrupted-newest-selector.log`, `regression.log` and
`regression-dwell-screen.png`. No timeouts or product safeguards were relaxed.

With normal app focus restored, all **14 ArchiveHistoryUi/SearchScopeUi cases**
passed in **32.531 seconds** on unchanged product/test code. This completes all
87 earlier regression cases and **105 distinct API 29 cases** overall, plus the
nineteen focused API 35 checks. The final fixture-only selector compile passed in
five seconds; see `evidence/feed-memory-comment-selector-build.log` and
`android/app.antisocial.android.ArchiveHistoryUiTest,app.antisocial.android.SearchScopeUiTest.log`.

Initial native runs exposed a real first-composition race: six of nine cases
failed on both versions because restoration used an empty captured row snapshot
after another effect had populated the mutable rows. It falsely reported an
expired anchor and left the list attached to its initial end marker. The fix
guards/restores from one immutable rendered snapshot and explicitly starts a new
reader at its first card. The subsequent tests assert the same exact post and
pixel offset across recreation and store reload; no timeout or expectation was
weakened. Initial failure logs/screens are retained in
`android*/focused-first-restore-race-failure.log`, `first-run-screens/` and
`android/first-single-diagnostic/`.

The native reader freezes full cards/order until explicit Show/Newest, including
edits and older backfills. Returning accepted keys are labeled available rather
than falsely new. Fixed header, read-marker and boundary heights prevent arrival,
read and check updates from shifting rows. Viewport/focus/lifecycle dwell marks
actual reading; composition, prefetch and jumps do not. Continue retains a
separate return point. Last reading-view/source-group selection is recorded at
the navigation action; a fresh session restores it before any stale direct-link
restoration. Removed person feeds safely return to Following. Previously read tails and unread holes use a stable batch
boundary. Source details retain failed/cancelled reasons; caught-up requires a
loaded/read batch, no waiting changes and every scoped latest check successful,
with its check time. These checks cover fetched public posts, not entire remote
histories. Empty/shell/preview/cache/index-only responses cannot certify success
or overwrite a retained full post. Periodic connection waits are pending checks.
No extra network requests or alternate transport were introduced by reading memory.

Room v2 stores many-to-many post/follow memberships. A transactional upsert keeps
overlapping feeds independent; removal and ordinary post eviction clean the
appropriate joins. The explicit v1→v2 migration preserves the seven existing
tables and backfills recorded extant follows, with no destructive fallback.
Previously overwritten associations can be rediscovered by refreshing their
sources. The actual emulator databases upgraded to v2, and subscription/saved-row
hashes match exactly before/after; primary subscriptions remain five. The seeded
migration case also verifies nonempty saved, history, media, health and seen data.
See `android*/pre-migration/summary.json` and `post-migration/verified.json`.

On-device reading metadata is capped at 4 MiB, 32 contexts, 4,000 shared read IDs
and 2,000 check records. Inactive contexts/checks expire after 90 days; shared IDs
expire after 90 days without reading. Old histories compact to current/Continue
anchors before contexts are evicted. Serialized AtomicFile IO saves after a
750 ms pause, checkpoints within five seconds of continuous movement and flushes
on lifecycle stop. It retains identifiers rather than permanent post bodies.
Expired/unavailable cached anchors resume at a surviving nearby post with a
notice; saved/offline content protection is unchanged. Positions remain local and
are separate from the existing encrypted library-backup payload.

Debug/release builds, Android instrumentation APK and lint passed: zero errors,
77 warnings (including three KTX-style suggestions for the new preference
setters); no check was suppressed. Actual native fixtures and full production
Scaffold/source-sheet screens were inspected on both versions. Standalone
component fixtures omit app chrome; the `native-person-*` images verify the real
insets and theme. Fixture data does not claim upstream availability. Physical
phone/ARM behavior remains untested. Build evidence:
`evidence/feed-memory-final-navigation-build.log`.

Bifrost retained the method and Compose failure as
`capture.revision.e3b1a572da91478ea5fe2a2743c309d1`; its receipt confirms durable,
lexical and semantic visibility of that revision, not execution or external
availability. Authored content and receipt are in this feature's
`bifrost-record.json` and `bifrost-receipt.json`.

The restart/view-selection addendum is
`capture.revision.11b54469eb714e8fb0c006f6065e93fd`, with authored content and
indexed/durable receipt in `bifrost-navigation-record.json` and
`bifrost-navigation-receipt.json`. It explains why an exact anchor also needs the
correct saved scope, and why legacy group identities must round-trip unchanged.

The **0.12.0 / versionCode 16** preview is installed as ordinary UID 10116 without
DEBUGGABLE. Signature, installed APK hash and launcher stamp match SHA-256
`17efbadf47925ac6f14ef8001e8ff403c66f84a11fe85a70ad35cc4f02f1194b`.
Preferences and pre-test reading metadata were restored exactly. Actual release
Following and All screens were inspected. A real release process stop/relaunch
reopened All with the same cached post and identical text bounds, independently
of the ViewModel fixtures; see `delivery/release-cold-restoration-verified.json`,
`release-all-cold-restored-native.png`, the other delivery receipts and
`release-*-native.png`. The extra headless Android 15 test emulator was stopped;
the visible laptop preview remains running. The existing Subscribe floating button can
overlap a post’s offline action at some scroll positions; scrolling exposes the
action, and this shell layout is unchanged by reading memory. APK/source/checksum packages are
`artifacts/liber-0.12.0-*`. System free space remains 21 GiB; Darwin runtime/profile
state was not changed.

### 0.11.0 selected-site links and in-app archive recovery

All **325 core cases** passed with zero failures/errors/skips. Added coverage
includes the 21-platform host registry and strict aliases, selected-site gating
before any shortener request, shared/pasted URLs, bounded same-platform login
continuation extraction, cancellation, exact post identity, live → saved → archive
ordering, preview handling, and recent-shell → readable-older-capture recovery.
Archive recovery is capped at four 64 KiB indexes and four 1 MiB replay bodies;
HTTP failures/challenges/rate limits do not trigger a historical scan. Existing
oldest/current comparison behavior remains separately tested. Evidence:
`direct-links-2026-10-16/core-summary.json` and `core-tests/`.

The real, filtered NetworkGateway audit recovered Jack's first tweet, “just
setting up my twttr”, from a **2008-11-03** Internet Archive capture after recent
captures were unusable. Six responses were read in **22.080 seconds**. The request
was exact, read-only and direct; no JavaScript, account, archive submission or
original media fetch was used. It proves recovery of this capture, not current
availability for other links, original-publication fidelity or Tor availability.
See `direct-links-2026-10-16/live-archive/result.json` and retained responses.

The 15 new native cases passed on the functional APK on API 29 in **20.381 seconds**
and API 35 in **22.248 seconds**. Four check Android host components/setup, four deliver real
VIEW/SEND intents through the activity/VM/repository pipeline, and seven cover
recovered presentation, saving/offline/backup retention, ordinary eviction,
coalescing, cancellation, mode-separated TTLs, quota persistence, rate limits and
forged metadata/assets. Reader network failures and captures in those native cases
are local fixtures; they do not claim upstream content availability. The recovery
label is one compact **Archived MM/yy** in UTC, with provider in the source footer.

The existing 16 focused cases passed in **41.812 seconds** and all 56 older
regression cases passed in **99.015 seconds**: 87 distinct cases on API 29, with
the 15 new cases also checked on API 35. The final SDK-35-only system-bar contrast
adjustment was followed by both 15-case reruns above. Native archived cards on
both versions show exactly one **Archived 02/26** line, one provider footer and no
redundant banner. The latest screenshots are in
`android*/screens/archive-recovery/archive-recovery/recovered-native-post.png`;
the same filenames one directory higher retain the previous presentation.
The actual API 35 reader also has readable status/navigation icons after the
edge-to-edge contrast adjustment. Final debug/release builds, Android test APK and
lint passed with zero errors and 74 warnings. Twenty-one new AppLinkWarning
entries reflect the deliberate absence of autoVerify for sites anti social does not
own; no check was suppressed. See `evidence/direct-links-final-native-bars-build.log`.

The first native runs failed all four intent cases only during ActivityScenario
teardown: production setIntent updated the Activity's launch intent, preventing
the rule from recognizing its DESTROYED transition. Restoring the original intent
in fixture cleanup fixed lifecycle tracking while keeping actual Android intent
delivery and production handling intact. Logs/screens are retained as
`android*/scopes-first-intent-lifecycle-failure.log`. An earlier lint worker had an
internal Kotlin FIR analysis failure; the stable-source complete rebuild passed.
No lint check was disabled.

An isolated Android 15 emulator opened the real **Open by default** page. It listed
101 unverified supported domains, including disabled aliases, as predicted by AOSP
domain collection. Manually approving old.reddit.com and the Twitter domains
appears in `pm get-app-links` user selection, and an implicit VIEW launched anti social.
After the final update, MATCH_DEFAULT_ONLY resolution selects anti social's Reddit
alias, an actual implicit VIEW launches MainActivity, and the native reader
honestly rejects the supplied login-page URL. Unselected Facebook resolves to
Chrome. This does not grant approval on a user's
phone: stock Android requires the user's one-time domain choices. Arbitrary
federated instance hosts remain explicit input/sharing. See `android35/setup/`,
`evidence/android-selected-links-2026-10-16.json` and
`evidence/archive-recovery-2026-10-16/research.md` for primary references.

Replay media/avatar/fullscreen/video/offline requests use the archive replay as
their source scope while keeping the original post's canonical identity. Imported
and cached recovery records must retain valid provider/date/route identity and
archive-pinned assets; they cannot be converted into a fake live current/older pair.
Ordinary recovery cache and persistent courtesy limits are separate from saved
items and the passive older-version quota. Settings has a separate recovery toggle.

Bifrost retained the method as
`capture.revision.02b61cfe72194b48875011d36bf6c4e0`. The receipt confirms durable
storage and lexical visibility; semantic indexing was pending after an embedding
service timeout. It does not attest execution or upstream availability. Authored
content and the exact receipt are retained in `bifrost-record.json` and
`bifrost-receipt.json` under this feature's evidence directory.

The **0.11.0 / versionCode 15** preview is installed under ordinary app UID 10116,
without DEBUGGABLE. Signature verification, installed APK and launcher stamp match
SHA-256 `2b5d414f1dee1b46c565d2c3864b284d43255b6bf0d06030baa0d03816d76a27`.
Preferences match the exact pre-test XML. The actual release reader was inspected;
see `direct-links-2026-10-16/delivery/`. APK/source/checksum artifacts are
`artifacts/liber-0.11.0-*`. System free space remains 22 GiB; Darwin runtime/profile
state was not changed. Physical-phone/ARM behavior and live Tor archive recovery
remain unverified; prior fail-closed Tor transport checks remain unchanged.

A follow-up review of the main feature corrected one misleading instruction:
unfamiliar federated servers need manual URL entry with the platform selected;
sharing works when anti social can recognize the server. The wording-only rebuild
passed core/build/lint checks, followed by all four LinkOpeningTest cases and the
native Settings overview/navigation case in **5.597 seconds**. Preferences were
restored exactly. See `evidence/direct-links-federation-wording-build.log`,
`android/wording.log` and `android/wording-screens/links.png`. The refreshed preview
retains version 0.11.0/code 15; its installed signature/hash/stamp are verified in
`delivery/`, and the final checksum manifest includes the updated source documents.

### 0.10.1 connection quick controls and platform defaults

All **282 core cases** passed with zero failures/errors/skips. Debug and release
builds and Android instrumentation compilation passed. Evidence:
`evidence/connection-order-build.log`, `connection-order-instrumentation-build.log`
and `connection-order-2026-10-16/core-summary.json`.

The top connection badge opens a compact sheet over the current reader. Its Tor
switch uses the existing Repository/NetworkGateway policy; it does not select a
new search provider or weaken the independent Bing/DuckDuckGo Tor requirement.
The sheet displays content and search routing separately and pauses archive dwell
checks while open. Settings remains on its own icon. Connecting/ready UI fixtures
do not claim a live Tor exit measurement; transport is unchanged from prior checks.
The first native run found a real default half-expanded sheet clipping controls
below the viewport. Opening at the fully expanded anchor fixes that layout while
retaining scrolling for taller content. The failing log and actual screens are
retained under `android/scopes-first-partial-sheet-failure.log` and
`android/partial-sheet-failure-screens/` in the current evidence directory.

One shared approximate mainstream display policy orders new defaults, search and
subscription pickers, and Settings' Add a tab list. Selected default membership is
unchanged: Instagram, TikTok, X, Reddit. Explicit stored custom ordering persists;
Settings offers Sort by popularity. The current preview's ten enabled test tabs
were explicitly sorted and the first activated under the user's instruction that
this installation is test data. See `preview-default-order.json`.

Popularity is a practical navigation choice, not a comparable MAU ranking. Public
disclosures mix daily/weekly/monthly users, registered members and regional data;
small-network positions are editorial. Primary references and the chosen complete
order are retained in `evidence/platform-order-2026-10-16.json`. No runtime ranking
lookup or new network path was added.

All **16 focused Android cases** passed on the corrected build in 34.966 seconds:
five connection-sheet checks, three real Preferences checks, one Settings sort/
recreation check and the seven search-scope checks after picker reordering. They
verify that the badge keeps reader context, Settings remains separate, the quick
switch persists and enforces a not-ready Gateway pause, and disabling content Tor
retains Tor-only search for both Bing and DuckDuckGo. Actual native panel and
platform-order screens were inspected. Preferences are restored exactly between
test runs. Evidence: `connection-order-2026-10-16/android/scopes.log`, restoration
receipts and `screens/`.

The regression run passed 55 of 56 cases in 89.707 seconds; the remaining test
assumed the All platforms tab stayed onscreen when X was selected. With X later
in the new order, that tab legitimately scrolls offscreen. Explicitly scrolling
to All before clicking fixed the test, and its focused rerun passed in 2.805
seconds on the unchanged production APK. Thus all 56 regression cases, plus the
16 focused cases above, are verified on the final production build. Evidence:
`android/regression-first-offscreen-tab-failure.log`, `android/tab.log` and
`evidence/connection-order-tab-test-build.log`. Final lint: zero errors, 51 warnings.

Bifrost retained the quick-control/half-expanded-sheet finding as
`capture.revision.62849888f01d4e6c8162b7b69f6b0c91`; authored content and the storage/
index receipt are in `bifrost-record.json` and `bifrost-receipt.json` under this
feature's evidence directory. Receipts attest storage/indexing, not task execution.

The **0.10.1 / versionCode 14** preview is installed without DEBUGGABLE under
ordinary app UID 10116. Signature, installed APK hash and launcher stamp match:
`32a4cee2868885e8ac3be1d50585908fd0a588039473fc031c6b1a7eb3cfebc7`.
The actual release screen shows All, Facebook, Instagram and TikTok first; its
connection sheet was also inspected. See `connection-order-2026-10-16/delivery/`
for verification, signature, retained-preferences receipt and native screenshots.
APK/source/checksum artifacts are `artifacts/liber-0.10.1-*`. System free space
remains 22 GiB; Darwin runtime/profile state was not changed.

### 0.10.0 native search scopes

All **279 core cases** passed, with zero failures/errors/skips. New coverage
includes all 21 platform containers, native Reddit/X/Bluesky scope translation,
strict URL and hydrated hashtag membership, original JSON/RSS requests through
Gateway, source-health rejection of wrong-community data, scoped cache and routing
cooldown isolation, restart/backup identity, and literal mentions/hashtags inside
an explicitly selected scope. Eight feed-scanning cases exercise original source
continuations, empty pages, repeated cursors, cancellation and page/post/text limits.
Six native-to-feed fallback cases cover failed continuations, independent feed
cursors, cancellation/rate limits, retries, older matches beyond empty blocks and
successful empty native searches that never scan the feed. Empty-page tolerance
activates only after the bounded feed scanner takes over.
Evidence: `evidence/search-scopes-final-pagination-build.log` and
`evidence/search-scopes-2026-10-16/core-tests/`.

The first all-platform fixture reused one synthetic federation origin for multiple
protocols; the existing origin ownership guard correctly rejected it. Distinct
platform-specific fixture origins fixed that test without relaxing server identity.
Native UI checks found and fixed a real empty-search notice omission, keyboard
focus ownership across windows and dialog keyboard sizing. The larger search
heading also required existing stream tests to scroll to their asserted post.
Source research and primary documentation are retained in
`evidence/search-scopes-2026-10-16/research.md`.

The direct, real-Gateway scoped audit returned 25 r/Android posts for "battery"
in 5.306 seconds and 16 matching Telegram/durov posts in 0.824 seconds. Bluesky's
full-text endpoint first returned HTTP 403; its selected account feed fallback
then supplied six matching bsky.app posts in 13.969 seconds total, with a visible
limited-history notice. Every returned post passed scope/readability checks.
This is an availability sample, not a speed guarantee or a claim of complete
history. The diagnostic passing alone does not establish upstream availability.
Evidence: `android/live/search-scopes-live/measurements.json`, native screenshots
beside it, and `android/live-before-feed-fallback/` for the initial refusal.

All seven new native Android scope cases passed on the final pagination build
in 22.029 seconds. They cover context, platform reset, scope selection, validation,
cancel, profile/link mode, results/refresh and all-platform option sets. Actual
native scope-sheet, keyboard, empty-feed, Reddit and Bluesky screens were inspected.
Debug/release builds and lint passed with zero errors and 51 warnings. Exact
preference XML is restored after instrumentation. Evidence: `android/scopes.log`,
`android/scopes-preferences-restored.json` and `screens/search-scopes/`.
The 56 existing Android regression cases also passed on that final build in
87.870 seconds, covering the earlier 42 reading/privacy cases and all 14 archive
UI/storage cases. See `android/regression.log` and its exact preference-restoration
receipt. Combined with the seven scope cases, this is 63 native checks.

Bifrost durably retained the scope/fallback method as
`capture.revision.fb75634ff5564bb089873c68a3450573`; exact authored content and
receipt are in `evidence/search-scopes-2026-10-16/bifrost-record.json`; the subsequent
`bifrost-indexed-receipt.json` confirms durable, lexical and semantic visibility.
Storage and
index receipts do not attest execution or external-source availability.

The packaged **0.10.0 / versionCode 13** preview is installed as ordinary UID
10116 without DEBUGGABLE. Signature verification passed; installed APK and launcher
stamp match the preview SHA-256:
`2a1122f70b39d53f7d549f4caf8ab87e51768c443b598ac0cd2c64449c87d619`.
Local preferences match the exact pre-test XML. The actual native release reader
was inspected. See `evidence/search-scopes-2026-10-16/delivery/verified.json`,
`signature.txt`, `preferences-retained.json` and native screenshots. APK/source/
checksum packages are under `artifacts/liber-0.10.0-*`; system free space remains
22 GiB, and Darwin runtime/profile state was not changed.

Reddit community, X account/hashtag and Bluesky account/hashtag have native scoped
request paths. Other offered scopes search only the public feed/snapshot supplied
by their selected container, capped at 12 source pages, 400 inspected posts and
1 MiB of inspected body/title text. Every platform has an appropriate container
option; this does not assert that every external account/feed works. Facebook
groups, Misskey channels, LinkedIn personal-profile search and unsupported tag
readers are not presented as implemented capabilities. Actual network availability,
full historical coverage and physical-phone/ARM behavior remain source dependent
or unverified as appropriate.

### 0.9.0 paired archive history

The final core suite passed **238 cases**, zero failures/errors/skips. New cases
cover archive index/replay parsing, exact post identity, rejecting scripts/login
shells and unrelated posts, conservative content comparisons, strict replay URLs,
bounded courtesy budgets across restarts, cancellation, rate-limit propagation and
origin pinning on every Gateway surface. Evidence:
`evidence/archive-history-2026-10-16/core-summary.json` and `core-tests/`.

All **14 new Android cases** passed on the final APK in 12.440 seconds: seven native UI/visibility
checks and seven storage checks, including save while showing the old version,
late discovery after bookmarking, ordinary eviction, actual backup serialization,
delayed media promotion after removal/replacement, and rejection of a late archive
asset after saving a newer edition. The existing **42 Android
regressions** passed in 90.772 seconds. The first new run found three fixture
factory calls shadowed by a Kotlin `apply` receiver; explicitly qualifying those
calls fixed the fixtures without a production change. Actual native current and
archived/Undo screenshots were inspected. Evidence: `android/archive.log`,
`android/ordinary.log`, `android/screens/` and exact preference-restoration receipts
under `evidence/archive-history-2026-10-16/`.

Both live Tor methods passed together in **34.359 seconds**, including independent
Tor/direct exits and two bundled Tor restart cycles. Two earlier mixed-transport
runs failed resolving `check.torproject.org` on the emulator's direct connection;
their bundled Tor cases passed. A cold emulator start with the active host LAN
resolver restored guest DNS, after which both methods passed. This is a test
environment workaround, not a custom resolver in anti social. See `android/tor.log`,
`tor-*-direct-dns-failure.log`, `emulator-dns-restart.json` and
`emulator-dns-ping.txt`; exact preferences were restored after each run.

The opt-in real, filtered Gateway audit recovered Jack's first tweet from a
Wayback capture dated **2008-11-03**: “just setting up my twttr”. The complete
two-index/readable-replay operation took 4.836 seconds in direct host mode; see the
retained metadata in `evidence/archive-history-2026-10-16/earlier-reader-success.json`
and `evidence/archive-history-final-build.log`. The later availability run returned
no usable capture: Wayback's index stream reset after 11.857 seconds and Arquivo
again supplied JavaScript shells. This remained UNAVAILABLE, not a confirmed empty
history. Its full responses/result are in `reader-latest-availability/`.
This validates retrieval, not that the tweet was edited: the comparison text is
explicitly a diagnostic placeholder. Earlier Arquivo replay samples were genuine
JavaScript shells with no tweet body and were rejected. A lean Wayback index
request separately returned 205 bytes in 3.36 seconds; remote digest collapsing
was removed because it could require scanning many identical captures.
Evidence: `lean-index-followup/` and `archive-reader-research.txt` in that directory.

The native feature waits eight continuously visible seconds and pauses under
dialogs/backgrounding. Clock/Undo presentation and persistence use data-only
fixtures; they do not query archives for synthetic posts. Saved pairs include
both texts in backup and survive ordinary-post eviction. Offline saves protect
both versions and guard pending media commits with a unique intent token, so
removal or replacement cannot resurrect a stale save. Public archive coverage,
historic media availability and archive access over Tor remain source-dependent;
no claim is made that the oldest readable capture was the original edition or
that matching oldest/current text proves all intervening captures identical.

Debug/release builds and final lint passed: **zero errors, 51 warnings**. Evidence:
`evidence/archive-history-delivery-build.log`. One earlier incremental release
packaging worker failed without a detailed cause; the unchanged focused retry
passed, followed by the complete successful final build. No source availability
claim depends on build success.

The packaged **0.9.0 / versionCode 12** preview is installed under ordinary app
UID 10116, without DEBUGGABLE. Installed APK SHA-256 matches the packaged preview
and launcher stamp: `24be6e32ec33a0ec7c03c1124d8d8b6f894bd513641e2c9c5a0ae005b42c6b75`.
Signature verification passed; the actual native release feed was inspected.
Preferences match the original pre-test XML exactly. See `delivery/verified.json`,
`signature.txt`, `preferences-retained.json` and `release-native-screen.png`.
System filesystem free space remained 22 GiB; Darwin runtime/profile state was not
changed. APK/source/checksum packages are under `artifacts/liber-0.9.0-*`.

Bifrost's durable, lexical and semantic indexed lesson is
`capture.revision.28b538611b5842cfaf1092a519cefbad`. Exact authored content and receipt
are in `evidence/archive-history-2026-10-16/bifrost-record.json`. This receipt proves
storage/indexing, not execution attestation or upstream availability.

### 0.8.0 adaptive routing and expanded social data

The final core suite passed all 176 cases with zero failures, errors or skips.
Evidence: `evidence/adaptive-complete-core-tests.log` and
`evidence/adaptive-complete-core-tests-2026-10-16/summary.json`. Meaningful additions
cover capability/Tor isolation, target versus source cooldowns, hedge cancellation,
no backup request on a fast winner, empty-query isolation, unrelated selected-post
rejection (including retained partials), original API/RSS ordering, terminal-page
neutrality, cumulative probe limits, JSON/form credential stripping and dynamic
original-origin gating. Periodic upkeep now shares the automatic sounding budget;
full sweeps remain an explicit source-details action.

The final Android APK passed all **42 ordinary instrumentation cases** with no
skips or failures in 104.018 seconds. The selection includes the previous 37 cases
and five new native platform/selected-server cases. All 21 platforms retain native
search-input/feed navigation. Exact pre-test preference XML was restored and
compared after the run; no root is needed for app features. Evidence:
`evidence/platform-ui-2026-10-16/android-ordinary-42.log` and
`ordinary-methods.json` in the same directory.

After the final incoming-link startup readiness wait (including cancellation and
startup-failure handling), the selected-server inspection and real incoming
Facebook/comment diagnostic passed again: **2/2**, 10.408 seconds. Evidence:
`evidence/delivery-0.8/focused-incoming-and-server.log`; exact preference restoration
is recorded in `preferences-restored.json` alongside it. The core implementation
was unchanged after its 176-case passing run.

Both live Tor transport methods passed on the final core: independent mixed
Tor/direct exits in 9.939 seconds, and two bundled Tor start/exit cycles in
24.342 seconds. Evidence: `evidence/platform-ui-2026-10-16/android-live-tor-mixed.log`
and `android-live-tor-full.log`. These verify transport; the new-platform matrix
below used direct content mode and does not establish each source's Tor access.

A real incoming Facebook pfbid link loaded its selected post and ten supplied
replies in 3045 ms. Native Newest supplied 10 replies in 1603 ms and 20 after the
next block; Slop supplied 10 in 1836 ms and 20 after the next block, with no errors.
Evidence: `evidence/platform-ui-2026-10-16/screens-final/expanded-platforms/live-facebook-post.json`
and the actual native post/comment screenshots alongside it.

Actual Android native profile reads returned real posts on ten of the eleven
added platforms; Truth Social correctly reported its live HTTP 403 refusal.
No read timed out. These single direct-mode samples include rendering readiness,
not completed media downloads or physical-phone performance:

| Platform | Actual posts | Sample time |
| --- | ---: | ---: |
| Facebook | 3 | 1590 ms |
| Mastodon | 20 | 1666 ms |
| Lemmy | 20 | 978 ms |
| Telegram | 20 | 1275 ms |
| LinkedIn | 10 | 1766 ms |
| GETTR | 20 | 1265 ms |
| Gab | 20 | 1177 ms |
| Pixelfed | 10 | 914 ms |
| Misskey | 20 | 1876 ms |
| Snapchat | 7 | 722 ms |
| Truth Social | Unavailable (403) | 372 ms |

Results and actual native screens:
`evidence/platform-ui-2026-10-16/screens-final/live-outcomes.json` and
`live-*.png`. Facebook supplied usable posts with a partial-fields/attachments
notice; missing optional attachments are not claimed complete. Public source
coverage, linked media playback and availability remain account/instance dependent.
The README contains the capability matrix; adding a platform is not a claim that
all of its public/private surfaces or comments are supported.

Independent host Gateway/filter audits exercised eight API/channel/feed paths
(the seven public-platform readers plus same-server Mastodon RSS), six real
continuations and six selected-post paths. Evidence:
`evidence/public-social-adapters-final-2026-10-16/results.json`. Facebook's incoming
pfbid resolution, Newest and ranked comment pages, LinkedIn and Snapchat's distinct
single-post schemas, and actual CDN preview images were separately checked live:
`facebook-external-live-2026-10-16.log`, `facebook-ranked-live-2026-10-16.log` and
`document-individual-live-2026-10-16.log` under `evidence`.

The packaged, locally signed **0.8.0 / versionCode 11** preview is installed on
`emulator-5560` under ordinary application UID 10116. Its installed package flags
exclude DEBUGGABLE; signature and version were verified, and its actual native
Following screen was inspected after a cold launch. This release screenshot shows
existing cached feed data, not another fresh-source availability measurement.
Evidence: `evidence/delivery-0.8/installed-package.txt`, `release-badging.txt`,
`release-native-screen.png` and `evidence/adaptive-release-signature.txt`.
`artifacts/anti-social-preview.apk` and the laptop launcher's installation stamp point
to the verified preview; versioned APK/source hashes are in
`artifacts/liber-0.8.0-checksums.json`.

Release and debug APKs build. Current lint: **zero errors, 46 warnings**. Evidence:
`evidence/adaptive-delivery-build-and-lint.log`. Source hashes are in
`evidence/adaptive-source-manifest.json`. Existing Darwin runtime/profile state
was not changed; tools, builds, caches and evidence stay on RedtopStorage, with
22 GiB free on the system filesystem during final checks.

Bifrost contains durable, lexically and semantically indexed anti social records for
these verified public-source mechanisms and the adaptive routing method:
`capture.revision.1e194d6f5995450c8de70909982dadc6` and
`capture.revision.520f386bbc1d4d2d86c1d5c02ff85f26`. Exact authored records and service
receipts are retained locally as `evidence/bifrost-0.8-authored-records.json` and
`evidence/bifrost-0.8-captures.json`. Their original captures accurately mark later
native/release checks as pending; the completed results are documented here.
A memory receipt proves storage/indexing, not correctness or upstream availability.

### Earlier baselines

- Earlier 0.7.0: 102 core tests passed: imported Darwin routes, canonical identities and link cleanup,
  actual native filter matching and snapshot integrity, Tor fail-closed/remote DNS,
  filtering before connection and after redirects, cancellation, response limits,
  accountless adapters, RSS/Atom and XXE rejection, source failover, keyed download
  locks, target-specific page LRU, protected cache expiry, search input intent,
  index link extraction, original-index access gating and bounded search metadata,
  independent search-only Tor clients, protected-engine redirects, provider
  selection, DuckDuckGo challenge/link extraction, query-free HEAD preparation,
  indexed mirror recognition, recovery from cached search DNS failures, streaming
  native search, pagination ownership/forms, cancellation, deduplication, session
  bounds, and retaining undated content without inventing dates. The 0.5.0
  additions cover all-platform discovery, actual social pagination, scoped
  anonymous bookmarks, source identity, profile discovery, preview fallback,
  transient server-error retries, regional CDN restrictions, Unicode source
  context, native iFunny media precedence and bounded failure.
  The 0.7.0 additions cover linked identities, conservative cross-post matching,
  ambiguous reposts, undated retention, scoped identity-link discovery and HTTPS
  upgrades, backup compatibility, descendant-only discussions, Nitter conversation
  boundaries, ranking requests and rejecting root-only/related-only comment sources.
- 0.7.0: all 37 ordinary Android cases, both live Tor transport methods and the
  three-platform public discussion diagnostic passed in the final runs. The six
  new native cases also passed again after the startup health revision update.
  Actual release screens were inspected in light/dark appearance and at 130% text;
  the app and its bundled Tor process run under an ordinary Android application UID.
- 0.6.0 Settings validation: the final combined Android run passed 32/33 checks
  in 82.367 seconds, with no skipped cases. All five Settings cases, native
  navigation/cache/storage, provider persistence and mixed Tor/direct isolation
  passed. The bundled Tor restart test received `SOCKS: Connection refused` from
  its live endpoint path; its focused two-cycle retry passed in 24.932 seconds.
  No network code changed for this layout work. Lint: zero errors, 45 warnings.
- Earlier 0.5.0 Android run: all 30 selected checks passed in 222.114 seconds,
  with no skipped opt-in cases. This includes the full native/cache/storage/Tor
  suite, live next-page search, independent Tor/direct exits, all ten native
  search tabs, selected-preview ordering and failed-image recovery. The native
  nine-platform matrix records upstream availability separately from assertions;
  its passing method alone is not an availability claim. Every recorded search
  and selected-content operation in this final matrix had a null error.
- Earlier 0.5.0 candidate: all 29 checks passed in 185.410 seconds. A subsequent
  run encountered emulator DNS failures and was interrupted for a restart; it is
  retained as failed/incomplete evidence, not counted as a passing run.
- Earlier 0.4.0 Android run: all 26 checks passed in 63.1 seconds. This includes the full
  earlier native/cache/storage/Tor suite, three deterministic native search-feed
  cases, live post resolution and real second-page discovery, separate Tor/direct
  exits, provider persistence and search intent. No opt-in case was skipped in
  this selected run. Actual native feed and post/media screens were inspected.
- Earlier 0.3.3 Android combined run: all 26 checks passed in 85.218 seconds, including
  live search/profile/post reads, separate Tor/direct exits, Tor restart cycles,
  cache/offline behavior and native navigation. Two opt-in diagnostic methods
  recorded external failures as measurements rather than asserting availability.
  The final selector and 404/DNS backoff changes followed this combined run;
  all nine final focused checks passed in 32.213 seconds, including newly fetched
  prepared/warm searches and profile/post content. See the 0.3.3 section below.
- Earlier 0.3.2 Android checks: 22 of 23 cases passed together. The live X search case
  received an unrecognized DuckDuckGo response in that run. A subsequent focused
  five-case run passed in 18.138 seconds, including fresh search/profile/post,
  a second fresh warm search, separate Tor/direct exits, fail-closed search,
  asynchronous Tor status replies, and native provider-setting persistence.
  This establishes those working paths, not reliable external delivery on every
  request. See the search transport section below and retained failed-run evidence.
- Debug APK and instrumentation APK build; Android lint reports no errors.
  Nonfatal toolchain/dependency/style warnings remain.
- Earlier 0.3.0 baseline: all 16 Android tests passed together: `OK (16 tests)`, 499.99 seconds. Coverage
  includes actual scroll retention across separate tab/settings transitions, cached
  content during failed refresh, real saved-video first-frame rendering while the
  network is fail closed, image reuse, page/post expiry, SQL storage totals and
  followed-only queries, filter restoration, encrypted-backup rejection, native
  grid/post/viewer and board navigation, settings, Atom/XXE, and two Tor exit cycles.
  Evidence: `evidence/smoothness-full-device.log`. This combined suite preceded
  the final narrow HTML selection optimization; the final core suite and actual
  cold Android reader verify that optimization separately.
- Earlier focused eight-test Android run passed in 179.051 seconds, including native
  snapshot restore, cache/page expiry, decoded-image reuse and native UI navigation.
  That run preceded the strengthened scroll-transition and saved-video tests.
- Native host, ARM64, ARMv7 and x86_64 libraries build. Android filter libraries use
  16 KB ELF segment alignment; an actual Android 16 KB runtime was not tested.

## Measured performance and protection

Measurements below are diagnostic results, not physical-phone benchmarks.

| Check | Result | Evidence under LiberBuild/evidence |
| --- | --- | --- |
| Native blocklist audit on host | Analytics/ad requests blocked; public API, frontend and photo allowed. Warm p50 0.02–0.06 ms and p95 0.03–0.10 ms; 63.8 MB snapshot restore 322 ms. | `filter-audit.json` |
| Repeated tracked-link cleanup on host | p50 1.195 → 0.391 ms; p95 3.042 → 1.286 ms after compiled expression reuse. Cold initialization remains about 75–76 ms. | `link-audit-before.json`, `link-audit-after.json` |
| Retained public HTML extraction on host | Warm p50 67.449 → 39.816 ms with the same 20 post identities/dates after removing repeated whole-document selection. Cold initialization varied; no cold-speed claim. | `extraction-audit-before.json`, `extraction-audit-after.json` |
| Direct live Android frontend diagnostic | First response received in 3096 ms, full extraction in 14302 ms: 20 dated NASA posts. Second read 6325 ms total, also 20 dated posts. | `smoothness-live-stages.json` |

The direct live diagnostic identified a faulty budget: extraction/class loading could
consume the old 12-second source deadline after a successful 3-second network read.
HTTP text requests still have a 12-second direct network budget (45 seconds with
Tor); the source fetch plus parsing now gets 20 seconds direct / 55 seconds Tor.
Multi-step probes receive a separate bounded budget. Caller cancellation remains
cancellation; an exhausted provider is reported as unavailable.

Filtering remains enabled for every gateway request, including redirects, images,
media, RSS, probes and list updates. Settings expose actual checks and blocks in
the current session. The bundled seed contains 753390 raw entries from 32 lists;
the pinned engine accepts 733172 network rules. Native UI does not execute remote
scripts or render remote page cosmetics. Network-only compilation removes unused
cosmetic rules; it did not materially reduce the compiled snapshot size.

A compiled snapshot is restored rather than recompiling raw lists on each launch.
The default snapshot is read directly from its uncompressed APK asset. Valid
customized/updated snapshots remain in place; this preview has 755922 updated raw
entries, so it must not be reset to the packaged count. Saved snapshots use native
file reads and engine checksum/version verification. Legacy SHA metadata is
stream-verified once. A prior intermediate build restored saved protection in
24121 ms on this software emulator; this is not a verified phone startup figure.
Raw-list recovery remains fail closed if compiled snapshots are unusable.

Current changes also preserve cached native content during refresh/failure, retain
per-target scroll across tabs/settings, decode images off the main thread with a
24 MB decoded-image LRU, limit downloads/decoding, serialize only matching media
URLs, and coalesce routine cleanup. Page metadata references ordinary post rows
rather than duplicating bodies, so expired post bodies cannot reappear from a
page snapshot. SQL usage/projection queries avoid decoding the entire cache.
Following queries subscriptions before the result limit and excludes shared-link
history. Routine list updates and all-site probes start after 24 hours, while
idle, charging and unmetered. Backup encryption and file work run off the UI thread.

## Public source checks (earlier baseline)

These earlier checks precede 0.5.0; use the current Android matrix below for the
new reader behavior. They are point-in-time host checks of ranked alternate pools followed by
accountless original fallback. They do not guarantee every instance, profile or post.

| Platform | Observed result |
| --- | --- |
| X | 20 dated posts through nitter.tiekoetter.com; one public linked post through original syndication |
| Bluesky | 48 dated posts/feed entries through public original API |
| Reddit | 25 dated posts through red.artemislena.eu; 25 dated feed entries through safereddit.com. Original RSS follows denied JSON requests. |
| Tumblr | 17 dated posts through tb.opnxng.com; 20 dated feed entries through original fallback |
| Tieba | 30 threads through ti.opnxng.com; dates absent |
| Pinterest | Boards/profile through pinterest.bunk.im; Created: 15 pins; search: 18 pins. Dates absent. |
| Instagram | Two Kittygram instances supplied 12 tiles; timeline/Atom checks also supplied 20 dated posts. Later requests can supply undated tiles. |
| iFunny | 8 dated posts from public original JSON; surrounding scripts are parsed as data and never executed |
| TikTok | Timeline sources unavailable; video/thumbnail readers and oEmbed preview fallback exist, without a live playback claim |
| Threads | Public original profile metadata; no timeline posts supplied by current sources |

Challenges, login walls, empty frontends and missing dates are reported honestly.
Full comments/search/tag coverage, universal media playback and offline HLS remain
unverified or unsupported. Host availability results do not prove Android delivery.
Earlier direct Instagram/Pinterest emulator reads timed out; the final normal
reader checks below establish current native delivery. Synthetic fixtures independently
verify grid, board, image-viewer and saved-video interactions.
The native live diagnostic above verifies the Android extractor/gateway; normal
reader-path verification is recorded separately below when completed. The initial
20-second-budget candidate still returned metadata only on a cold normal read;
its trace showed a successful frontend response followed by extraction exceeding
the budget. The final extractor optimization removes repeated full-document
selection during nested-post checks. Its retained-response audit preserves all
20 dated post identities, with a new nested-card regression passing.

## Final normal-reader check

The final 0.3.0 candidate completed a cold-process normal ACTION_VIEW read of
`https://x.com/NASA`, using Repository → SourcePool → NetworkGateway → native UI.
It persisted 20 fresh posts from `nitter.tiekoetter.com`, replacing the earlier
metadata-only result. The live native timeline and actual post/photo content were
inspected. Evidence: `smoothness-final-reader.json`, `smoothness-final-startup.log`,
`smoothness-final-reader.png` and `smoothness-live-nasa-posts.png`.

The check observed persisted content after 77462 ms from launch on the software
emulator, including 23004 ms to restore the updated filter snapshot. This is still
a slow cold launch, and neither host benchmark timings nor the improvement in
content delivery establish phone performance. Cached pages remain available during
subsequent refreshes, and the Android scroll/cache tests verify navigation reuse.

The final warm normal reader also fetched 12 Instagram posts through
`kittygram.irelephant.net` in an observed 13250 ms, and 15 Pinterest pins plus two
collections through `pinterest.bunk.im` in 13182 ms. Actual Instagram photos and
Pinterest native content were inspected. These are point-in-time provider results,
not guarantees for other profiles or later reads. Evidence:
`smoothness-live-photo-readers.json`, `smoothness-live-instagram.png` and
`smoothness-live-pinterest.png`.

## Laptop preview and artifacts

Double-click **anti social Preview** on the desktop or run `python3 scripts/preview.py`.
It uses a separate persistent `LiberPreview` AVD on RedtopStorage. The launcher
disables preview animations when KVM is absent because continuous animated drawing
was taking CPU from loading workers. The app does not disable phone animations.
The preview can still be substantially slower than a physical Android device.

`artifacts/` points to RedtopStorage's LiberBuild/artifacts. `scripts/package.py`
packages the built version into a versioned debug APK, source zip and SHA-256
manifest, and updates `antisocial-current-debug.apk` for the preview launcher. Source
archives exclude local Codex configuration, generated native libraries, caches,
keys and emulator data. Debug signing is for installation/testing; there was no
production signing, store publication or external deployment.

Detailed evidence is under `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence`.
Darwin source/runtime/profile were preserved. Imported provenance records that a
live browser export was unavailable. Root filesystem free space remains above
15 GiB. Déjà Vu is engineering memory, not the app backend.

## Linked subscriptions and discussions (2026-10-16, 0.7.0)

Platform tabs now use 32 dp marks with accessible platform names; eight use bundled
Simple Icons vectors, with the local iFunny/Tieba symbols retained. The layers tab
opens an all-platform subscription feed. A person or organization can hold several
local accounts, each retaining its existing group and alternate RSS URL. New optional
Follow payload fields preserve Room schema 1 and backup schema 1 compatibility.

Discovery reads an actual public profile, then at most one explicitly linked website
(30-second profile budget; eight-second/1 MiB website limit). Only supported public
profile destinations become suggestions. The entered account starts selected; users
choose additional accounts before saving. Same usernames never imply identity.
Legacy HTTP links are upgraded to HTTPS before use; no insecure fetch is made.
There is no recursive crawl, script execution, remote subscription service or new
network stack. Every read uses NetworkGateway and its existing filter/Tor policy.

Linked accounts' own posts can share one card when they explicitly link to each
other, or have exact normalized substantial text (80+ characters) within 48 hours.
Short captions, common URLs, unrelated authors, replies, previews, old reposts and
ambiguous duplicates remain separate. Undated items remain visible and can match
only through an explicit cross-post link. Each group holds at most one post from a
platform. Similar wording and media-only cross-posts are not inferred matches.

Each card offers a native discussion sheet with per-platform tabs and All. Newest
sorts supplied replies by date, leaving undated replies last. Slop requests Nitter's
X Relevance order or Reddit's top order. Other readers explicitly disclose their
lack of a popularity ranking and retain supplied source order. All interleaves
those orders without a shared popularity score. Feeds remain chronological.
Nitter's Recency/Relevance parameters and separate replies/related containers were
checked against its [status renderer](https://github.com/zedeus/nitter/blob/master/src/views/status.nim)
and [status route](https://github.com/zedeus/nitter/blob/master/src/routes/status.nim).

Comment readers currently cover X through Nitter, Reddit, Bluesky and Tieba. Other
platforms show an unavailable explanation. Descendant relationships/conversation
containers are required: ancestors, recommendations and unrelated posts cannot
count as replies. Nitter supplies conversation membership but not every immediate
parent ID. Empty/limited reads do not claim zero comments or complete coverage.
Root-only or unrelated-only reads cannot win a comment-source race as healthy content. Reader health
revision 3 drops old negative capability results on upgrade, retaining healthy ones.

Opening a sheet reads one platform; All permits two concurrent reads. Closing and
changing order cancel work, including queued requests. Each order has a bounded
five-minute in-memory cache; persistent data uses ordinary cache expiry. A quick
thread stops at 200 entries or approximately 200 KB of text/metadata. Continuations
stay with their issuing source and stop on duplicate/exhausted cursors. Saved
records and explicit offline downloads retain their existing protection.

Final checks:

- 102 core checks passed with no failures/errors/skips. Eleven new cases cover
  identity evidence, matching exclusions, ambiguity, undated retention, legacy
  payloads, HTTPS upgrade, descendant selection, Nitter boundaries, ranking request
  parameters and root-only/related-only source rejection.
- All 37 selected ordinary Android checks passed in 61.054 seconds, including six
  subscription/discussion cases, five Settings cases, native reading, source settings
  preservation, storage/expiry, search feeds, all ten search tabs and fail-closed
  transport. The two live transport methods also passed in fresh processes:
  mixed Tor/direct exits in 7.748 seconds; two bundled Tor cycles in 24.020 seconds.
- Earlier combined 39-case attempts passed 37/39 and 38/39. One tab fixture assumed
  no restored Reddit destination; its setup now explicitly clears that destination.
  Live Tor endpoint reads intermittently received TLS closure/refusal. Those
  failures are retained in the earlier logs; later passing reads do not establish
  universal endpoint reliability.
- Debug, release, instrumentation builds and lint succeeded. Lint: zero errors,
  45 existing warnings. The final startup/health revision was rechecked with the six
  focused native cases; evidence is `subscriptions-delivery-native.log`.

Root is not an app requirement. The installed non-debuggable 0.7.0 release and its
Tor process both run under ordinary application UID 10116 (`u0_a116`); the test/ADB
shell uses UID 2000. The merged APK permission list has network state, Internet,
notifications, foreground service, wake lock and boot scheduling permissions plus
its own receiver permission. There is no root helper, su invocation, custom-ROM
dependency, privileged system permission or ADB dependency in the shipped app.
ADB is only used by development/test scripts. This was checked on the emulator;
an unrooted physical ARM phone was not available for device-specific validation.
Evidence: `subscriptions-release-package.txt`, `subscriptions-release-permissions.txt`
and `subscriptions-release-ui-qa.json`.

Live source checks passed in 22.323 seconds (direct content route, separately
protected search connection; these are point-in-time emulator reads, not phone or
all-traffic Tor benchmarks):

| Source | Replies supplied | Newest read | Slop read | Evidence |
| --- | ---: | ---: | ---: | --- |
| X / nitter.tiekoetter.com | 22 | 415 ms | 527 ms | Supplied order differs; Newest has a source continuation |
| Reddit / red.artemislena.eu | 106 | 2,772 ms | 2,271 ms | Supplied new/top orders differ |
| Bluesky public API | 199 | 1,190 ms | 789 ms | Source order unchanged; no popularity ranking claimed |

NASA identity discovery took 1,631 ms and found its Instagram profile through the
website linked from X. The old HTTP website field had initially prevented that
read; the HTTPS-only upgrade was verified against the real profile HTML. Bluesky's
own profile took 1,270 ms and supplied no additional linked accounts. These are
suggestions for user review, not ownership attestations. No subscriptions were
created by the live diagnostic. Tieba has parser/earlier source evidence but was
not live-rechecked in this pass; the other six platforms have no comment reader.

Evidence lives under the external build root: `subscriptions-unit-final.json`,
`subscriptions-deterministic-final-native.log`, separate `subscriptions-*-tor-final-native.log`,
`subscriptions-live-final-native.log`, `subscription-live-final/` (actual pages,
measurements and native comment screenshots), and `subscriptions-verified-screens/`
(fixture grouping, discovery choice, All/Newest/Slop and unavailable states).
The earlier ten-platform search/media matrix was not repeated for this UI change.

The final non-debuggable release was installed and compiled in the laptop preview.
Actual release All/subscription/add-account screens were inspected in light and
dark appearance, including 130% Android text and a live NASA→Instagram discovery
review. No candidate was subscribed by the review. Appearance was restored to Use
device theme, system night mode to no, and font scale to 1.0. Release screenshots
and UI trees are in `subscriptions-release-screens/`; development tools, builds,
device state and evidence remain on RedtopStorage. More than 21 GiB remains free
on the system filesystem.

## Settings redesign (2026-10-16, 0.6.0)

Settings now opens a category overview with a protection summary. Focused native
pages cover platform tabs, appearance, privacy/connection, search, feed updates,
storage, link opening and encrypted backups. The reader toolbar and bottom feed
navigation are hidden while editing settings. Advanced filter lists, recent
blocks and source tests remain available inside the privacy pages.

Rows show current values and open preset/custom-value dialogs rather than exposing
all inputs at once. Numeric values validate before saving. Platform tabs appear
in their actual order with movement in both directions; hiding the active tab
keeps the user in Settings and retains at least one tab. Back follows the actual
path through settings and source tools, then returns to the reader, library or
subscriptions. ViewModel navigation and per-page saved list state retain the page
and scroll position across activity recreation. Light/dark cards, larger touch
targets, full-row switch/radio semantics and concise descriptions were inspected
on actual native screens.

Preferences and readers use the same stored keys and policies. No production
network, filtering, Tor, retention or saved-download behavior was changed. Settings
read an explicit immutable value snapshot after changes so Compose updates rows
immediately. Asynchronous operations report their outcome locally; history
clearing has a cancelable confirmation, and backup passphrases remain masked.

Five focused Settings tests passed in 17.448 seconds. Coverage includes category
navigation, source-tool return paths, library return, tab ordering and active-tab
removal, the last-tab guard, activity recreation, preset/custom values and bounds,
automatic/manual refresh, dark mode, custom-list validation and backup controls.
The initial focused run caught a stale-control rendering issue; it was fixed.
An initial broader run passed 31/33 cases: two expiry fixtures assumed seven days
while new UI tests had left a temporary twelve-day preference behind. The UI test
cleanup now restores the exact preference snapshot, including removing newly
created keys, and asserts equality afterward. Expiry fixtures now age their data
past the configured retention instead of assuming a default. Those failed logs
are retained; they are not counted as passes. Pending test-only preference changes
were cleared before final validation.

Build evidence: `evidence/settings-release-build.log` and the final test APK build
logs. UI evidence: `evidence/settings-final-screens/`; the final combined run and
screens are recorded separately in `settings-verified-native.log` and
`settings-verified-screens/`. Earlier dialog screenshots captured part of an
Android window fade; final dialog evidence waits for that transition to finish.
The 0.5.0 live source matrix below was not rerun for this layout change.

The final combined run passed 32/33 cases in 82.367 seconds, including all five
Settings cases, native reader/storage/cache tests and mixed search-Tor/direct
isolation. The single live two-cycle Tor check received `SOCKS: Connection
refused`; the exact upstream cause was not established. A focused retry of that
same two-cycle check passed in 24.932 seconds. Both logs are retained as outcomes,
not represented as an entirely passing combined run. The 91 unchanged core tests
pass, debug/release/test APKs build, and lint reports zero errors / 45 warnings.
Before/after snapshots verify the user-facing preferences were unchanged across
final validation, and each Settings test asserts exact snapshot restoration.
Evidence: `settings-verified-native.log`, `settings-tor-focused-native.log`,
`settings-preferences-verified.json` and `settings-verified-screens/`.

A locally signed, non-debuggable 0.6.0 preview (code 9) is installed in the laptop
AVD with its data retained. The final layout check uses larger Android text and
restores the original emulator font setting. Version/hash proof and actual release
screens are under `settings-release-installed.json`, `settings-release-layout.json`
and `settings-release-*.png`. This remains a local preview, without store release.

## Platform reliability pass (2026-10-16, 0.5.0)

All ten tabs now use the native search feed. Darwin remains the routing donor;
the expanded per-platform readers, compatibility fixes and selected commercial
index are anti social additions. No donor runtime/profile or imported catalog was
changed. Alternates still precede permitted accountless originals. Every request,
including media and pagination, uses NetworkGateway and the loaded filters.
Selected commercial indexes require Tor and fail closed; content follows the
ordinary connection setting. Settings, subscriptions, bookmarks and explicit
offline downloads were preserved.

The initial Android audit found missing Reddit/Tumblr/Tieba continuation links,
repeated Pinterest pages, denied Bluesky post search, metadata-only Threads,
unrecognized Tieba thread bodies and four tabs without keyword discovery. Current
fixes include:

- Native Reddit JSON/RSS and HTML, Tumblr and Tieba next links; all continuations
  stay with their issuing source. Repeated pages and two consecutive unreadable
  blocks stop automatic search fetching. Missing dates stay unknown.
- Pinless's actual anonymous `bookmark` cursor. Only that bounded cookie is
  explicitly captured/replayed, bound to the same endpoint and selected target.
  General cookies, account/session cookies and authorization remain discarded.
  Initial same-host canonical redirects carry no cursor. Created pins are tried
  before empty saved-board navigation; navigation alone is not a healthy feed.
- Bluesky native actor discovery when post search is denied, DID/handle post
  identity preservation, and selected-index post discovery. Instagram search
  retains actual accounts and attempts public topic posts. The retained topic
  response had media tiles without post permalinks; these are rejected instead
  of becoming invented social posts. Account clicks can open real profile grids.
- Threads uses its current `.com` origin and explicitly requests HTML; embedded
  public post/reply data is parsed without executing scripts. TikTok/Threads
  profiles without a supplied timeline can discover up to six readable public
  posts belonging to that author. Profile metadata appears while this bounded
  discovery runs; coverage is explicitly incomplete.
- Tieba thread bodies/images, supplied Chinese timestamps and individual reply
  identities. Listing dates without years remain unknown. Preview metadata must
  identify the requested platform/post, is labeled **Public preview**, and no
  longer suppresses fuller alternate/original post reads.
- An unproven post/media alternate pool gets four seconds direct / eight seconds
  Tor before permitted original fallback. Recently healthy sources retain normal
  budgets. Disabling original fallback preserves the full alternate budget.
- One same-query, same-provider form retry for DuckDuckGo's HTTP-200 server-error
  page. Challenges, refusals and continuation failures are not retried this way;
  no query switches engine or escapes required Tor. Source inspection and
  settings reflect the provider's all-platform scope.
- Unicode source URLs stay in local request tags instead of invalid HTTP headers;
  the Media3 compatibility header uses an encoded URL and is stripped before the
  request leaves the gateway. iFunny webpage URLs in ImageObject metadata no
  longer displace actual native media records. Failed images have an explicit
  retry control; a deterministic Android case verifies recovery without reopening
  the post.
- Original-access restrictions include regional TikTok, Meta, iFunny and Tieba media
  domains. An upgraded reader discards old negative health results once, retaining
  known healthy sources and all personal settings/saved data.

The final native matrix below used fresh normal ViewModel/Repository reads on the
accelerated Android emulator. Search times include populating the first native
block, not merely obtaining an index response. Search-only Tor was active; content
used the ordinary direct connection. These are observed examples, not guarantees
for every query, post, account or Tor exit.

| Platform / query | Native search result | Selected content / profile |
| --- | --- | --- |
| X / elon | 12 posts, 2 accounts; actual next-page response | Full post reader covered; zero unreadable destinations in this run |
| Bluesky / space | 5 dated posts, 1 account, 11.871 s | Exact selected original thread plus replies: 89 dated entries; zero previews |
| Reddit / android | 25 dated posts, 5.167 s | Full selected dated post through safereddit.com; zero previews |
| Tumblr / space | 15 dated posts, 2.977 s | Actual selected dated post |
| TikTok / nasa | 5 dated posts, 1 account, 18.814 s | Actual selected post; public NASA profile discovered 6 dated posts in 23.219 s |
| Instagram / space | 20 accounts, 2.457 s; honest posts-unavailable notice | Selected account supplied 12 undated profile tiles |
| Pinterest / space | 5 undated pins through index fallback, 31.525 s | Actual selected pin/image in a page with 13 entries |
| iFunny / space | 6 dated posts, 5.226 s | Actual selected post and visibly rendered image |
| Tieba / 安卓 | 6 dated posts, 6.830 s | Actual selected thread; an earlier distinct thread supplied 8 dated entries |
| Threads / space | 6 dated posts, 7.859 s | Selected thread: 18 dated entries; NASA profile: 5 dated posts |

Pinterest also returned 16 native search pins in 2.247 seconds in the earlier
run; the final run needed slower index fallback. This is source variation, not
a uniform speed improvement. X's final first usable post took 16.049 seconds.

Earlier normal-reader audits also verified real next pages: Bluesky 48→50 author
posts, Reddit 25→25 reader/search posts, Tumblr 17→17 reader and 15→16 search posts,
Instagram 12→12 profile tiles, Pinterest 17→25 distinct search pins, and Tieba
30→30 community threads. Those are measured pages, not exhaustive capability
coverage. The native matrix and detail screenshots were inspected; content checks
do not imply that every remote image loaded or that every video plays. Native
saved-video rendering is independently asserted by the Android suite. Physical
ARM phones, arbitrary private/deleted posts and universal live playback remain
unverified or unavailable.

Evidence under LiberBuild: `evidence/platforms-baseline/`,
`evidence/platforms-shared-fixes/`, `evidence/platforms-accounts/`,
`evidence/platform-explicit/`, `evidence/native-platforms/`,
`evidence/platform-native-device-tests.log`, `evidence/platform-x-search-live.json`,
and `evidence/platform-final-verified-build.log`. Final evidence is
`evidence/platform-complete-build.log`, `evidence/platform-complete-tests-build.log`,
`evidence/platform-release-validation-native.log`,
`evidence/platform-release-validation-trace.log`,
`evidence/platform-release-x-search-live.json` and
`evidence/release-native-platforms/`. The diagnostic methods record
failures as outcomes rather than asserting all upstreams work. Earlier invalid
diagnostic-runner, host DNS, topic-response rejection and transient release-package
failures are retained as failed evidence; the narrow release retry and subsequent
complete build succeeded. They are not counted as passing source reads.

A later intermediate run could not resolve `check.torproject.org` and several
media hosts in the emulator while the laptop resolved them immediately. Its
Android log retains the DNS error; a ten-second device DNS probe also stalled.
Restarting only the anti social AVD restored DNS (including an immediate successful
Tor endpoint probe), and the final 30-case run then passed. The underlying DNS
proxy failure was not established. No app data or host resolver setting was reset.
Evidence: `platform-final-native-device-tests.log`,
`platform-preview-dns-failure.txt`, and `platform-preview-restart.log`.

The separate final media diagnostic completed in 15.795 seconds. Actual Android
image download/cache/decode succeeded for one recent item from nine platforms:
X, Bluesky, Tumblr, TikTok, Instagram, Pinterest, iFunny, Tieba and Threads. The
selected Reddit preview proxy timed out after 15.384 seconds; this remains a
source-dependent media failure. Some successful images reused ordinary cached
bytes, so these are decoder/read-path results rather than ten fresh network
benchmarks. Evidence: `platform-media-verified-device.log` and
`platform-media-verified.json`. Earlier Unicode-header, webpage-as-image and DNS
failures remain in `platform-media.json` and its diagnostic logs.

The non-debuggable, locally signed 0.5.0 preview (code 8) was installed over the
existing app, compiled with Android's `speed` mode, and its installed APK SHA-256
matched the built release exactly. Installation proof is
`evidence/platform-release-installed.json`; source, debug/preview APKs and
checksums are packaged under `artifacts/`. The desktop launcher retains its
verified installed-APK stamp. This is local testing, without store publication.

Primary protocol references: [Pinless source](https://github.com/bunk-im/pinless),
[Meta's Threads.com migration](https://about.fb.com/news/2025/04/new-features-threads-web-experience/),
[Bluesky public endpoint reference](https://endpoints.bsky.app/), and
[TikTok oEmbed documentation](https://developers.tiktok.com/docs/en/embed-videos).
oEmbed thumbnails/player metadata alone do not establish native video playback.

## Previously diagnosed platform issues

- Android packaging expands `.gz` assets and changes their names. The compressed
  raw seed uses `.seed`; the native compiled snapshot uses uncompressed `.compiled`.
- Android Harmony rejects a desktop JAXP hardening option. Shared RSS/Atom parsing
  uses jsoup XML with explicit declaration rejection and native Atom/XXE coverage.
- Tor control readiness is published after authentication/listener discovery.
  Every enable cycle uses a fresh private process; disable terminates it. This
  avoids reusing destroyed native mutexes across cycles. TLS connection cleanup
  runs off the main thread after an immediate gateway policy switch.
- Moving to Compose BOM 2026.09.00 resolved the observed older-runtime native image
  transition failure in tests. The exact internal failure cause was not established.


## Emulator tuning (2026-10-15)

The host is a ThinkPad P52 / Intel i7-8750H. Its kernel explicitly reports
`VMX (outside TXT) disabled by BIOS`; `/dev/kvm` is absent, and the installed SDK
acceleration check fails. The KVM kernel module is already installed. Intel UHD
630 host graphics acceleration is working. Hardware CPU acceleration requires
enabling Intel Virtualization Technology in the F1 firmware menu. The checked-in
helper loads the existing driver after that change; no firmware or host driver
settings were changed by this tuning. Hardware acceleration remains unverified.

The preview launcher now uses an actual SDK acceleration check and selects four
virtual CPUs / 3 GB RAM when it succeeds. Software mode retains one CPU / 2 GB
RAM, with 576 × 1024 at 256 dpi and 30 Hz: the same 360 × 640 dp interface and
36% fewer pixels than before. The device reports that resolution, density and
30 Hz display mode. Host GPU rendering and disabled preview animations remain.

A non-debuggable release-variant preview APK builds and installs with the same
local testing key and app data. This is not production signing or publication.
Android reports `speed-profile` compiled code for it. The debug APK was downgraded
to `quicken` even after an explicit `speed` compile; debug builds restrict this
optimization. The launcher records APK SHA-256 and the actual installed base APK
path, skipping reinstalls when both still match so compiled code is retained.

Software emulation remains unsuitable as a phone performance benchmark. One
valid native grid scroll sample rendered 68 frames, with 64 janky frames, p50
48 ms and p95 450 ms. Earlier zero-frame samples were discarded. Android System
UI also temporarily became unresponsive during initial installation and recovered
after waiting. These limitations are reported rather than treated as an app speed
measurement. A release preview activity displayed in 9698 ms with protection
restored in 15364 ms in that run; these are observations, not controlled phone
benchmarks.

An isolated, paused two-CPU probe warned that this x86 guest is not converted to
multi-threaded TCG. That configuration was not enabled for the preview. A saved
snapshot took 3.14 seconds to write, but restore failed with a RAM length mismatch
and the emulator fell back to a cold boot. Software snapshots are therefore off
by default; KVM Quick Boot is prepared but cannot be verified until VT-x is enabled.

The full virtual device was backed up before changes. Evidence:
`emulator-host-diagnostic.json`, `emulator-backup.txt`, `emulator-precompile.log`,
`emulator-aot-startup.log`, `emulator-preview-signature.log`,
`emulator-tuned-display.txt`, `emulator-tuned-scroll-frames.txt`,
`emulator-mttcg-capability.log`, `emulator-snapshot-save.log`,
`emulator-resume-launcher.log` and `laptop-preview.log`.

## Hardware acceleration verified (2026-10-16)

After the firmware change and reboot, `/dev/kvm` is accessible and the SDK reports
`KVM (version 12) is installed and usable`. The running emulator has a KVM VM and
four KVM vCPU file descriptors; graphics use Mesa Intel UHD 630. Android reports
720 × 1280, 320 dpi and 60 Hz, with 3 GB configured RAM. Normal Android animation
scales are restored when the launcher selects acceleration. RedtopStorage needed
mounting after reboot; the launcher now reports unavailable build storage cleanly
instead of creating directories there or throwing a missing-executable traceback.

Observed timings on this laptop, with the existing non-debuggable preview APK and
app data retained:

| Check | Software observation (2026-10-15) | KVM observation (2026-10-16) |
| --- | --- | --- |
| Cold emulator start through launch command | 188.6 s | 60.9 s on first start after host reboot |
| Cold app process, first activity displayed | 9698 ms | 294 ms after Android and host file caches were warm |
| Existing protection snapshot restoration | 15364 ms | 1857 ms during first boot; 284 ms on the later app launch |
| Native Instagram grid scroll | 68 frames; 94.12% janky; p50 48 ms; p95 450 ms | 222 frames; 23.42% janky; p50 15 ms; p95 18 ms |

The scroll sample used four 800 ms swipes inside the actual cached NASA profile
grid, with public photos displayed and no dialog covering the app. Display size
and refresh rate differ between the profiles. These are emulator observations,
not controlled physical-phone benchmarks; first-boot work and file-cache warmth
also affect startup. No app slowdown is inferred from remote source fetch times.

The old software snapshot was rejected under KVM. A new KVM snapshot saved in
8.335 s and successfully restored in 1804 ms. The complete preview launcher then
issued the launch command in 4.6 s. Further visual inspection found a black host
window despite a correct Android screenshot; resizing the host window restored
display. More importantly, repeated normal exits logged `Snapshots have been
disabled by the user, save request is ignored` despite launching with a named
snapshot. The complete automatic save/restore cycle is therefore not reliable.
Snapshots are now disabled by default in both profiles so an old named snapshot
cannot roll back preview app data. Experimental opt-in remains explicit and
warns about that risk. A second KVM normal boot with warm host file caches reached
the launch command in 12.7 s. Hardware acceleration remains fully enabled.

All 42 core tests were rerun and passed. Three focused Android instrumentation
tests passed in 4.115 s against the installed preview: bundled native protection,
cached grid retention through failed refresh/tab/settings navigation, and native
Instagram grid/post/image viewing while offline. The complete prior Android suite
was not rerun for this host-only change. Actual native screens were inspected.
System filesystem free space remains 22 GiB; tooling and evidence stay on the
external build drive. No Darwin runtime or profile state was changed.

Evidence: `kvm-host-verification.json`, `kvm-cold-boot.log`,
`kvm-app-startup.txt`, `kvm-native-scroll-frames.txt`, `kvm-instrumentation.log`,
`kvm-native-instagram.png`, `kvm-device-display.txt`, `kvm-snapshot-save.txt`,
`kvm-resume.log`, `kvm-snapshot-exit-check.log` and `kvm-preview-ready.png` under
the build root's `evidence`.

## X search repair (2026-10-16, 0.3.1)

The reported `elon` read was an exact profile lookup: the magnifying glass opened
the old **Profile or link** mode by default. Words now search by default, while
`@username`, `#tag`, a full link or explicit profile mode retain exact-destination
behavior. Keyboard search submission uses the same action as the dialog button.

There was also a real source capability failure. nitter.tiekoetter.com returned
**Search disabled** despite continuing to supply profile/post content. Other
bundled X sources returned DNS failures, 403, 404 or 451 responses. Disabled-feature
pages now report `UNSUPPORTED`, and **Inspect sources** opens the failed capability
instead of showing timeline health for a failed search. Original-source outcomes
are recorded and visible alongside alternate-source outcomes.

X discovery also uses two LibreY routes imported from Darwin's reviewed search
pool, separately from social mirror recognition. An initial native search returned
eight real destination links, but repeat reads exposed an upstream search failure:
LibreY reported that it could not fall back, while a separate DuckDuckGo diagnosis
returned a human challenge. A single successful query is therefore not treated as
proof of reliable public keyword search. With **Public original fallback** enabled,
the reader can now try Bing's public RSS search index after alternate routes fail.
That additional provider is explicitly identified as a anti social addition in donor
provenance. Turning the original-access setting off blocks it before DNS; all index
requests use NetworkGateway and its current Tor policy and protection.

Index results are destination cards and snippets, never fabricated social posts.
Only recognized links for the selected platform are accepted. Unsupported subpages,
navigation, other domains and executable destinations are discarded. RSS crawl
dates are not presented as social post dates; XML declarations/entities are
rejected. Snippets and titles are bounded and count toward the recent-page memory
budget. Cached results are labeled. Search coverage may be incomplete or older,
and external index availability is still source dependent. Live index delivery over
Tor was not part of this direct-connection check.

The active platform tab now recreates its scroll state when switching platforms,
so opening X from another platform brings its selected tab into view. Post/thread/
media headings use readable labels instead of displaying long routing paths.

Final checks on the installed non-debuggable 0.3.1 preview:

- **49 core tests pass**, including seven search-specific regressions.
- **Seven focused Android tests pass together in 23.424 seconds**: search defaults
  and keyboard submission, exact usernames/profile mode, failed-search inspection,
  a live search/profile/post flow, native protection restoration, cached grid
  retention, tabs/settings/library navigation, and SQL storage/followed-feed
  behavior. Several assertions share a case. An earlier six-case run also passed.
- The live flow required newly fetched index and post provenance; cached results
  were explicitly excluded as evidence of fresh network delivery. `elon` fell back
  to Bing, which supplied one matching X profile link. Opening it fetched **21
  dated posts** from nitter.tiekoetter.com. Opening a real post fetched a page with
  **34 posts/replies**, including the exact selected post identity and body. Video
  playback was not exercised by this read check.
- The full earlier Android suite was not rerun for this change. Actual native
  search, profile and post screenshots were inspected. Settings, subscriptions,
  saved data and Darwin runtime/profile state were preserved.

Evidence under the external build root: `evidence/x-search-final-ui-device-tests.log`,
`evidence/x-search-final-ui-live-trace.log`, and
`evidence/x-search-verified-native/{search-results,opened-profile,opened-post}.png`.
Earlier failed/repeat reads remain in `x-search-live-post-test.log`,
`x-search-final-device-tests.log` and `search-repeat/`; they are not counted as
passing validation. The opt-in `:core:networkAudit` task uses the same guarded
gateway and bundled native protection for future public-source diagnosis.


## Search provider and transport work (2026-10-16, 0.3.2)

The selected X index is now explicit: DuckDuckGo HTML (default), Bing RSS, or
Darwin's selected LibreY search routes. Social alternate sources precede the
index. Bing/DuckDuckGo require original fallback permission and always use Tor,
including probes and redirects. A selected engine failure does not send the
query to another engine or a direct connection. Darwin's selected LibreY routes
follow the ordinary all-traffic setting. Other platform search readers retain
their existing native routes. Engine results remain destination cards, without
invented social posts/dates; DuckDuckGo wrapper URLs are decoded locally and
challenge responses are reported. Google was measured diagnostically, not added
as a working provider.

NetworkGateway maintains separate Tor and direct clients. Updating Tor readiness
does not cancel or switch ordinary direct media/feed requests. Required engine
hosts are guarded before DNS; a direct redirect to a protected engine is stopped.
The existing all-traffic Tor option still overrides content connections. Tor is
kept ready when a commercial X index and original fallback are selected. Settings,
backup validation and the native SEARCH TOR badge distinguish that scope from
all-traffic Tor. Reading a result follows the content connection setting. Tor
hides the client's IP from the engine; the engine still receives query text, and
query content can identify a person. No claim of complete unlinkability is made.

A benchmark exposed an independent Tor status bug. The service's main-thread
Messenger handler captured an Android Message in a coroutine; Android recycled
it before IO reply construction, losing the generation and replyTo. Native Tor
ran, but the app could remain at 0% / unresponsive and time out at 180 seconds.
Capture epoch and recipient before launching IO. The Android regression checks
20 distinct asynchronous status request identities. A short deferred bind also
avoids immediately rebinding the private process handling its previous stop.
Fresh selected-provider metadata prevents a previously selected index's in-memory
search page from suppressing a new provider request. Recent failed frontend
searches cool down for one minute, then become eligible again; explicit
unsupported capabilities retain their existing one-hour skip. Index discovery
gets a bounded 4-second direct / 8-second Tor native-alternate attempt first.

Measurements on the accelerated laptop emulator, with existing Tor disk state:

| Check | Observed result |
| --- | --- |
| New Tor process through ready status | 5.517 seconds in both benchmark runs; first-install bootstrap was not measured |
| DuckDuckGo through connected Tor, two queries in two runs | 1.496–2.179 seconds including extraction; 9–10 recognized X links |
| Bing RSS through connected Tor | 0.775–1.739 seconds; 0–1 supported X links; responses included unrelated results despite the site query |
| Google through connected Tor | 0.715–1.327 seconds; no extractable X links; captured HTML asks for a JavaScript retry |
| First fresh native X search/profile/post flow | Search 8.224 / 8.788 seconds; DuckDuckGo 8 / 9 links, Nitter profile 21 dated posts, selected full post identity matched |
| Second fresh native search, connected Tor | 1.833 seconds, nine NASA destination links; provenance newly fetched and not cached |
| Mixed connection exit check | Explicit protected request IsTor=true; ordinary default request IsTor=false |

The raw engine benchmark does not include native fallback and UI time. The native
warm timing does. There were no HTTP-200-only successes: usable destinations or
actual profile/post content were required. One intervening combined Android run
received no recognizable DuckDuckGo content; the response body was not captured
in that run, so its precise external cause is unresolved. Later benchmark and
native reads succeeded. These measurements are point-in-time observations, not
speed or availability guarantees. Phone performance, arbitrary query coverage,
Tor exit-dependent blocking and live video remain unverified. Explicit diagnostic
response capture is disabled by default and enabled only by the opt-in live test.

Final host checks: 55 core tests pass; debug/release/instrumentation APK builds
and Android lint complete without errors. Android combined run: 22/23 passed
(the live upstream response failure above); final focused run: 5/5 passed.
Actual native settings, index results, warm results, profile and post screens were
inspected. Deterministic privacy checks, two full-Tor restart/exit cycles, cache,
native navigation, protected offline playback and storage checks passed in the
combined run. The preview is locally signed for testing, without store release.

Evidence under the external build root:
`evidence/search-tor-benchmark-fixed.json`, `evidence/search-tor-repeat/`,
`evidence/search-privacy-android-final-tests.log`,
`evidence/search-privacy-live-diagnostic-tests.log`,
`evidence/search-privacy-native-diagnostic-trace.log`,
`evidence/search-privacy-{search-provider-settings,search-results,warm-search-results,opened-profile,opened-post}.png`,
and `evidence/search-privacy-{core-tests,final-build,lint}.log`.
Earlier failed status diagnostics and the invalid test-class attempt remain in
the evidence directory and are not counted as passes.


## Search connection and extraction work (2026-10-16, 0.3.3)

The app now prepares the selected commercial index connection while browsing or
opening search. A query-free HEAD to the provider's favicon uses NetworkGateway,
the loaded filters and required Tor route. It reads no response body, carries no
entered text, and does not mark the provider healthy. Preparation waits for Tor
in the background; submitting a search cancels unfinished preparation. Recent
successful preparation is reused for up to a minute only for the same provider
and gateway client. A cold immediate submission still pays Tor/connection costs.
This moves connection work before submission rather than eliminating its cost.

Known X SEARCH refusals (403/404/451) and DNS failures now cool down for ten minutes;
other recent search failures retain their one-minute interval. Unsupported
capabilities retain their one-hour skip. Explicit source testing can retry and
recover immediately. This policy does not suppress timeline/post reading, and
eligible alternate sources still precede accountless original fallback.
Darwin mirror recognition indexes roots by hostname, preserving path/port/scheme
checks and donor order. Canonical post patterns are compiled once. DuckDuckGo
extraction reads result fields directly, skips ads and stops after 30 distinct
supported destinations; whole-document error text is checked only when no results
were extracted. Explicit challenge forms always fail, even alongside result cards.
Quoted server-error words in an ordinary result no longer reject valid content.

These measurements used actual Android NetworkGateway/Tor and the native reader:

| Check | Observed result |
| --- | --- |
| Prototype connection preparation, two successful query pairs | Unprepared 1.910 / 3.037 seconds with one new connection; prepared 1.135 / 1.066 seconds with zero new connections. Preparation itself cost 1.841 / 2.283 seconds. Its query-free `/html/` HEAD returned 400; this proved reusable connection behavior, not working search content. Production uses `/favicon.ico`, observed returning 200. |
| Full native combined run, intermediate 0.3.3 | First search 7.869 seconds, 10 links; warm fresh search 1.423 seconds, nine links. Prepared search 5.266 seconds with zero new connections, including alternate-source retry time. |
| Final native prepared search | 2.565 seconds from submission through usable native results, seven NASA links, zero new engine connections; freshly fetched provenance, no cached success. |
| Final native search/profile/post and second search | First search 6.821 seconds, nine links; profile 21 dated posts; selected post matched within 31 posts/replies; warm fresh search 1.690 seconds, nine links. |
| Final connection/privacy check | Protected request used a Tor exit; default content used the direct exit; unavailable Tor failed closed. |

These are small, point-in-time observations, not a controlled speedup percentage
or availability guarantee. Preparation and avoiding repeated known refusals remove
specific waits; the upstream service and Tor exit still dominate variation. A
later four-request calibration recorded one valid search, two HTTP-200 server
error bodies and one HTTP 403. Those failures were retained as failures, never
counted as fast working content. Sharing an engine connection can associate
queries at that engine; Tor does not hide the query itself.

Smaller pages were tested rather than assumed faster. Of four paired HTML/Lite
GET queries, two Lite responses supplied real destinations and two were server
errors; one HTML response was also a server error. Separate form-submission
measurements returned ten raw result anchors for each of four responses. Lite
used about 20% fewer response bytes but was slower in both pairs: 2.014 versus
1.739 seconds and 1.059 versus 0.959 seconds. These raw anchor counts do not prove
all links are supported X destinations. Production retains HTML GET. It fetches
HTML without page scripts/images/styles; servers do not offer CSS-selector range
requests, so local field extraction does not by itself reduce response bytes.
The form-submission comparison is diagnostic only, not a new production POST path.

The retained-fixture Android parsing benchmark used five warmups and 30 measured
iterations per usable response. Baseline p50 was 11.976 / 10.505 / 9.560 ms
(Elon/NASA/space); final p50 was 14.423 / 12.630 / 11.612 ms. The first intermediate
run was also slower than baseline. No CPU parsing speed improvement is established;
the simpler bounded extraction and route lookup changes are not presented as a
measured speed win. Link counts stayed 8 / 9 / 7. The fourth retained fixture was
an error page, now reported explicitly as a server error instead of generic
unrecognized content.

Final validation: 59 core tests; debug/release/instrumentation APK builds; lint
with zero errors and 45 warnings. The combined Android run passed 26 cases in
85.218 seconds before the final selector and 404/DNS backoff changes. The final
focused nine cases passed in 32.213 seconds: prepared native search, word-search
and exact-profile modes, fresh search/profile/post/warm-search flow, asynchronous
Tor status identity, provider persistence/scope, mixed exits, fail-closed routing,
and retained-response parsing. Diagnostic methods record upstream failures and
do not assert universal availability. Actual native result and post screens were
inspected. Physical-phone speed, ARM runtime behavior, arbitrary search coverage
and dependable delivery across Tor exits remain unverified.

After installation, the non-debuggable preview's normal NASA search received a
DuckDuckGo server error. It preserved the previous results, labeled them as saved,
and displayed the failure; this is not counted as a fresh successful read. Evidence:
`search-optimization-preview-ready.png` and `search-optimization-preview-logcat.txt`.
The installed APK hash matches the packaged preview, version 0.3.3/code 6; Android
reports no debuggable flag and one-time code preparation succeeded.

Evidence under LiberBuild/evidence:
`search-lite-before/`, `search-opt-duckduckgo-post/`,
`search-opt-search-preparation.json`,
`search-opt-search-parsing-{before,after}.json`,
`search-opt-final-search-parsing-final.json`,
`search-optimization-{device-tests,final-device-tests,final-build}.log`,
`search-optimization-{native-trace,final-native-trace}.log`,
`search-opt-final-{prepared-search-results,search-results,opened-profile,opened-post,warm-search-results}.png`.
Earlier unsuccessful diagnostics are retained and excluded from passing checks.
The 0.3.3 debug APK, locally signed non-debuggable preview, source archive and
checksums are packaged under `artifacts/`. No external deployment was performed.


## Native search feed (2026-10-16, 0.4.0)

Search is now a discovery step behind a native feed. Index snippets/titles stay
internal. Exact post destinations are read through the normal source pool and
rendered with the regular PostCard, including media, save, offline and post
navigation. Profile destinations resolve into actual public metadata and appear
as native account cards in a horizontal People row. Profile timelines are not
silently inserted as if their unrelated posts matched the search. Requested post
identity is checked; surrounding replies from a post page do not become extra
search matches. Login walls and missing content never become synthetic posts.

The feed enters from the bottom (320 ms slide / 220 ms fade), and individual
arrivals fade/slide in (260 / 300 ms). Compose uses the system animation duration
scale. Stable item keys retain identity while dated posts sort newest first;
undated posts remain at the end with dates marked unavailable. Loading placeholders
are static, avoiding a continuous shimmer workload. Already revealed lazy items
retain their reveal state while navigating/scrolling.

Each scroll-triggered block resolves at most six discovery destinations, with at
most two concurrent source reads. Each completion publishes immediately. Native
search sources that already supply posts need no separate resolution. Within four
items of the bottom, the UI requests the next block automatically. Pending links
are consumed before fetching another index page. Discovery follows the issuing
source's cursor only, with provider/query validation and repeated-cursor detection.
Changing providers requires a new session. Duplicate post identities and duplicate
account destinations are removed; common account aliases compare without case.

DuckDuckGo pagination uses the Next form returned by the provider, retaining only
reviewed fields and the exact search query, endpoint and offset. NetworkGateway
now submits these bounded forms through its existing filters, Tor selection,
original-access gating, cancellation and response limits. Same-origin 307/308
redirects preserve the form; cross-origin form redirects are rejected before the
body can be sent elsewhere. Native source cursors and same-source Darwin search
links are supported when supplied. Bing RSS currently has no verified continuation;
it supplies its first discovery page and resolves that page's destinations.
End-of-results UI follows the available source, not an invented infinite stream.

Sessions are limited to the three most recently used searches, 200 posts, 40
accounts, 20 discovery pages and a 1 MiB text budget each. The last discovered raw
page and pending links are bounded as well. New queries, tab/navigation changes
and Stop cancel outstanding reads. A session can resume its unprocessed candidates
after cancellation. A generation guard prevents old completions from replacing
new-query content. A failed continuation retains loaded cards and offers Retry;
a stopped search does not immediately auto-fetch again. Partial item failures
are skipped and counted, with an availability note when the feed ends.

Completed blocks persist post references and account metadata in the ordinary
bounded, expiring page cache. Post bodies are not duplicated in page metadata.
Persisting a completed block precedes the next automatic block so older snapshots
cannot race a newer one. Visible cards retain their existing active-cache leases;
bookmarks and explicit offline saves retain their existing protection. Legacy
page-cache records without account metadata still read correctly.

Verification on the KVM Android 29 emulator:

- All 68 core tests pass: 42 general, 15 search, five search/privacy and six new
  search-session cases. New coverage checks completion-order streaming, exact
  requested identity, two-read concurrency, pending-before-pagination behavior,
  deduplication, repeated cursors, bounded memory/results, cancellation/resumption,
  unavailable reads, retry and undated posts. Gateway tests exercise actual form
  bodies, same-origin redirects, cross-origin rejection and required-Tor gating.
- Debug, non-debuggable release and instrumentation APKs build. Android lint has
  zero errors and 45 existing warnings. One first-build pagination fixture used
  an HTTP post URL, which the HTTPS-only extractor correctly rejected; the fixture
  now uses the original HTTPS destination. The failed log is retained.
- The selected full native suite passed **26/26 in 63.1 seconds**. Three new
  deterministic feed cases assert posts appearing before all reads complete,
  absence of index titles/snippets, native account cards, cache restoration,
  automatic next-block loading, deduplication/end detection, and cancellation
  when a new query replaces the old one. Existing offline-video, native grid,
  scroll retention, cache expiry/protection, settings, backup/storage, XML and
  two Tor enable/exit cycles also pass. Separate Tor/direct exits and fail-closed
  engine traffic were verified.
- A fresh live `elon` query produced the first full native post after **4119 ms**,
  requiring newly fetched content provenance rather than a cached/indexed snippet.
  Scrolling triggered a real provider Next-form request through the guarded Tor
  path. After two index responses, the feed held **11 posts and three accounts**,
  zero failed destination reads, no continuation error, and further results were
  available. Actual native post text and photo content were inspected. Ordinary
  content used the default direct route; engine requests retained required Tor.

These are point-in-time live results. The 0.3.3 index-only timings do not measure
filling the new feed: resolving full posts adds network work. Public index
coverage may be older/incomplete, some destinations may fail, and Tor exits may
receive challenges/errors. Other platform continuations are source dependent;
this run does not establish their universal live coverage. Physical-phone speed,
ARM runtime behavior and arbitrary query reliability remain unverified.

Evidence under LiberBuild/evidence:
`native-search-feed-{build,final-build,verified-build,test-build,device-tests,native-trace}.log`,
`native-search-{streamed,feed-posts,auto-pagination,live-posts,live-pagination}.png`,
`native-search-live.json` and `native-search-live-index-{1,2}.html`.
The locally signed 0.4.0 preview, debug APK, source archive and checksums are
packaged under artifacts. No external deployment was performed.

The installed non-debuggable preview is version 0.4.0/code 7, with device APK hash
matching the packaged preview. One-time code preparation succeeded. Its normal
reader displayed the retained live feed with a saved-results label; that screen
is not additional evidence of a fresh network read. The actual bottom-entry and
card-fade transition was recorded and inspected at 0.9 and 1.2 seconds:
`native-search-feed-entry.mp4`, `native-search-entry-frame-{09,12}.png`, and
`native-search-feed-preview-ready.png`. The final lint run on the updated test
sources also passed with zero errors and 45 warnings.

### 0.35.37 — regional discovery and the local identity graph

Added native Search → Posts / Businesses / Events, explicit remembered areas,
protected saved business/event snapshots, and reader-export fields documented in
`docs/PORTABILITY.md`. Standard operation requires no AI model, account, GPS
permission, root or browser extension. Source records keep original social URLs
separate from Darwin's retrieval frontends.

Qualification evidence is under
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/local-discovery/`:

- Full core run: 1,615 cases, 1,614 passed, zero failures/errors; one unrelated
  acquired-model SSE embedding parity case was intentionally skipped.
- Nine focused native checks cover actual native cards/details, source icons,
  saved-copy protection through expiry/corrections, local export validation,
  remembered areas, cancellation, fast-result delivery ahead of slow providers,
  cross-platform return navigation, complete date ranges and Android calendar
  parsing. Native screen captures are retained with the evidence.
- The existing 172-method native regression selection covers Context readers,
  discussion/fusion/media behavior, search feeds/entity shelves/scopes/privacy,
  source-link opening and reader/source-pack import/export. Exact APK hashes and
  the final result are in `native-regression-summary.json`.
- Live production transport: explicit public example Melbourne, Australia;
  location and Overture lookups used Tor/NetworkGateway. One bounded Overture
  lookup returned 630 records, 235 with social links; the local merger retained
  605 business cards in the last qualified run. Ingest took about 1.5 seconds on
  the API 35 test emulator; this is not an end-to-end phone search benchmark.
- Live event production reader: the City of Melbourne's public International
  Jazz Festival listing supplied 16–25 October 2026. The app washed the page,
  extracted the typed occurrence, recovered the explicitly stated city from a
  swapped city/state address field, and displayed the complete date range.
  HTML-first content negotiation fixed this publisher's server error when JSON
  was preferred. No page script, image/widget loader or AI call was involved.
- Release lint: zero errors/fatals; existing warnings remain in the retained
  report. The preview uses the non-debuggable locally signed release APK, with
  a versioned source archive and pre-upgrade private-state/APK backup.

External limits remain explicit. The tested Overpass routes were unavailable or
hit time/resource limits; their outage did not prevent Overture/native results.
This validates failure isolation, not their current public availability. Public
Fused examples have no service guarantee; hosted Open-Meteo has non-commercial
free-access terms. Both provider source notices and policy references ship with
this release. Ordinary graph payloads/source caches expire and are bounded;
protected saved snapshots are independent. Structured calendars/metadata are
supported; posters, unstructured social announcement date inference, and
JavaScript-only event calendars are not claimed as implemented. The initial
Overpass batch is limited to 15 km; broader selected areas also use publisher
search and previously observed sources.

The acquisition path retains typed facts and bounded source hints, not downloaded
HTML or Markdown. Generic pages reuse the existing in-memory Markdown washing;
calendar/JSON feeds are parsed directly. Temporary discovery `.md`/HTML files are
never created. Existing explicitly saved/offline material remains protected.


### Local business routing, identity and contact polish — 0.35.38

Qualification evidence: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/business-routing/`.
The exact qualified APK hash and all check counts are in `final-checks.json`;
`main-upgrade/promotion.json` records the installed non-debuggable preview.

- Core: 1,629 cases, 1,628 passed, zero failures/errors. The existing optional
  acquired-model SSE embedding parity test was skipped.
- Native: 13 focused methods, the production four-query business test, one live
  production event extraction test and 172 existing regression methods (187 total).
  Focused checks cover distance-unit persistence, contact dial/email intents,
  explicit owner roles versus directory-publisher accounts, ordinary duplicate
  reconciliation with protected saved copies, native details and export validation.
- Live public example: Melbourne, Florida, 25 km. The selected Tor-protected
  public index, Photon/OSM, bounded Overture enrichment and passive source readers
  returned Sky Zone, taco businesses, Burger Inn and Indian River Coffee. Burger
  Inn occupied one physical card; Sarno Road coffee records occupied one card,
  and the unrelated realtor was absent from its business accounts. Distinct
  physical branches remain separate. First-card timing and full background
  completion timing are separate measurements in `live-final.json`; emulator
  results are not a promised phone or service latency.
- Actual native screens were inspected, including the km/mi picker and full-height
  contact details. Phone and email actions use ACTION_DIAL/ACTION_SENDTO, with
  injected intent interception in instrumentation; tests never placed calls or
  sent mail. Screen evidence is retained under `screens-final/` and `main-upgrade/`.
- Source-derived identity checks include name/legal-suffix/apostrophe variations,
  street abbreviations, a building-name prefix, an omitted house-number letter
  with supporting phone/locality evidence, and conflicting directory map pins.
  Suite/branch conflicts remain distinct. Directory headline decoration cannot
  replace an equally authoritative clean business name; both observations remain
  in the source evidence. Malformed comma-separated website fields are split.
- Explicit schema owner/operator/founder relationships retain their role and
  source. Directory footer accounts are directory-publisher information, not
  inferred business ownership. Previously cached mistaken footer bindings are
  reclassified on read; saved snapshot bytes are not rewritten. Business account
  buttons require usable routed public profile/content, not an empty HTTP success.
- Release lint: zero errors/fatals. Source licences/notices are bundled, including
  Photon/OSM attribution. Versioned source/APK archives and a pre-install private
  state backup are retained. App-private records were compared before first launch
  after incremental replacement; the exact count and result are in the promotion
  receipt. No product feature requires root, ADB or privileged installation.

This supersedes 0.35.37's initial 15 km Overpass limit: targeted named/category
requests now use the selected radius, with bounded response/record budgets.
Overpass was unavailable or resource-limited during qualification, so availability
was not verified; failure isolation was. Public Photon/Fused examples and publisher
frontends provide no uptime promise. Login walls, unavailable pages, JavaScript-only
content, missing structured dates and absent public accounts remain source limits.
Public index queries and geographic lookups use NetworkGateway/Tor; publisher
reads use the selected content connection. No fallback silently bypasses Tor.

Publisher HTML is passively parsed and washed to inert Markdown in memory. Typed
facts and bounded source hints survive; HTML/Markdown files, page scripts, widgets
and hidden browser sessions do not. Ordinary graph/source caches remain bounded
and expiring, independently of protected saved records.

### Field-directed progressive enrichment — 0.35.39

Evidence root: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/business-enrichment/`.
`final-checks.json` binds the core/native/lint results to the qualified debug APK;
`main-upgrade/promotion.json` and `live-verification.json` record release installation
and actual screen inspection. Versioned APK/source archives retain checksums.

- Core qualification: 1,639 cases, zero failures/errors; 1,638 passed and the existing
  optional acquired-model SSE embedding parity case was skipped. Ten new cases cover
  field-directed route priority, timestamp-independent stability/backoff, contact and
  sibling-branch scope, visit evidence, AllThePlaces bounds/lineage and calendar formats.
- Focused native qualification: 17 methods, including ready cards while enrichment
  is pending, live detail-sheet updates, saved visit evidence, stable deck positions,
  rejection of an uncooperative late callback, and wider calendar windows following a
  healthy empty response. Existing miles, contact intent, ownership, duplicate and
  portability checks remain included.
- The first final-candidate focused run failed its first background-start wait while
  the fixture left the restored foreground reader active. Test setup now enters the
  actual Discover section, cancelling the reader as real navigation does. All 17 pass;
  deferred traffic priority was retained. The earlier failure log is preserved.
- Two live source methods use the production backend/Gateway. The published
  AllThePlaces Sky Zone file supplied one branch within the public example area
  (Melbourne, Florida, 25 km), including branch contacts and CC0/upstream evidence.
  A repeated request reused its cached slice marker. The collector did not download
  the global archive or run a spider. Florida State University's advertised Localist
  calendar was recognized from real application/CDN metadata, then supplied 17 local
  dated occurrences through public event JSON. Reports retain exact records and URLs.
- The production four-query native method returned Sky Zone (one card), tacos
  (12 cards), Burger Inn (one card), and Indian River Coffee (one Sarno Road card).
  Sky Zone received a further branch contact from the published brand slice. The
  unrelated realtor remained absent from coffee business accounts. First-card times
  were approximately 8.1, 3.9, 12.0 and 8.3 seconds; full background completion took
  approximately 46, 93, 65 and 68 seconds. These are one emulator/Tor/source run,
  not a universal latency improvement or physical-phone benchmark.
- The production event washer method and existing 172-method reader regression
  selection are retained, for 193 native methods in the complete qualification.
  Native screen evidence includes updated visit/resource details and distance units;
  no real call, email, order or reservation was placed by validation.

Source limits remain explicit. AllThePlaces covers published brands rather than
every independent business; oversized/empty/unavailable spiders are skipped or
deferred. Public Photon/Fused/Overpass/index availability remains variable. Live
availability is established for the checked AllThePlaces and Localist routes, not
every calendar tenant. Trumba/25Live and Events Manager recognition is fixture-tested;
no fresh live tenant availability claim is made for those families. Localist is
bounded to two 50-entry pages, with further coverage reported. Valid empty machine
calendars do not create invented events; a reachable empty website is still not
working business content. The wider research catalogue is not an implemented-API list.

All acquisition still uses NetworkGateway, selected Tor failure closure and passive
in-memory washing. Visit facts reuse existing evidence and saved/export records;
ordinary data remains bounded/expiring and explicit saved snapshots stay protected.

### Cached event reconciliation and business social routing — 0.35.40

Evidence root: `/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/business-reconciliation/`.
`final-checks.json` binds the final qualification to debug APK SHA-256
`e6ed27065928542a33b99449744cfde627586acd29bbc70e61f8ef1db0139e4a`.

- Core: 1,653 cases, zero failures/errors; 1,652 passed and the existing optional
  acquired-model parity case was skipped. Fourteen new cases cover observed event
  identity variants, offset conflicts versus separate performances, fresh edits,
  primary-source sequence isolation, retained update dates, washed descriptions,
  and compatible publisher-brand evidence.
- Android: 199 methods passed on that exact candidate: 21 focused discovery/UI,
  four live source methods, the four-query live business method, the live event
  washer method and 172 existing reader regressions. Release lint: 159 warnings,
  zero errors/fatals. No new runtime dependency, model or account requirement.
- The first native candidate passed 19/20 focused methods; the late-card completion
  wait timed out. Concurrent store/account/page projections could replace a newer
  deck with an older read. Projections are now serialized; the final 21-method
  run passes, including late-card order, concurrent social validation and live
  event-sheet updates. The earlier log and interrupted build are retained. The
  timeout alone is not proof of its underlying scheduling sequence.
- Production reads retrieved the public Visit Space Coast calendar and both the
  Downtown Melbourne Trick or Treat and Halloween Family Fun Night at Intracoastal
  announcement pages. Thirty-two observations became 30 occurrences; both reported
  pairs retained their source identities in one card. The calendar explicitly
  declares America/Halifax, while corresponding web records use New York time.
  Matching wall clock plus specific event link/venue evidence reconciles these;
  no approximate one-hour matching rule is used. Fixtures keep different dates,
  venues, performances and unqualified all-day listings separate.
- Stated modification dates take priority over retrieval dates. A later retrieval
  of older content cannot inflate the selected details' freshness. Sequence numbers
  apply to the primary source identity, not any alias accumulated by a merge.
  Missing public edit times fall back to retrieval time and do not establish the
  publisher's actual last-edit time. Complete matching text fills clipped excerpts;
  double-encoded paragraph tags are removed by bounded inert parsing.
- Native checks repair previously cached event rows/FTS without extending expiry
  or modifying saved snapshots, retain later entries in a 300-source queue, bind
  newly identified owners without resetting backoff, and show a usable account
  while supplemental pages remain pending. Newly extracted contacts/accounts
  survive washed-description fallback. Version 5 requeues successful sources for
  the corrected projection on the next explicit search; failures keep cooldowns.
- The final public four-query run around Melbourne, Florida, 25 km returned Sky
  Zone (one card), tacos (seven), Burger Inn (one), and Indian River Coffee (one
  Sarno Road card). Sky Zone exposed three usable brand accounts (Facebook, X,
  TikTok); Burger Inn exposed one unique usable Facebook account. Slash variants
  remain in evidence but display one icon. Coffee accounts were identified but no
  accountless route was usable in this run. No unrelated realtor was a business
  account. First cards took about 7.5, 1.7, 5.8 and 7.8 seconds; full enrichment took
  60, 36, 62 and 62 seconds. These are source/emulator observations, not phone or
  general latency guarantees; directory inventory varies between runs.
- Intracoastal's own page supplied its declared Facebook and Instagram URLs through
  the production passive washer. Live readability was measured separately: an
  Instagram alternate returned no recognizable content, its original returned 400,
  and the Facebook original did not identify the selected public profile. These
  remain unavailable, not healthy content. Declared links stay in source records;
  failed/empty account buttons remain hidden with bounded retries.
- Actual native screen evidence includes social connections during enrichment and
  a changed event price/status in an already open sheet. Versioned source/APKs and
  pre-install private-state backups accompany the release. Installation, protected
  logical-row audit and live release inspection are retained in `main-upgrade/`.

Every app request remains behind NetworkGateway; selected Tor fails closed.
Network limits, source attribution, saved-copy protection and passive in-memory
extraction remain in effect. Emulator backup tooling is development-only; stock
Android users need no root, ADB or privileged installation.

## Signal discovery and source-backed enrichment — 0.35.41

Qualification evidence is under
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/source-records/`.

- Core: 1,674 cases, 1,673 passed, zero failures/errors; the existing optional
  acquired-model embedding parity case remains skipped. Native: 197 checks passed
  on the isolated API 35 emulator: seven source UI/cache cases, six acquisition
  pipeline cases, eight article/cache cases, four live public-source cases, and
  172 reader/discussion/merger/portability/search/link-opening regressions.
- Debug, instrumentation and non-debug release builds succeed. Release lint has
  zero errors/fatals (159 warnings and one hint). All final native receipts refer
  to the same frozen debug APK. Prior failed candidates are retained separately.
- Deterministic selection excludes navigation, promotions, cycles and repeated
  destinations, works with declared citations in other languages, retains late
  citations on long documents, and distinguishes structure-accession DOIs from
  papers. Native fixtures prove that a first brief can be published while an
  underlying source waits, source-parent relationships survive, six follow-ups /
  two levels remain bounded across refresh, and an unavailable follow-up does not
  erase the usable brief. Publication checks precede ordinary follow-up material;
  actual notice text is routed through the existing reader.
- Conditional article refreshes retain validators. Requests without validators
  can reuse a bounded shared body; explicit cache clearing requires another read.
  An older washed article receives one full extraction for newly supported paths,
  then returns to conditional refresh. Source catalogue data is shared rather
  than reloaded for every link. Record-cache tests cover coalescing, expiry without
  access renewal, a retry floor, clear-during-read invalidation and explicit reads
  proceeding while automatic work waits for the feed.
- An initial native check exposed foreground starvation in the source panel;
  explicit and automatic record reads now have separate admission lanes. A later
  regression found the new annotation field missing from the strict portability
  schema. Old absent fields now remain valid, nested values retain strict types,
  and semantic validation checks sizes, HTTPS URLs and the same original post.
  Saved text and captured-thread annotations survive an actual export/import
  round trip. The complete regression suite passes after both fixes.
- Live production-Gateway checks retrieved a retraction of
  `10.1177/1758835920922055`, combining the publisher and Retraction Watch entries
  for the same dated notice. The AlphaFold 3 article
  `10.1038/s41586-024-07487-w` was recovered from its public PMC copy: 77,392 text
  characters and 12 selected source references, headed by earlier explanatory
  research after ranking changes. Before those changes, structure entries crowded
  out useful paper links; the earlier receipt is retained as research evidence.
- Live registration/publisher and PLC cases validate actual authoritative domain
  records, explicit owner/advertising-manager declarations and recorded handle/
  hosting changes. Missing example.com advertising declarations remain an
  unavailable component, not a healthy empty ownership claim. These secondary
  records do not enter the brief or produce credibility scores.
- Community Note text/facet support is verified against FxEmbed's public schema
  and processor plus typed fixtures, persistence, routing and native disclosure
  tests. This does not establish universal availability, complete note coverage,
  or Helpful/Not Helpful rating status. No official cumulative notes dataset is
  downloaded to the phone.
- Actual native screens were inspected for a readable publication notice,
  expandable public annotation and the article's underlying-source links. Nested
  article/source sheets and source callbacks work without AI. Generic DOI/link
  labels use the surrounding readable citation rather than an indistinguishable
  row of “DOI” buttons. Empty inspection actions and resolver/platform-domain
  profiles are omitted. Native screenshots and final public receipts are retained.

Every new product request uses NetworkGateway. Passive article washing executes
no page scripts or subresource loads, produces typed text/Markdown in memory and
does not leave intermediate Markdown files. Existing public-copy, archive,
caption-first, media cleanup and saved-copy protections remain in effect. Source
records are bounded ordinary cache; saved Context evidence retains its existing
protection. No new paid service, model, native inference dependency, browser
extension or privileged Android feature is required. These source/emulator checks
do not establish physical-phone speed or general external-service availability.

## 0.36.0-rc2 — bounded media Info, 2026-10-22

The post Info action is a stable compact icon, opening a native drawer with
automatic staged inspection. Normal media acquisition records only a hash,
final URL/type and explicit credential references; receipt persistence is
coalesced off the delivery path. Standard container declarations remain passive.
Ordinary raster files without credential carriers skip native PKI initialization.
On-demand work is serialized and bounded by time, bytes, requests/redirects,
pixel count, container sizes and cache quotas. Failed attachments do not prevent
later available files being inspected. Foreground cancellation retains findings
and stops the indicator. Parent cancellation and cache clearing prevent late
publication. Offline copies never fetch missing media or credentials.

Structured generation recipes are supported in PNG text and applicable EXIF
UserComment records. Connected ComfyUI paths retain separate positive/negative
prompts; unused nodes and ordinary captions are not AI findings. NovelAI's
fixed-key verifier reads exact noninterlaced eight-bit PNG samples and bounded
alpha records. It does not repair or rewrite images. Copied reports lose signature
authority; saved/portable recipes remain readable. The small C2PA JNI bridge uses
the pinned SDK's caller-created deny-network context rather than its default-context
detached convenience API. Only NetworkGateway downloads credential bytes.

Verification:

* Full JVM suite: **1,759 tests, zero failures, one existing optional acquired-weight
  skip**. New checks include structured versus ordinary metadata, connected graph
  recognition, bounded compressed fields/depth/pixels, EXIF carriers, Ed25519 RFC
  vectors, Python-compatible serialization, strict acquisition/redirect filtering
  and shared request/body budgets.
* Debug/release APK assembly and lint pass. All three supported native bridge ABIs
  compile with 16 KiB page alignment. Existing deprecation/lint warnings remain.
* API 35: **54 selected Android cases, zero failures**, including one Android-8-only
  assumption skip. Coverage includes exact-byte cache reuse/restart/replacement,
  offline-to-online recovery, unavailable Tor, partial failures, foreground yield,
  cancellation/clear races, native detached validation and wrong-image rejection,
  native disclosure presentation, portability and protected archive storage.
* Four external public sample PNGs from `enslo/sd-metadata` at
  `866c04b3c02151fc62f7e7a15d0499cbeccb500a` verified both file and alpha signatures
  against NovelAI's published key, in independent Python and the Kotlin engine.
  Android repeated those positive checks; changing one RGB sample invalidated both
  records. Emulator timings were approximately 0.26–0.51 seconds per original.
  These are exact-fixture checks, not general detector accuracy or phone-speed claims.
  The sample images stay outside source/APK and are not distributed with the app.
* A wider first Android run exposed an opening/display synchronization failure.
  The production action now retains one IconButton while findings change, and the
  updated run passed. Native pixel inspection separately waited for the actual
  dialog surface: an immediate UIAutomation screenshot could still show the
  underlying Activity despite successful Compose semantics. The paused native
  screen confirms the dark drawer, concise finding card and expandable details.
* Signed release APK: **192,786,825 bytes**, SHA-256
  `ffeb72fde28e871c61f1fe8b87533888b8eeebe49c4057ce9a185ff0f2d69e7d`;
  **91,932 bytes (89.8 KiB)** above rc1. No classifier, watermark model, language
  model or additional model-inference runtime is bundled by this change.

Evidence is under the external `evidence/info-engine-v1/` directory. General
camera/GPS enrichment, WebP pixel verification, guessed sidecars, hash/reverse-image
lookup and optional statistical detectors are outside this qualified pass.
Physical-phone and broad live credential-host coverage remain unverified.
