# Collected images

Post grouping and gallery presentation have different responsibilities. Grouping
keeps its existing account, text, time, ambiguity and source checks. A gallery
removes a repeated picture only among editions already admitted to that card.
Original posts, URLs, media attachments and comment roots are retained.

Since 0.38.46, the complete attachment inventory and its screen layout are
separate. A shared cover or qualified related interview frame can occupy one
slot with source-edition controls. Every distinct recording/frame remains in
the collection plan, Context input and saved originals. A matching poster is
cover evidence, not proof that two recordings contain identical footage.

The earlier reading card could keep raw media records after its captions were
frozen. Later image-loader observations then improved grouping without reaching
the displayed gallery. `rememberCollectedMedia` now observes the bounded local
bank's revision and projects current observations over the frozen media records.
It does not rebuild the text or make a network request. The same projection is
used for Context's collected attachments. A single post can use it too.

The existing photo hash can change under a platform's resize/reencoding chain,
especially for lettering and charts. New `f2` observations retain the prior
shape/color features plus a 64-by-64 luminance grid. True area averages, computed
one source row at a time, avoid dependence on a chain of bilinear thumbnail
resizes. Gallery comparison checks aspect, contrast, spatial color, overall pixel
error, worst local patch and peak error. It can match compression differences
without relying on identical coarse hashes. Local edits and crops may remain
separate even when the broad photo hash agrees. This is conservative visual
comparison, not semantic understanding of every possible image edit.

No new image request, remote classifier, model, library or permission is involved.
The bank remains limited to 2,048 exact URL observations with seven-day expiry;
no original bitmap or media file is retained by it. Each new observation carries
about 5.6 KB of encoded derived data. Transparent artwork, tiny images, simple
logos and blank placeholders retain the existing weak-evidence exclusions.

On save, new editions capture available image observations even if the calling
feed holds raw frozen roots. Existing saved text/attachments are preserved. The
offline reader uses captured observations rather than newer bytes at a live URL;
it does not download images to improve a match. Previous `f1` observations remain
readable with their earlier comparison behavior. Malformed/oversized `f2` grids
are rejected. Older app versions do not understand the new observation version.

An open image viewer holds its selected media list while the underlying card
removes duplicate copies. It cannot jump to an unrelated picture or acquire an
out-of-range page index because the card's list became shorter.

Regression material includes the retained X and Instagram copies of the reported
“No President” image and the public X images for “NEXT STOP: CLEMSON” and the
unaccompanied-children update. Test-only provenance is recorded beside those
assets. The three-platform UI scenario uses a synthetic Facebook edition of a
known picture; it does not establish availability of a particular live Facebook
post. Tests exercise lower-resolution JPEG copies, a changed infographic figure,
a crop, distinct photos, late observations, viewer stability and saved-edition
round trips. This is not a guarantee of arbitrary crops, watermarks or every
future platform transformation.
