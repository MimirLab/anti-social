# Public-index search continuation, 0.38.10

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

The continuation now marks unfinished work as deferred. The next call tries
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

## Controlled evidence

The same new native checks ran against retained 0.38.9 and the candidate. Both
substantive assertions failed on the retained APK: a Reel was displaced by
another fast page, and slower query shapes exhausted their allowance unseen.

| Native three-query fixture | Retained 0.38.9 | Candidate |
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
and legacy plans. [Native checks](../app/src/androidTest/kotlin/app/antisocial/android/SearchFeedTest.kt)
exercise the ordinary search screen and Gateway/factory. See
[release qualification](RELEASE_QUALIFICATION.md) for final scope and counts.
