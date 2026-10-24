# Route scheduling and source-check admission

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

## Transport readiness and source checks, 0.38.13

Waiting for Tor is local preparation, rather than evidence about a source.
Previously, a source probe held one of the four read permits while waiting,
included that wait in its latency and compared the ready connection with the
disconnected policy captured before startup. A normal startup could therefore
discard a usable result. Four pending Tor checks could also occupy all source
check permits while a direct source was ready to answer. This affected source
diagnostics; it was not a four-permit limit on ordinary feed reading.

Tor-required probes now await transport before acquiring a source-read permit.
The same four permits bound actual probe operations; the existing explicit
sweeps remain capped at 128 sources. All waiting and reading remains in the
caller scope, with the pinned library's
[cancellable permit acquisition and release](https://github.com/Kotlin/kotlinx.coroutines/blob/1.10.2/kotlinx-coroutines-core/common/src/sync/Semaphore.kt).
No new transport, polling job, request allowance or detached work is added.
Cached observations and known provider pauses can end a probe before waiting.

A connection-selection identity distinguishes a reader changing direct/Tor mode
from Tor becoming ready. A mode round trip invalidates queued work even when
the values match again. Once admitted, the probe pins the actual prepared
transport/source sessions, checks them between dependent reads and before
recording results, and retains Gateway's strict request/response guards.
Search-provider and original-access changes also cancel stale queued work.
Warm independent Tor clients and direct content sessions keep their existing
reuse rules.

Initial search paths, directory reads and selected continuations recheck policy
and public-index cooldown after transport waits. Probes recheck after permit
acquisition too. A pause learned while work is queued prevents its request;
skipping it does not produce another source-failure sample or prolong backoff.
Local startup/preparation failure returns a connection diagnostic without
overwriting earlier provider observations or learning a provider outage.
Failures from an admitted source still follow the ordinary health policy.

Probe latency starts after admission and successful local preparation, and its
observation date is recorded at completion. The same timing separation applies
to original-search fallback reads. Startup time remains part of the reader's
overall wait; it is excluded from the claimed source response time.

[Probe regressions](../core/src/test/kotlin/app/antisocial/core/SourceProbeAdmissionTest.kt)
exercise readiness, local failures, permits, cooldown and timing. The
[queued-search regressions](../core/src/test/kotlin/app/antisocial/core/SourceReadAdmissionTest.kt)
cover initial search, directory and continuation admission with all outbound
destinations denied by the fixture filter. Native checks use owned loopback
sources and simulated readiness; they do not establish working live Tor exits
or public-provider latency.
