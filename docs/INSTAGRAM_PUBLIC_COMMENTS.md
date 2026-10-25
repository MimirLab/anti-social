# Public Instagram comments, 0.38.21

Instagram posts now have an additional anonymous comment route through the
existing `instagram-public-graphql` reader. It supplies ordinary native
discussions and the same retrieved comment material can enter Context. No AI,
social account, key, browser, site script or extra native runtime is required.
Original-site access must be enabled; selected Tor still fails closed. Darwin's
alternate-before-original routing and the learned source pauses remain intact.

[0.38.25 query recovery and reuse](INSTAGRAM_QUERY_RECOVERY.md) extends the
recognized protocol failures and retains successfully validated replacement
metadata for bounded reuse. It adds no child-reply enumeration claim.

## Source research and observed access

The zero-star [instapick project](https://github.com/andresayac/instapick) supplied
a useful discovery lead: public comments have a separate pagination operation.
Its request shapes informed the independently implemented Kotlin reader. The current
[FxEmbed client](https://github.com/FxEmbed/FxEmbed/blob/main/packages/atmosphere/src/providers/instagram/client.ts)
and [comment processor](https://github.com/FxEmbed/FxEmbed/blob/main/packages/atmosphere/src/providers/instagram/processor.ts)
provided additional protocol and permalink references. Existing FxEmbed notices
are retained. The app uses its own Kotlin implementation and shared Gateway.

An API 35 Android Gateway probe on October 25, 2026 read 15 public comments on
the selected NASA post, then 13 new comments using the issued cursor. The first
query took 1,278 ms in that run. A separate native reader check loaded 15 comments
in **All**, pressed the actual **More Instagram replies** button, and reached
27 distinct comments. Its rendered discussion was inspected. These are dated,
single-post observations, not a promise of complete history or universal access.

The readonly POST uses `/api/graphql`, operation
`PolarisPostCommentsPaginationQuery`, observed document ID `25516980651312394`,
the exact selected media ID, `first: 12`, `sort_order: recent`, null `before` and
`last`, and `__relay_internal__pv__PolarisIsLoggedInrelayprovider: false`.
The observed server can return 15 rows despite requesting 12. Matching anonymous
LSD form/header values and the public application identifier follow the existing
reader. No login cookies or account authorization are sent.

## Extraction and continuation

Only `data.xdt_api__v1__media__media_id__comments__connection` supplies comments.
Recommendations or connections buried in another object cannot contribute rows.
The selected operation/request establishes the post scope; an explicit conflicting
media ID in a returned row is rejected. The response does not independently
repeat the selected media ID on every row. The exact endpoint/origin and actual
connection session remain pinned by the Gateway and existing envelope checks.

Literal `XDTCommentDict` rows retain source comment IDs, text, actual
`created_at`, parent references, supplied like/reply counts and public avatars.
Missing dates stay unknown. A supplied null parent identifies a top-level
comment; an omitted/unusable parent does not silently become the post root.
Useful text can remain readable when the account is unavailable. Supplied public
profile names enter the existing bounded local name bank through `Page.accounts`;
a username alone is not promoted into a proper name.

Comment permalinks use the original `/p/SHORTCODE/c/COMMENT_ID/` form. Content
identity also recognizes the equivalent `comment_id` query form. Separate replies
therefore survive discussion collection, copies, local storage and Context
instead of collapsing into the parent post's identity. No saved-format field or
database schema is added.

The existing 32-entry, ten-minute, memory-only cursor bank binds continuations to
their source, selected target, operation and actual direct/Tor session. Raw
provider cursors are not caller-supplied query templates or navigation URLs.
Expiry, mode changes, forgery, repeated pages and cursor loops stop; failures
leave an issued cursor retryable. Valid new comments can still be delivered when
the provider's next cursor stalls. Parsing is bounded to 4 MiB, strict JSON depth,
64 edges and existing per-field limits. Opaque JSON cursors retain ordinary spaces
while control characters and excessive lengths are rejected.

Only a recognized query-protocol change permits one literal public-page
bootstrap and one retry. Metadata must identify actor zero, the exact comment
operation and selected media ID. The logged-in Relay flag stays false even if
metadata suggests otherwise. Auth, challenge and quota refusals do not trigger
query retries or identity rotation.

## Coverage

This adds recent public comment pages, not a follower graph, global search,
complete child-reply enumeration or a popularity-ranked query. Parent/reply
information is retained when supplied, and missing child replies stay incomplete.
The source notice and existing incomplete-discussion presentation remain visible.
Both ordinary readers and Context keep their existing request/work budgets.
Cached comment progress cannot make undated profile previews into fresh feed data.

The unchanged production baseline could not consume the comment connection:
its generic comment call used selected-post extraction and failed with “no
selected post.” Owned Gateway regressions cover the new connection, cursor and
parent/identity contracts, missing fields, foreign/nested data, refusals,
permissions, transport changes and exact operation bootstrap. Public-source
observations and native qualification remain separate from physical-phone and
live-Tor measurements. Raw evidence stays under external
`evidence/public-comments-20261025`, outside release assets.

## Regression qualification

The final JVM run completed 2,153 cases with zero failures/errors and one optional
model-weight skip. The final API 35 native selection passed 53 methods with one
opt-in live-Tor skip and zero failures. It covers actual Instagram discussion and
history buttons, feed coverage/history, all nine subscription journeys, public-index
pause handling, connection controls, 23 name-store checks and seven Context
progress/noise/parent-persistence checks. Native discussion and profile screens
were inspected. Debug, instrumentation and release assembly and release lint pass.

The first 54-method native batch had one disabled-button assertion failure in
the existing stalled-discovery subscription test. The unchanged case passed in
isolation, the previous 0.38.20 release passed all nine subscription methods,
and the unchanged final 54-method batch passed with the skip above. This does not
establish the transient failure's cause; its log and comparisons are retained.
No subscription production code or assertion was changed to obtain a pass.

## Source account bindings, 0.38.22

Public grid previews, selected posts and comments now retain the literal
Instagram username alongside their display label. A public name is not an
identifier: two commenters may use the same name while belonging to different
accounts. The optional `authorAccount` carries the original profile URL and,
when supplied, its positive decimal account ID. It is a source declaration,
not independent verification of common control across platforms.

The native author link, existing local-name cache and bounded local participant
graph reuse this binding. Selected-post responses also contribute their already
present public name to the existing name bank. Body mentions retain their own
identities. No new acquisition request, global graph crawler or dependency is
added. Existing optional name lookups keep their normal controls.

Saved copies, captured replies and archive snapshots retain the binding. Older
records without it remain readable and unknown; an old snapshot never inherits
a current account identity it did not capture. Imports require an original
Instagram HTTPS profile path, typed optional ID and matching platform. Mutable
handle observations do not become cross-platform ownership proofs.
