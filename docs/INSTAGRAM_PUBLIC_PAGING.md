# Instagram public paging, October 24, 2026

The later [0.38.21 public-comment route](INSTAGRAM_PUBLIC_COMMENTS.md) adds
separately qualified comment pagination. The observations below describe the
original profile/post/media increment.

The additional `instagram-public-graphql` route reads public profile-grid pages
and selected public posts through NetworkGateway. It runs in ordinary builds,
without the experimental TLS library, a browser, account cookies or site scripts.
Darwin-derived alternate readers still precede original-site access. Disabling
original-site fallback excludes this route; selected Tor continues to fail closed.

## Observed additional access

On an owned Android API 35 emulator, the integrated reader obtained **36 distinct
NASA grid posts across three twelve-item pages**, using the source's issued
`after` cursors. The second and third windows supplied 24 additional post IDs.
The first integrated read took 1,409 ms, then 564 and 474 ms for continuations.
A selected post returned a source publication timestamp and playable video;
another returned three distinct gallery images. These are dated observations,
not a promise of unlimited history, quota renewal or universal login bypass.
Physical-phone performance and a live Tor exit are not qualified by this test.

The simpler profile-URL `?cursor=` attempt repeated the initial window, including
with the experimental native transport. A separate public-App-ID request to
`/api/v1/users/web_profile_info/` returned 401. Neither is reported as a working
pagination route. The useful addition is the anonymous readonly Relay POST.

## Capability boundaries

| Operation | Behavior |
| --- | --- |
| Profile grid | Captions and image/video/gallery previews, with actual source-author attribution. Other authors on the selected grid are not relabeled as the selected account. |
| Profile continuation | Exact selected connection only; source, target and issuing connection are bound to an expiring local opaque cursor. |
| Selected post | Both outer and inner shortcode/PK identities must match. Only `if_not_gated_logged_out` supplies the selected body and media. |
| Full media | Supplied video variants and gallery children are retained. Larger image candidates remain separate from display previews. Missing or bounded-out media stays partial. |
| Publication time | Only actual `taken_at` is used. Grid rows omit it; no date is inferred from IDs, accessibility prose or retrieval time. |
| Relationships | Explicit coauthors and tagged accounts feed existing discovery. Grid membership alone does not establish collaboration or common account control. |
| Feed freshness | No FEED capability. Undated grid previews cannot advance a successful-refresh or caught-up boundary. |
| Profile metadata, global search, follower graphs and comment pagination | No new capability advertised. Nested recommendations/comments are not harvested as extra selected posts. |

Each ordinary page is one bounded request. A recognized changed-query error can
trigger one public HTML bootstrap and one retry. Only an actor-zero declaration
for the exact readonly query and selected username/media ID is accepted; a small
whitelist of boolean Relay-provider variables is retained. Login, challenge,
rate-limit and transport failures do not cause protocol retries or identity
rotation. HTTP 200 with GraphQL errors is not healthy content.

The later [0.38.25 recovery bank](INSTAGRAM_QUERY_RECOVERY.md) recognizes a missing
requested document and reuses a successfully validated replacement for the exact
source, target, operation and connection. Each fresh read still needs a new
protected response; no cached query metadata can certify content or freshness.

The initial query IDs are verified protocol identifiers, not credentials. The
public application identifier and per-request matching LSD nonce supply the
anonymous request shape. Responses have a 4 MiB allowance and strict depth-bound
JSON parsing. HTML bootstrap has structural admission and a traversal budget.
The cursor bank retains at most 32 entries for ten minutes, in memory only;
forged/expired cursors, another target/source, restart, or direct/Tor/direct
round trips require a fresh head. Repeated IDs, changed owners or cursor loops cannot
silently become new pages. Failed requests leave an issued cursor retryable.

## Source research and checks

The small [research-mcp Instagram reader](https://github.com/vvzvlad/research-mcp/blob/main/src/providers/instagram.py)
provided the anonymous POST and paging lead; its MIT notice is retained in
`app/src/main/assets/public-pathways-notices.txt`. The Kotlin reader is independent
of its Python/MCP and optional cloud components. In particular, its ID-derived
date estimate was not imported. FxEmbed supplied a second protocol reference.

Sixteen new JVM tests exercise real Gateway requests, issued cursors, actual dates,
complete/partial media, collaborators, exact identity, refusal/error handling,
changed-query bootstrap, malformed/deep input, expiry, bounded eviction,
stalled/repeated pages, retry and privacy-session changes. The carousel preview
fix adds one regression to the existing literal-JSON parser suite. The full
JVM run passed 2,130 cases with zero failures/errors and one optional skip.
The Android batch ran 18 named checks: 12 passed, six opt-in native/live-provider
experiments skipped. Four explicit public paging/media checks ran successfully. The native NASA
gallery screen was inspected after opening the post URL through the reader
search sheet; this is separate from a browser intent handoff.

Raw public responses, APK/source hashes, private state backups and measurements
are retained separately under external `evidence/public-preview-paging-20261024`.
Private state and raw captures are not repository or release assets. The native
experiment's separate qualification remains in
[PUBLIC_PREVIEW_QUALIFICATION.md](PUBLIC_PREVIEW_QUALIFICATION.md).

The ordinary-profile integration in 0.38.19 can offer this route after a fast
finite alternate snapshot. See [on-demand history and cursor ownership](PROFILE_HISTORY.md).
