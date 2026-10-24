# Public-index search continuation, 0.38.11

Unscoped Instagram, TikTok and Facebook search can ask the selected public index several
different questions concurrently: ordinary posts, Reels/photos, and a broad
query that can also find accounts. These are discovery destinations; their index
snippets never become fabricated social posts. Existing readers hydrate the
actual destinations into native cards.

## Fast first results, then unfinished work

The first call still starts the distinct query shapes together. Once a post
destination arrives, it gives companions the existing short grace interval and
returns useful results. Account-only answers still wait for post queries.

Previously, a saved continuation restarted unfinished queries alongside each new
page from the fast winner. A slow query could be cancelled after every grace
interval until its four attempts were spent. Concurrency existed, but some query
shapes never got enough uninterrupted time to answer.

Since 0.38.10, the continuation marks unfinished work as deferred. The next call tries
those queries before requesting another page from the winner. Completed queries'
real next cursors remain parked without spending an attempt. If several deferred
queries have different speeds, each completed query leaves the priority group;
the last unfinished one can eventually run alone. If the priority group fails,
validated parked continuations can still run. A provider challenge, login demand
or rate limit ends the provider's plan before sending a parked request.

The caller still owns cancellation. There is no detached query registry,
background keep-alive, new provider or model. The 200 ms companion grace,
10-second per-attempt deadline, four-attempt per-query allowance, 24 KiB cursor
bound and outer caller/source budgets remain. A continuation can spend more of
its permitted time obtaining a previously missing answer; it no longer spends
every turn fetching another fast page instead. Each planned query starts at most
once per call, with at most three query operations active.

## Stream useful pages while an unfinished query runs

The ordinary search feed now receives progress from its **selected source's
continuation**. Initial source races do not publish losing candidates through
this path. Deferred queries start first; if they are still running after 200 ms,
validated parked queries can start alongside them. Each query still starts at
most once in that call. Fast pages can therefore reach native cards while the
slower query gets its uninterrupted turn. A continuation without a progress
consumer retains the earlier page-at-a-time behavior.

Each completed query sends a destination delta and a checkpoint describing the
remaining work. The reader accepts the checkpoint before acknowledging it, then
hydrates the real destinations. Stopping cancels the owning jobs and Gateway
requests. Retrying resumes unfinished queries from that checkpoint; a completed
page already shown need not be fetched again. There is no detached registry or
keep-alive job. All children remain inside the caller's coroutine scope, using
the pinned library's [context and cancellation behavior](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/Builders.common.kt).

Progress does not consume another page allowance or mark a query complete.
Receiving the same checkpoint on the terminal response does not falsely end
pagination. Distinct per-query deltas also preserve destinations beyond the
terminal page's 60-item projection. The shared reader bounds remain: 240
candidates, 200 posts, 40 accounts, 20 completed discovery pages, 1 MiB of text,
two concurrent destination reads and two discovery calls per load. Cursor,
per-query attempt and source time limits are unchanged.

The selected provider, original-access permission and actual source transport
session are checked before later requests and progress delivery. Waiting for
search transport cannot authorize a provider that the reader has since replaced.
These multi-query reads and form retries retain the same privacy session. Unrelated Tor search-readiness
updates leave direct Librey continuations intact. Policy cancellation is not
learned as a source outage; successful routing latency uses the first usable
delivery rather than the time spent waiting for the tail or reader acknowledgment.

A refusal received before the parked hedge prevents those requests. If it arrives
after a parked request started, existing usable results remain available and the
plan ends; a request already sent cannot be undone.

## Retain completed results and validate the whole plan

Parsed results are recorded before their cancellable completion notification.
The collector keeps finished work when it stops waiting and cancels losers.
An atomic selection prefers a ready result over an equally ready grace timer,
using the pinned coroutine library's
[selection semantics](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/selects/Select.kt).
This avoids wrapping channel removal in a separate timeout; the library documents
how [prompt receive cancellation](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/channels/Channel.kt)
can otherwise remove an element without returning it to the caller.

Version 2 plans add a strict Boolean deferred marker; version 1 plans remain
readable and upgrade on their next response. Every query, source, target and
cursor is validated before **any** query starts, including parked queries.
Unknown versions, malformed flags and foreign cursors fail before networking.
HTTPS-only admission for public HTML pagination is unchanged. Routing, filtering,
Tor policy and the selected public search provider are unchanged.

## Controlled 0.38.10 fairness evidence

The same new native checks ran against retained 0.38.9 and the candidate. Both
substantive assertions failed on the retained APK: a Reel was displaced by
another fast page, and slower query shapes exhausted their allowance unseen.

| Native three-query fixture | Retained 0.38.9 | 0.38.10 |
| --- | --- | --- |
| Requests by query shape | 4 / 4 / 4 | 1 / 2 / 3 |
| Total requests | 12 | 6 |
| Distinct requested shapes delivered | 1 | 3 |
| First block, this run | 240 ms | 220 ms |

These are authored source delays and loopback Gateway measurements on an Android
emulator, not public-provider coverage or physical-phone latency claims. The
fixtures stage a source/query-bound saved continuation at their HTTP loopback
boundary; they do not relax production HTTPS pagination admission. Initial
fixture admission and lazy-list navigation errors are kept separately from the
production failure evidence.

[JVM regressions](../core/src/test/kotlin/app/antisocial/core/PublicIndexFairnessTest.kt)
also cover failed priority reads, refusals, cancellation, parked-token validation
and legacy plans. [Reader progress checks](../core/src/test/kotlin/app/antisocial/core/SearchProgressTest.kt)
cover shared limits and checkpoint acceptance. [Native checks](../app/src/androidTest/kotlin/app/antisocial/android/SearchFeedTest.kt)
exercise the ordinary search screen and Gateway/factory. See
[release qualification](RELEASE_QUALIFICATION.md) for final scope and counts.

The 0.38.11 regression holds a deferred Reel read open while a fast post page is
available. The unchanged 0.38.10 reader reaches the held query but does not
deliver that available page within the controlled two-second observation.
The candidate delivers it while the tail remains active, then retains the Reel.
Additional checks cover stopping and retrying only unfinished work, early/late
refusals, provider/privacy changes, source identity, 90 per-query destinations,
and identical partial/terminal checkpoints. These are authored scheduling and
native-reader cases, not measurements of public provider or physical-phone speed.
