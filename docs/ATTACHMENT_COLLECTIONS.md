# Attachment collections

## Complete presentation and late content, 0.38.46

The live card, Context and offline reader use a shared, read-only `CollectionPlan`
over already admitted originals. Text compression is independent of the complete
attachment inventory. Existing passage fusion and `CaptionWeave` remain the
preferred presentation; if alignment cannot represent a collection, complete
source-labelled blocks retain each distinct caption. A longest-per-platform
fallback no longer removes another edition's unique wording. Save validation is
still required; a failed proof reports failure rather than silently dropping the
collection and saving only its primary original.

Late updates compare text and the observed media inventory. A new photograph can
therefore trigger off-screen adoption even when the caption is unchanged. Visible
wording and media remain frozen until the existing adoption boundary. A verified
reencoding adds attribution without reopening an already-read announcement; an
unseen additional photograph does not inherit its old read mark. Existing stored
revision signatures and reader state do not require a blanket reset.

Within one acquisition batch, same-original selection measures effective caption
content rather than redundant generated-title length before comparing attachment
coverage. Storage and matching share this chooser; cached page keys are unique.
Historical revisions are not unioned into a guessed current gallery. Candidate
nomination retains selective complete buckets before broad ones, with the same
128-candidate bound and mutual/ambiguity checks. An over-budget bucket remains
unexamined; it is not evidence that its members differ.

Media-enclosure entries in a feed's link inventory do not become competing
editorial destinations merely because two platforms use different image CDNs.
Literal publisher links and social work URLs retain their comparison rules, and
the full original link inventory remains available.

Distinct recordings remain separate inventory entries even with identical posters
and equal or unknown durations. The reader presents one shared cover with source
edition controls, preserving every playable recording for Context and saves. The
existing qualified related-coverage route can likewise share a compact slot for
nearby interview frames while keeping each captured frame selectable. Related
scene similarity no longer deletes a distinct picture from the inventory.

## Gallery headings and preview attribution, 0.38.46

A single-picture edition can use a shorter heading than its complete gallery.
After the existing original/time and actual-picture containment checks, a bounded
caption route recognizes a complete, unique heading at a sentence boundary. It
requires at least six words and 35 characters, limits omitted surrounding prose,
and excludes changed quantities, negation, editorial corrections, questions,
meaningful symbols and competing destinations from that additional route. Every
picture must still be proven present; ambiguous galleries stay separate.
Detailed `attachmentFingerprint` evidence can support this qualified nonblank
gallery-caption relationship without becoming general post identity. A blank
weak picture or legacy coarse-only attachment observation does not qualify.

Composition keeps the longer announcement's extra sentence with only its actual
sources and presents the shared heading once. Original captions, media, links
and discussions remain intact. A short already-matched caption can also compose
when one edition adds a known opaque campaign link; its complete link inventory
remains available. This closes a mismatch where grouping succeeded but the card
could fall back to one edition's attachments because caption composition failed.

An observed still matching a playable video's poster no longer loses its source
when removed from the picture inventory. The existing media label distinguishes
“Video from …” from “preview from …”; a still does not establish that its platform
published a video. If multiple distinct recordings match that still, it remains
an independent selectable image rather than being assigned to one arbitrarily.
Distinct opening frames alone do not establish identical clips.

These paths add no requests, models, dependencies or permissions. Picture
observations come from the existing bounded decoding/cache path. Native tests
start with an empty image-identity bank and cached real fixture bytes, exercise
late regrouping and deliberate caption adoption, retain distinct pictures, and
validate saved-copy source admission. Current dated results are in
[release qualification](RELEASE_QUALIFICATION.md).

## Literal caption variants, 0.38.45

An additional composition path handles small local wording edits around a large
shared caption. It requires an independently matched picture, original dated
editions within ten minutes, and agreeing number, negation, symbol and destination
guards. Account/name substitutions, changed questions and meaningful status
changes retain the existing conservative treatment. Earlier exact-copy, gallery,
decorative, credit-name and passage-composition routes remain available.

`CaptionWeave` aligns at most eight distinct caption versions, each bounded to
256 whitespace tokens and 4,096 source characters. A cheap overlap upper bound
precedes ordered alignment. At least 24 tokens, 140 shared characters and 88% of
the longest edition must survive; at most three variant regions are presented.
Before accepting a composition, selecting each original's branches must reproduce
its complete normalized token sequence. Opaque campaign URLs remain in the source
link inventory rather than becoming sentence variants. All original Post records,
media and discussion identities stay unchanged.

The native reader shows common wording once and brackets the alternatives with
small colored platform marks. Multiple marks identify wording carried by several
platforms. Shared text retains the reader's theme; the marks use fixed contrasting
foreground/background colors and accessible platform labels. Tapping a mark opens
its actual original through the existing app link-opening path. Scoped mentions
and links retain their existing renderer with explicit textual attribution.
Plain-text consumers retain a source-named representation, and saved collections
retain all original editions for later reconstruction. No model, dependency,
network lookup or decoder is added by caption alignment.

Repeated publications remain separate source records and comment roots. The reader
collects their shared content into one chronological card and retains each original.
These changes extend that existing mechanism; they add no model, dependency,
native runtime, database schema or automatic network lookup.

## Caption and image evidence

A substantial, otherwise identical caption can introduce its attached pictures
with a colon on one site and a downward layout pointer on another. The narrow
comparison requires the same prose and punctuation, supplied images and the
existing timing, ownership/content and ambiguity checks. Changed numbers, negation,
flags, upward arrows and destinations do not become cosmetic differences. The
composition selects an actual source caption; original text remains intact.

Qualified Meta CDN object paths can identify the same attachment across regional
Facebook/Instagram hosts and expiring query tokens. This is an image-object
identity, not a cryptographic statement about file bytes. The reader recognizes
bounded numeric object paths and known resizing parameters. Unrelated/lookalike
hosts, explicit crops, unknown transforms, credentials and ambiguous forms do not
qualify. Request URLs and their signed parameters are never rewritten by this
comparison. File hashes, metadata validation and download permissions remain tied
to the actual acquired file.

The image renderer keeps the evidence of a discarded copy within the collection.
For example, an X image can match an observed Facebook copy; that Facebook copy
can then identify an undecoded Instagram CDN alias. Forgetting the middle copy
previously allowed the Instagram picture to appear again. Every available pixel
observation must stay compatible. An intermediate reencoding cannot bridge two
contradictory pictures, and an ambiguous bridge is retained. Detailed contrary
observations outrank an object-name match. Gallery containment likewise uses the
fine comparison when both observations exist. Ordinary scene/related-coverage
rules remain a separate path.

Object-name preparation shares the existing operation's bounded memory allowance
through its own memo. It cannot contaminate ordinary URL normalization or become
a persistent tracking bank. Rendering prepares object keys once per input list;
no comparison downloads another image.

## Nitter editions and account references

The examined NASA RSS response was fetched from `teapawt.net` but declared
`xitter.cf` as its rendering hostname. A local parser binding now requires the
selected Nitter profile/feed origin and path, an agreeing channel profile,
a numeric item GUID and matching creator/status account before turning that
item's declared rendering URL into its original X URL. This declaration is scoped
to the response. It does not register a global route alias or grant another
network destination. Darwin's route and Gateway policies remain unchanged.

The same response uses `RT @author (by @curator): caption`. For Nitter records,
the heading's author must agree with the supplied author and its caption must
actually echo the body before it can become a compact repost attribution.
A distinct or disagreeing heading stays visible. Source title and body fields
are preserved.

An image-corroborated copy can also vary only in platform account mentions.
The complete non-mention prose, punctuation and destination checks still apply;
mentions are bounded, and normal image/date/original admission is mandatory.
The composer retains every changed reference with its source attribution. It
never asserts that different handles identify the same entity. Changes to claims,
quantities, negation or destinations continue through the existing conflict guards.

## Dated qualification

On October 25, 2026, the retained White House example supplied three editions and
eleven attachments. Unmodified 0.38.29 produced two cards. The candidate's native
replay produces one card with five displayed pictures and all three originals,
using zero Gateway requests. Actual downloads establish that the X picture is
already the third picture of the five-image Facebook gallery; the other portraits
and text cards remain distinct.

The NASA example includes an X repost and an Instagram gallery. The captured RSS
and images expose the hostname, heading and mention differences described above.
Reparsing the response and replaying the records produces one collection. Eight
source attachments produce five displayed images after three observed pairs are
matched. The low-information sky/aircraft pair remains unqualified and retained;
this release does not weaken the image bank's low-contrast admission.

Regression coverage includes a gallery plus three separately captioned pictures
in both arrival orders, CDN aliases, unsupported crops, distinct text-card detail,
ambiguous image bridges, mention variants, changed claims/URLs and source-bound
retweet parsing. The native fixture renders one caption, two actual decoded
pictures and both original comment streams; its viewer and All-comments screens
were inspected. Full dated gates are in [release qualification](RELEASE_QUALIFICATION.md).
Raw captures, private inputs, failed/passing checks, frozen APKs and screens remain
outside release assets under `evidence/gallery-members-20261025`.
