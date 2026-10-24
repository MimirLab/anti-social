# Reviewing the app and its evidence

Start with the installed/tagged release and the question being tested. The public
Android release, a laptop preview, automated emulator qualification and a person's
phone use describe different aspects of the same project. A local artifact named
`preview` is not a declaration that the public release is an untested prototype.

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

## Evidence has a subject and a date

| Evidence | What it establishes |
| --- | --- |
| Unit regressions | Deterministic behavior for the stated inputs, including rejected inputs and failure paths. |
| Android instrumentation | Native execution of the named journeys on the recorded Android/device configuration. Some checks exercise the shipping release; others require a debug-only fixture. |
| Live-source checks | The recorded target, operation and connection worked or failed at that time. |
| User-reported device use | Real reader acceptance feedback. Ongoing phone testing has driven fixes to search, subscriptions, deduplication, Context and local discovery. It does not invent laboratory measurements or device specifications. |
| Release/artifact checks | Matching source/tag, signer, APK integrity, preview state preservation and the anonymous permanent download where recorded. |
| Explicit missing qualification | The named unmeasured behavior, hardware or source remains unmeasured. Its scope does not erase the other evidence. |

For the published **0.38.10** release, the full JVM run covered **1,970 cases**,
with zero failures/errors and one optional weight-dependent skip. Its selected
Android batch covered **52 named checks**: 50 passed and two opt-in live-source/Tor
checks were skipped. The [release record](RELEASE_QUALIFICATION.md#03810-fair-public-index-continuations)
describes the exact increment, corrected fixture issues and native screen checks.
The preceding 0.38.9 batch covered a different selection of 68 unique Android
checks, with 67 passes and one opt-in skip. Counts from different releases should
not be combined into a new run or treated as interchangeable coverage.

These are release-specific regression records, not a count of every test ever
written or a claim of an independent security audit. The public APK, matching
source and fixed-name download were verified; the laptop preview was promoted
after preserving its retained reader state. See [publishing](PUBLISHING.md) for
the artifact checks required for each release.

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
