# Cached reading during refresh

The feed can read the last complete cached window while subscriptions are being
checked. A slow or unavailable source no longer hides that cache from someone
opening the app or changing feed scope. This does not reveal partial cross-post
batches: new material is still published as one completed reader generation, with
the existing reading anchor and deliberate incoming-post adoption.

## Collection and publication

Network acquisition runs through NetworkGateway, outside the reader database's
transaction. Each completed source window is streamed into private disposable
scratch. The shared allowance is the smallest of 64 MiB, one quarter of the
configured ordinary cache, and one quarter of available space above a 256 MiB
reserve. A single source may use at most 32 MiB. The existing catch-up page/post
bounds remain in force. No complete multi-source refresh is retained in memory.

After the source tasks end, one Room transaction reads and verifies one scratch
page at a time and publishes their post records and subscription memberships.
Only this local publication briefly gates new reader snapshots. The previously
rendered feed stays on screen. Source checks become successful only after that
transaction commits; unavailable or incomplete subscriptions remain pending.
Cache maintenance runs after the completed generation has been released.

A source deadline (45 seconds direct, 90 seconds with Tor) now belongs to the
catch-up collector. If a later cursor stalls, completed pages survive and enter
the final publication with a pending reason. The unfinished read is cancelled;
no extra request is made and no incomplete gap becomes a successful refresh.
If the first page never arrives, the source fails normally. Retrying still targets
the persisted original frontier, even though newer partial pages are now cached.

[Feed coverage](FEED_COVERAGE.md) keeps finite RSS/Atom relay windows pending
when they miss that frontier. Ordinary feeds can follow declared, source-bound
history links within the same catch-up budget; initial or overlapping windows
do not crawl older pages.

Cancelling a refresh retains source windows that finished before cancellation,
while interrupted sources remain pending. A failed database transaction rolls
back its whole batch. If scratch cannot be prepared, acquisition does not start;
the failed attempt still completes its generation so a manual retry remains
possible. Collection, serialization and storage run on the IO dispatcher even
when refresh was invoked from the UI thread.

## Disposable files

Scratch uses generated filenames, aggregate/per-source quotas, cancellation
checks, strict bounded-depth JSON and an exact SHA-256 check before publication.
Files are removed when the batch closes. Startup and ordinary cleanup remove
inactive orphan batches left by process death. Cleanup skips active batches and
never traverses unrelated directories or removes saved records/offline downloads.
The work adds no native library, model, background polling or extra route request.

## Qualification

A gated native regression failed on the former implementation because a cold
collector waited for a blocked network source. The candidate returned the complete
old Room window in 13 ms on the final run while that source remained blocked, then published and
merged the finished batch without moving the reader's 17-pixel anchor. This is a
cache-collector/UI measurement after repository initialization, not an end-to-end
process-start or physical-phone benchmark.

Regression coverage includes concurrent scratch quotas, cancellation, altered
scratch, unavailable storage, failed atomic publication, preserved saved material,
source pending/success state, UI-thread invocation and scoped/global generations.
Full test and native-screen results are recorded in
[release qualification](RELEASE_QUALIFICATION.md). Raw measurements and private
preview backups remain on the external build volume.
