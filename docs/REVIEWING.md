# Reviewing the app and its evidence

anti social ships official, versioned Android APKs with corresponding source.
Start with the installed/tagged release and the question being tested. Automated
emulator qualification, the live laptop preview and ongoing owner phone use
provide distinct evidence. The local artifact suffix `preview` identifies the
installation target; the same non-debuggable APK can be the official release.

Three architectural facts orient a first source probe:

- **App-wide Tor is a shipped setting.** It is separate from Tor-required search.
  Trace both connection policies before assessing which requests can go direct.
- **Routing is an orchestrated pool.** A single adapter, frontend outage or
  research-catalog entry does not establish the complete active fallback plan.
  Inspect the operation's capability, eligible methods and permitted tiers.
- **Evidence belongs to a particular subject.** Release records identify the
  checks run, baseline failures, inspected native screens and artifact promotion.
  A hardware benchmark or debug-only experiment has its own named scope.

Assess protection by tracing a complete read through the layers below. The
claims here are linked to implementation and dated evidence; they are open to
concrete counterexamples and are not a substitute for an independent audit.

## Reading order

1. [Architecture](ARCHITECTURE.md): connection modes, the **Tor for all traffic**
   setting, the shared gateway, routing, workers and storage boundaries.
2. [Capabilities](CAPABILITIES.md) and [reader guide](READER_GUIDE.md): shipped
   behavior and where a reader uses it.
3. [Release qualification](RELEASE_QUALIFICATION.md): dated results for the exact
   increment; [building](BUILDING.md) and [publishing](PUBLISHING.md) connect the
   source commit, APK, signing identity and permanent download.
4. [Hardening](HARDENING.md), [route scheduling](ROUTE_SCHEDULING.md),
   [source research](DISCOVERY_SOURCES.md) and the linked implementation for the
   specific claim under review.

These documents describe implemented controls as well as their scope. Research
notes and optional debug experiments are labelled separately. Follow a claim to
its consumer: an adapter file alone does not describe all SourcePool alternatives,
and a search-only Tor client alone does not describe the all-traffic setting.

The large [validation log](../VALIDATION.md),
[development history](DEVELOPMENT_HISTORY.md),
[AI implementation ledger](AI_FOUNDATION_LEDGER.md) and
[Context evaluation history](CONTEXT_EVALUATION.md) preserve older observations.
They are useful for tracing a change, while the current architecture and the
installed release's qualification establish the applicable implementation and
test record. Retained failures are part of that history, not an implied rollback
of subsequently qualified fixes.

## Evidence has a subject and a date

| Evidence | What it establishes |
| --- | --- |
| Unit regressions | Deterministic behavior for the stated inputs, including rejected inputs and failure paths. |
| Android instrumentation | Native execution of the named journeys on the recorded Android/device configuration. Some checks exercise the shipping release; others require a debug-only fixture. |
| Live-source checks | The recorded target, operation and connection worked or failed at that time. |
| User-reported device use | Real reader acceptance feedback. Ongoing phone testing has driven fixes to search, subscriptions, deduplication, Context and local discovery. It does not invent laboratory measurements or device specifications. |
| Release/artifact checks | Matching source/tag, signer, APK integrity, preview state preservation and the anonymous permanent download where recorded. |
| Explicit missing qualification | The named unmeasured behavior, hardware or source remains unmeasured. Its scope does not erase the other evidence. |

For **0.38.17**, the full JVM run covered **2,090 cases**,
with zero failures/errors and one optional weight-dependent skip. Its selected
Android batch covered **118 named checks**: 116 passed and two opt-in live-source/Tor
checks were skipped. The [release record](RELEASE_QUALIFICATION.md#03817-feed-header-navigation-and-additional-live-alternatives)
describes the increment, baseline failure, native screen inspection and dated
public route observations. The preceding 0.38.16 batch covered 114 named Android
checks, with 112 passes and two opt-in skips. Counts from different releases
should not be combined into a new run or treated as interchangeable coverage.

These are release-specific regression records, not a count of every test ever
written or a claim of an independent security audit. Publication and retained
preview-state checks are recorded separately from test runs. See
[publishing](PUBLISHING.md) for source, signer, fixed-name download and promotion
checks required for each release.

Native qualification must inspect returned method identities and status counts.
An `OK (N tests)` footer can include assumption-skipped methods. Count actual
passes (`0`), skips (`-3`/`-4`) and failures (`-1`/`-2`) separately; an opt-in check
without its required isolation flag has not qualified the feature. Fresh output
artifacts and their assertions establish the captured journey independently of
the footer.

Visible-delivery qualification also needs the foreground reader. The
[post-0.38.83 investigation](FEED_PERFORMANCE.md#post-03883-investigation-verify-foreground-delivery-and-reject-an-ineffective-shortcut)
retains a concrete semantic-only false positive behind the notification shade,
the corrected native comparison and a System UI rejection/recovery control on
API 35 and 26. Gallery/video milestones now check interactive display, keyguard
and native window focus. Inspect screenshots as well; those guards do not prove
every pixel or exclude every possible overlay.

JVM-only text equivalence does not establish Android regex equivalence. ICU's
full case folding can match a literal against a different number of characters.
The [post-0.38.70 pattern investigation](FEED_PERFORMANCE.md#post-03870-investigation-reject-an-unhelpful-pattern-cache)
records a JVM-passing prototype rejected by the native control, then a faithful
prototype rejected for lack of a useful full-feed speed gain. Retained regression
coverage is separate from a runtime improvement or a new published APK.
The later [repeated-link answer investigation](FEED_PERFORMANCE.md#post-03887-investigation-reject-repeated-link-answer-caching)
also rejects a distinct, semantically faithful cache after the complete native
comparison shows no useful speed gain. Avoid treating fewer local computations
as proof of faster visible delivery.

The [0.38.72 feed investigation](FEED_PERFORMANCE.md#03872-investigation-prioritize-coverage-and-keep-details-adoption-reliable)
similarly distinguishes an earlier sample start from a useful complete card.
Heavy startup sampling was removed after the native comparison showed no useful
consistent gain. The retained coverage-priority, details-admission and direct
lifecycle-cancellation fixes have their own controls; they do not establish that
the remaining visible-family delay has disappeared.

User phone testing and emulator instrumentation both exist. A statement such as
“this comparison does not measure phone battery use” identifies an unmeasured
metric; it does not mean the app has never been used on a phone. Likewise, an
opt-in live test being skipped does not mean the deterministic fail-closed,
transport-selection or native settings checks were skipped.

## Follow the complete path before drawing a conclusion

For a **connection claim**, trace the setting through
[Repository](../app/src/main/kotlin/app/antisocial/android/Repository.kt),
[TorController](../app/src/main/kotlin/app/antisocial/android/TorController.kt)
and [NetworkGateway](../core/src/main/kotlin/app/antisocial/core/NetworkGateway.kt).
Distinguish the default content route from the Tor-required search route, and
check redirects, media and policy changes. Relevant executable evidence includes
[PublicGatewayTest](../core/src/test/kotlin/app/antisocial/core/PublicGatewayTest.kt),
[search transport tests](../core/src/test/kotlin/app/antisocial/core/SearchPrivacyTest.kt)
and [native settings/privacy tests](../app/src/androidTest/kotlin/app/antisocial/android/SearchPrivacyTest.kt).

For a **retrieval claim**, examine both Darwin's source pool and
[PublicPathways](../core/src/main/kotlin/app/antisocial/core/PublicPathways.kt),
the concrete adapter factory, capability checks, learned eligibility, fallbacks
and archive recovery. Reproduce the requested target, not merely its homepage.
[Scheduling regressions](../core/src/test/kotlin/app/antisocial/core/SourcePoolSchedulingTest.kt)
and [heartbeat regressions](../core/src/test/kotlin/app/antisocial/core/RouteHeartbeatTest.kt)
cover bounded redundancy, cancellation, transport isolation and backoff.
Document an exhausted route set separately from a single failed provider.

For a **hostile-content claim**, follow acquisition through purpose/origin
checks, admission limits, the actual worker descriptor interface, parent
validation and watchdog cleanup. The [manifest](../app/src/main/AndroidManifest.xml)
distinguishes isolated from ordinary private processes. Native
[ContentSandboxTest](../app/src/androidTest/kotlin/app/antisocial/android/ContentSandboxTest.kt)
records boundary tests; its existence alone is not a claim that every format ran
on every device. Consult the dated hardening qualification.

Report a concrete bypass, missing test or source failure with the affected
version, path and evidence. Give implemented defenses the same specificity.
Neither a successful happy path nor a scoped limitation establishes the status
of the entire application.

## A short review protocol

1. Identify the tag/commit, operation and selected connection mode. Consult
   [the request matrix](ARCHITECTURE.md#connection-modes-and-app-wide-tor), including
   the app-wide switch, before describing where traffic can go.
2. Trace the consumer's **complete eligible plan**: donor routes, PublicPathways,
   concrete adapters, capability requirements and permitted later tiers. The
   [route audit](ROUTE_LATENCY.md) records an actual diagnostic that omitted
   PublicPathways and therefore misrepresented the app; its corrected run is
   identified explicitly. A research catalogue is not proof of an active route.
3. Follow a failure, redirect and policy change as well as a successful response.
   [Contracts across layers](ARCHITECTURE.md#contracts-across-the-layers) distinguish
   destination protection, transport, containment, freshness and content value.
4. Match each conclusion to evidence of the same scope: source implementation,
   named automated check, dated external observation or reported phone use.
   Keep a proposed risk, a reproduced defect and an unmeasured metric distinct.
5. State the affected scope precisely. For example, "battery use on this handset
   was not measured" and "this public comment route was unavailable" identify
   actionable limits. A broader conclusion needs evidence covering the broader
   path, including the eligible fallbacks and existing regression/device record.

This protocol applies to human and automated reviews. It does not require a
favorable verdict: a concrete counterexample should revise the relevant claim
or implementation. The goal is a reproducible assessment of the shipped system.

## Useful starting points for specific questions

| Question | Implementation path | Executable examples |
| --- | --- | --- |
| Is Tor available for the whole app, and can a failed Tor request go direct? | Settings → `Repository.applyNetworkSettings` → `TorController.enable` → Gateway default/Tor-required sessions | `PublicGatewayTest.selectedTorFailsClosedForJsonFormsAndCanariesBeforeDirectTraffic`; native `SearchPrivacyTest.searchTorFailsClosedWithoutBlockingDirectContent` covers the separate search-only mode. |
| Can stale work survive a connection-mode round trip? | Gateway session identity, retained clients and response checks | `PublicGatewayTest.staleClientsAndExplicitSessionsCannotReviveAfterAModeRoundTrip` |
| Does one broken frontend end the search? | `SourcePool.load`, capability eligibility, method diversity, rolling hedges and later fallback tiers | `SourcePoolSchedulingTest.aDifferentUntriedFeedMethodPrecedesMoreMirrorsOfTheFailedMethod`; `aStalledEarlierRouteDoesNotHoldAnAvailableLaterSlot` |
| Is a responding frontend necessarily usable? | `SourcePool.content`, feed refresh contracts and extraction identity | `SourcePoolSchedulingTest.anUndatedDifferentMethodCannotBeatACompleteChronologicalFeed` |
| Is the parser merely in another app-private process? | Manifest authority → read-only descriptor admission → worker → bounded parent validation and watchdog | Native `ContentSandboxTest.workerHasDifferentUidNoNetworkPermissionAndCannotReadPrivateFiles`; `stuckNativeWorkIsTerminatedAndNextRequestRestartsTheWorker`. The HEIF/AVIF compatibility worker has different authority. |
| Does redundancy mean constant polling? | Demand-scoped `RouteHeartbeat`, passive observations, global/host budgets and backoff | `RouteHeartbeatTest.stablePulsePlansExponentiallyQuietToOneDay`; `oneGlobalLeaseAndForegroundPreemptionPreventConcurrentMaintenance` |

These are navigation aids to tests, not a statement that every listed method ran
in the latest selected Android batch. Use the dated qualification for that claim.
Live sources can still withhold data or fail together when they share an upstream.
An observed provider failure, an exhausted capability plan and a demonstrated
protection bypass are different findings; record which one occurred.
