# anti social

anti social is a native Android, accountless social reader. Darwin's rebuilt and expanded routing
system is its donor. Preserve source provenance and GPL notices.
Call the donor Darwin in product language, documentation and user-facing updates.
anti social must run on stock, unrooted Android. Do not require root, ADB, a custom ROM,
or privileged system installation for any user-facing feature.

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
