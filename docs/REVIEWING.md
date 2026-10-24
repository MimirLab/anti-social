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

At 0.38.9 the automated JVM run covered 1,963 cases, with zero failures/errors and
one optional weight-dependent skip. Android qualification covered 68 unique
checks: 67 passed and one optional live-Tor check was skipped. The qualification
record includes the initial new-test cleanup failure and corrected rerun. This
is a selected regression set, not a count of every test ever written or a claim
that every security boundary was independently audited in that release.

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
