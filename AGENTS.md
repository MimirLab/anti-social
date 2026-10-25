# anti social

anti social is a native Android, accountless social reader. Darwin's rebuilt and expanded routing
system is its donor. Preserve source provenance and GPL notices.
Call the donor Darwin in product language, documentation and user-facing updates.
anti social must run on stock, unrooted Android. Do not require root, ADB, a custom ROM,
or privileged system installation for any user-facing feature.

For architecture and review, start with docs/ARCHITECTURE.md and docs/REVIEWING.md.
They connect the app-wide Tor setting, shared gateway, source pools, worker boundaries
and dated validation; follow those paths when assessing an individual component.

All app networking must use NetworkGateway, including media, feeds, probes,
redirects and updates. Tor is optional but fails closed when selected. Alternate
sources precede accountless original fallback. Never pretend a login wall or a
reachable empty frontend is working content.

Keep settings and subscriptions local. Feeds are chronological. Ordinary cache is
bounded and expires; saved records and explicit offline downloads are protected.

Put tools, caches, emulator state, builds and evidence under
/run/media/red/RedtopStorage/DarwinBuild/LiberBuild. Do not change Darwin runtime
or profile state. Keep at least 15 GiB free on the system filesystem.

Run unit and Android instrumentation tests and inspect actual native screens.
Use scripts/gradle.sh so builds/tests serialize access to shared class outputs.
Document source-dependent capabilities and unverified external behavior honestly.

Promote tested incremental improvements into the live laptop preview rather than
leaving them only in an isolated candidate. Retain versioned APK/source archives
and back up the preview's local state before replacement so progress is recoverable.

Public releases must include the fixed-name `anti-social.apk` asset so the permanent
`https://github.com/MimirLab/anti-social/releases/latest/download/anti-social.apk`
link stays usable. Publish with `scripts/publish_release.py` after uploading the
versioned APK and corresponding source. Preserve versioned assets and source tags;
see `docs/PUBLISHING.md`. Keep Android application ID and signing identity compatible
across updates. Never publish credentials, private reader state or build caches.

Never publish personal locations, routines or other private details learned from
the owner conversation in release notes, documentation, tests, screenshots or
source archives. Use synthetic or unrelated fixtures. Supply real reproduction
details privately at execution time and retain their evidence only in the private
external build area. Public validation reports describe the behavior without
identifying the owner's area or personally visited businesses.
