# Protection model and security review

anti social combines transport privacy, a shared network gateway, adaptive source
routing, passive extraction, bounded parsing workers and local storage controls.
Start with [the architecture map](docs/ARCHITECTURE.md) to see how they compose.

**Settings → Privacy & connection → Tor for all traffic** routes the app's
content, photos, videos and updates through embedded Tor on stock Android.
Requests pause when selected Tor is unavailable. This app-wide setting exists
alongside a separate Tor-required search connection. A direct-reading mode or
search-only Tor client does not describe the other available mode.

Every content/service/update request uses
[NetworkGateway](core/src/main/kotlin/app/antisocial/core/NetworkGateway.kt).
URL cleaning, compiled network filters, destination/origin restrictions, scoped
credentials, request-purpose checks and privacy-session checks apply across
source changes and redirects. Choosing a backup frontend cannot bypass them.
Public retrieval keeps no social login session. Daily filter maintenance retains
working compiled protection when a source update fails.

Passive acquisition avoids executing websites. Read-only descriptor-based
isolated workers and independent watchdogs contain documented image/PDF/article
operations, with bounded parent-validated output. Some platform formats and
model operations use ordinary private processes instead; the exact authority
boundaries are in [hardening](docs/HARDENING.md). Runtime/model installation
verifies its pinned catalogues and payloads. Expiring ordinary caches and
temporary files are separate from protected saved records/offline downloads.

[Reviewing and evidence](docs/REVIEWING.md) links these controls to implementation,
unit regressions, Android instrumentation, dated live-source checks and
user-reported phone testing. [Release qualification](docs/RELEASE_QUALIFICATION.md)
records what ran for each increment. An optional skipped test or an unmeasured
phone metric has that specific scope; it does not describe the whole app as
untested. These records also do not constitute an independent security audit.

For a security report, identify the affected version, entry point, concrete
behavior and reproduction conditions. Include the relevant connection mode and
whether an external application was deliberately opened. Keep credentials and
private reader state out of public reports.
