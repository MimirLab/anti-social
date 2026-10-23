# anti social

The project owner's supplied `anti-social-reference.png` is the approved artwork.
Its break-free emblem and lowercase wordmark are traced directly into Android
VectorDrawables. The adaptive launcher uses the white emblem on charcoal, as in
the supplied black icon. Android 13 also receives a themed monochrome layer.
Notifications use the transparent emblem; the reader wordmark follows the active
light or dark theme. No replacement font or generated logo is used.

To regenerate, install Pillow and potrace as development tools and run
`python3 scripts/generate_brand_assets.py`. Intermediates go to the configured
external build volume. The PNG is retained unchanged as a reproducible reference.
This artwork is part of the project's GPL-3.0-or-later distribution; third-party
platform icons retain their separate notices.

The repository's `docs/images/repo-hero.svg` composes those same approved emblem
and wordmark paths with the reader's mint/charcoal palette and separate headline
type. The PNG is its rendered counterpart for consistent GitHub display; the
Android download button likewise has SVG source and a PNG rendering. These are
repository presentation assets, not app screenshots or replacement app branding.

Release identity: `app.antisocial.android`. Kotlin packages are
`app.antisocial.android` and `app.antisocial.core`. Own native libraries, JNI entry
points, catalogs, backup formats, local storage and build variables use
`antisocial`, `AntiSocial`, or `ANTISOCIAL` as appropriate. Installable archives use
`anti-social-<version>-preview.apk` and `anti-social-<version>-source.zip`.

The old local prototype had no released users. This is a fresh Android identity;
it does not import old testing state or credentials. Old source/APK/private-state
snapshots are retained outside the repository for development rollback. The
workspace, external build-volume and existing development AVD paths retain their
historical names. They are operational locations, not release branding. Darwin
and upstream source attribution remain intact.

Run `python3 scripts/check_branding.py` to check active source identifiers.
