# Next-release qualification

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
