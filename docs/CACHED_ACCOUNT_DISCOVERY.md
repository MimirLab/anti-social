# Discovery from already-read accounts

Version 0.38.58 connects ordinary subscription-feed observations to the existing
on-demand related-account deck. An explicit public account link or sharing role
already acquired by the reader can produce a useful first card without another
profile request. These are observed relationships, not claims that two accounts
have the same owner or endorse everything the other publishes.

## Retaining the inexpensive evidence

Nitter's HTML and RSS methods describe the original author separately from the
account sharing that post. The RSS parser now preserves that typed sharing role
for the same presentation, snapshot and passive-discovery consumers as HTML.
The original author, caption, generated source title, publication date, canonical
URL, attachments and links remain in the original record.

Admission requires the requested account's RSS path, selected source, channel,
declared rendering origin, original creator and item identifier to agree. Newer
numeric GUIDs and older rendering-URL GUIDs must identify the exact same original.
Duplicate/contradictory identifiers, another account's feed, foreign origins,
credentials and unrelated query/fragment values do not qualify. A source-scoped
cursor retains its existing ownership checks. This is extraction identity,
not permission for a new network destination.

The generated repost heading must echo the caption and name the requested
sharing account. Known picture/video-only headings have separate media/body
checks. A literal authored `RT by` line cannot independently establish sharing.
If the renderer truncates a URL inside its hostname, caption comparison requires
the complete destination in that response's retained links and the matching
literal prefix. This does not guess a destination. Source text stays intact;
presentation can omit a corroborated generated heading or media-link label.

The implementation was checked against Nitter's
[RSS renderer](https://github.com/zedeus/nitter/blob/master/src/views/rss.nimf)
and [RSS routes](https://github.com/zedeus/nitter/blob/master/src/routes/rss.nim).
No new upstream runtime, source instance or dependency is imported. A finite RSS
window still cannot establish exhausted chronological history.

## A bounded local first answer

`Repository.adjacentSession` reuses enabled subscriptions with exact local
account keys. The cache query first selects those follow memberships, then looks
up their post keys. It does not scan the Library, reading history or unrelated
accounts to construct recommendations. The native query-plan control checks the
follow-membership index and primary-key lookups rather than a platform-wide scan.

The seed account contributes at most 80 ordinary records, each at most 32 KiB of
actual stored UTF-8 payload. Existing viewed/unseen expiry applies before
decoding; a stale byte-count hint cannot bypass this admission. Malformed rows
consume their bounded slot but contribute no connection. Observation dates are
retained rather than renewed simply because the user opens discovery.

The first deck is published before cache reads for other locally linked accounts.
Those siblings share another allowance of at most 80 records in total and use
the existing two-lane bridge scheduler. The enabled-account ID map is decoded
once per action and reused. A Facebook seed can therefore receive a relationship
already observed through its linked X subscription without waiting for a live X
graph request. Existing registered bridges can accept that later evidence without
duplicating requests or reordering previously published methods.

These reads introduce no network requests. Live relationship methods, identity
expansion and later routing retain their existing request/concurrency limits,
selected-provider semantics, Gateway checks and Tor policy. Related-account
discovery remains an explicit action, rather than a continuously mirrored graph.

## Purge and damaged-record recovery

A manual ordinary-cache purge closes admission while cleanup is active and
advances the existing discovery generation. Old sessions cannot restart their
network work or repopulate that generation. A new action after cleanup can use
freshly acquired evidence. Saved records and subscription configuration retain
their existing protection.

The cache stress regression also reproduced a reader failure: Gson accepted an
ordinary `{}` record with null Kotlin-required fields, and projection then threw
outside the decode guard. Feed and bookmark projections now validate each decoded
record within its own failure boundary. A damaged row cannot discard the good
rows in that projection; protected stored bytes are retained. This is a focused
record-admission correction, not a universal database-corruption repair claim.

## Qualification

Synthetic native controls cover a first cached card without a profile read,
cross-platform streaming, exact enabled ownership, expiry, actual payload size,
malformed cache/saved rows, query planning and a purge interrupted while its media
lock is held. Existing Tor, cancellation, method-order and saved-reader checks
remain part of qualification. Dated live-source and final release totals are in
[release qualification](RELEASE_QUALIFICATION.md).

Native screenshots use synthetic account fixtures; captured reader inputs remain
in the private external build area. Emulator operation timings are observations
of those fixtures, not physical-phone percentiles or battery measurements.
