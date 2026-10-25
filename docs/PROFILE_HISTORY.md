# Profile history without slowing the first read, 0.38.19

A fast finite snapshot should not hide a qualified route that can supply more
public posts. Ordinary Instagram profile browsing can now continue from the
winning alternate reader into its public paged reader using the existing
**Load more** button. Initial delivery makes no extra history request.
Darwin remains the routing donor.

## Observed user journey

On October 25, 2026 UTC, the ordinary API 35 routing pool returned six dated NASA
posts from RSS-Bridge in 1,852 ms. The first continuation added six distinct posts
through Instagram's public query in 1,067 ms; the next added twelve in 479 ms.
The collected page therefore grew **6 → 12 → 24 unique posts**. Each continuation
stayed with its issuer. This run is not the previous direct-adapter experiment
that collected 36 posts over three API windows.

A separate native test opened the profile through Repository/ViewModel and
pressed the real Load more button twice. Its native grid also grew 6 → 12 → 24;
the screen was inspected. These are dated direct-transport observations for one
public account, not unlimited history, every-account uptime, live Tor or
physical-phone latency qualification.

## Contracts

1. `SourcePool.delivered` can offer a pure local `DataAdapter.historyCursor`
   after a fresh, finite profile snapshot has no continuation. It checks the
   ordinary registry, capabilities, cooldown and original-access policy. No
   probe, second head, model call or background fetch is added to initial delivery.
2. `Page.continuationSource` identifies the navigation issuer separately from
   acquisition provenance. Existing cards retain their original text, media and
   provenance. The ordinary page cache stores this data-only navigation hint.
   No callback or networking object is serialized.
3. `SourcePool.continuePage` validates the selected issuer against its registry,
   operation, original permission, source pause and current connection. Returned
   cursors remain pinned to the adapter that issued them, including after a
   response's acquisition origin changes. They are never borrowed by a different
   adapter simply because the visible page began elsewhere.
4. The Instagram reader keeps its bounded, session-bound opaque cursor bank.
   It skips up to three overlapping windows per click. Duplicate-only checkpoints
   are retained for retry, with monotonic progress and the original expiry.
   Undelivered new posts are never skipped. Rate limits and failures retain the
   normal routing pause; mode changes and expiry require a fresh profile read.
5. If three validated windows contain only known posts but genuinely advance,
   `continuationProgress` permits another bounded click. No fake post is emitted
   and no empty result is certified as healthy new content. Repeated IDs/cursors,
   a changed owner or a nonadvancing checkpoint still stop.
6. `PageContinuation.merge` retains existing cards and carries the next issuer,
   checkpoint and completeness state forward. An ordinary duplicate page without
   validated progress still stops. Cache restoration does not make old cards
   fresh: `FeedRefresh` continues to reject cached and undated preview material.

Only the qualified Instagram paged adapter currently offers this history
handoff. Other adapters have a no-op default. Saved/archive-only pages do not
start it. FEED reads and operation-local `FeedHistoryPlan`/`FeedHistoryScope`
recovery are excluded: subscription catch-up retains its separate dated-window
requirements. This feature does not give the undated grid FEED capability.

## Validation

The new first-read regression failed on unchanged 0.38.18 after one successful
RSS request: the finite page had no continuation. It passes with the same one
initial request on the candidate. Ten profile-history JVM tests cover overlap,
cache-compatible navigation ownership, duplicate-only progress, retry checkpoints,
source pauses, original opt-out, unknown sources, mode round trips, ordinary
duplicate stopping, monotonic checkpoints and expiry. The full JVM run passed
2,140 cases, zero failures/errors and one optional weight-dependent skip.

The native batch passed fourteen checks with one opt-in live-platform check
skipped. It covered the owned handoff/cache/card journey, ordinary live profile
routing, platform UI, feed revalidation, retained feed-history recovery and Nitter
header pagination. The separate live button check adds one distinct passing
method: **15 passed, one skipped, zero failures** across the two runs.

The first owned native harness expected cached cards to count as two fresh feed
posts. Its zero result correctly exercised the existing cache/freshness guard;
the harness now checks two before caching and zero afterwards. No production
freshness protection was loosened to make that test pass.

Frozen APKs, source/input hashes, baseline and intermediate logs, raw measurements,
private preview backups and native screenshots remain under external
`evidence/profile-history-20261025`. They are separate from public source assets.
See [the public-query route](INSTAGRAM_PUBLIC_PAGING.md) for its protocol and
media-completeness boundaries.

Version 0.38.20 also [preserves source pauses during continuation](CONTINUATION_PAUSES.md),
including ordinary same-reader pages and cached cross-reader navigation.
