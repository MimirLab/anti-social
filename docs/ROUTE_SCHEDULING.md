# Requested route scheduling, 0.38.8

A public TikTok feed audit exposed the cost of visiting many mirrors of the same
frontend before trying another reader. A separate controlled case showed that
one stalled request could hold the boundary between three-route batches even
after the other two requests had failed. This changes requested-read scheduling;
it adds no source, polling task, model, transport or setting.

Recent working sources retain their health and latency priority. Within the
unproven tier, the pool tries one source from each frontend before returning to
more mirrors. Each frontend's own source order is retained. The existing
`fallbackOnly` preference orders those first choices instead of postponing an
entire different method until every ordinary mirror has been tried. Stale
observations and unknown sources still need to demonstrate usable content.
Different frontends are not necessarily independent operators or upstreams.
Original-site access and archives remain separate tiers.

Requested ordering snapshots each source's observed tier and score once. Other
reads can update shared learning without changing a comparator halfway through
sorting. Heartbeat plan descriptions use the same ranking. The automatic
maintenance shortlist and its polling limits are unchanged.

The scheduler holds at most three candidate adapter operations and three queued
completion messages per group. It creates jobs only when they can run, rather
than creating a waiting coroutine for every imported source. A new attempt starts
after its predecessor's learned stall interval, or immediately when the latest
attempt finishes without usable content. Failure does not broadcast to every
backup. A free slot can reach the next candidate while an older request remains
stalled. All remaining paths stay available within the existing group deadlines.
An adapter can make its own bounded subrequests; this is not a global limit of
three HTTP requests across the whole app.

Completed results and hedge timers are selected atomically. A buffered result
gets priority over a simultaneously ready timer, avoiding an unnecessary backup
beside a winner. The implementation uses the pinned coroutine library's
[biased selection](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/selects/Select.kt)
and [timeout clause](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/selects/OnTimeout.kt).
The timeout clause requires an explicit experimental API opt-in; library upgrades
must retain native scheduling qualification.

Eligibility is checked again before each adapter operation. A source that enters
cooldown while waiting receives no request. Winning and caller cancellation close
losing Gateway calls without training an outage. Owned request timeouts and real
source failures still update health. All requests retain NetworkGateway rules,
aggregate budgets, original-access controls and Tor's fail-closed behavior.
Empty pages, previews and undated posts cannot become successful chronological
feed refreshes. Pagination continues through the selected adapter's existing
cursor contract; this does not interchange cursors between frontends.

The dated public observations and their limits are in [route measurements](ROUTE_LATENCY.md).
The controlled negative/positive checks, native qualification and release scope
are in [release qualification](RELEASE_QUALIFICATION.md).
