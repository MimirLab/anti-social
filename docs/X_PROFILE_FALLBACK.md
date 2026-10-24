# Logged-out X profile fallback, 0.38.3

An allowed original X profile page can now supply native posts when alternate
routes fail. The reader decodes the public timeline embedded in that same HTML
response. It does not execute site scripts, load a browser, use a social account,
or fetch a second copy of the page. Existing alternate-first ordering and
NetworkGateway protections remain in force.

The discovery lead was [XProfiles for FreshRSS](https://github.com/shcheglovnd/FreshRSS-xExtension-XProfiles),
a small MIT project. The native implementation has separate origin, identity,
resource and feed-coverage checks; the upstream notice is retained in the APK.

## Useful content and coverage

The reader retains owned, dated timeline entries, full long-post text where
supplied, expanded link destinations, photo galleries, available video variants,
link-card details and an attributed quoted post. Quoted and recommended records
cannot become unrelated feed cards. Private authors, tombstones, malformed IDs,
missing/fractional timestamps and wrong-account roots are rejected. Known
excerpts remain previews and cannot replace a full cached feed record. Missing
media or further nested quotes carry incomplete extraction coverage.

Observed public windows contain about five originals, including a pinned item.
This is not history, search, replies, repost enumeration or a comments route.
No cursor is guessed. When a limited window cannot reach the previously cached
head, available posts can be retained but the subscription stays pending. A
matched head or first subscription can complete normally. An old pinned post
does not stand in for the previous newest head.

## Bounded data decoding

Only a recognized GraphQL completion wrapper is read. The literal reader accepts
objects, arrays, scalars and bounded completed data references. Calls, functions,
getters, constructors, property access and expressions are rejected. A JSON
string is decoded only inside a `Result.Ok.value` transport envelope; a caption
containing JSON remains ordinary caption text.

Candidate decoding is limited to eight messages and 1,048,576 input characters,
with 80,000 nodes, depth 64 and 4,096 labelled references. Unrelated oversized
messages are skipped. Timeline traversal has its own depth/work limits and
identity-based visitation so shared references cannot multiply traversal work.
Captions, media lists and output records are bounded too. NetworkGateway and
HTML admission retain their independent response and structure limits.

## Feed routing consistency

An audit regression confirmed that the routing pool could accept a dated preview
as healthy feed content, beating a complete dated backup. Feed delivery and
probes now use the same readable-feed eligibility as refresh. A date alone is
insufficient: previews, cached copies and public-index-only records cannot train
a readable chronological feed route. Ordinary post/profile/search eligibility
remains specific to those capabilities.

## Dated qualification

On October 24, 2026, actual Android gateway checks retrieved five owned, dated
posts each from NASA and SpaceX using one profile request per account. The reads
took approximately 1,646 ms / 536 ms and 191,290 / 182,240 bytes. Selecting
unavailable Tor or disabling original-site access produced no network start.
These are individual source checks, not an uptime or phone-performance guarantee.
Final checks and native inspection are recorded in
[release qualification](RELEASE_QUALIFICATION.md).
