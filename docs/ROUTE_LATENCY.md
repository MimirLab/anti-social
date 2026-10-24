# Public feed head route measurements

On October 24, 2026, an explicit audit used the same bundled Darwin routes,
`public-pathways.json`, NetworkGateway and compiled protection rules as Repository.
Each target used a fresh in-memory SourcePool, followed by a second read retaining
its route learning. No reader database, preferences or cached feed frontier was
used. These are head reads, not complete historical catch-up or physical-phone
benchmarks. OS, DNS and server caches were not cleared.

| Public target | Fresh pool | Request starts | Learned pool | Request starts | Selected route |
| --- | ---: | ---: | ---: | ---: | --- |
| X / NASA | 1,429 ms | 3 | 181 ms | 1 | Sotwe |
| Instagram / nasa | 2,295 ms | 4 | 213 ms | 1 | RSS-Bridge / rss.bloat.cat |
| Bluesky / bsky.app | 639 ms | 2 | 143 ms | 1 | FxEmbed |
| Telegram / telegram | 2,008 ms | 3 | 298 ms | 1 | RSS-Bridge |
| Reddit / r/technology | 4,638 ms | 5 | 2,251 ms | 5 | safereddit.com |

Every selected head contained complete dated native records. This does not prove
that a route supplied every recent post or could bridge an older reading window.
Request starts include redirected requests and cancelled hedges; completed text
bytes exclude partial transfers and other media. Upstream outcomes vary over time.
These observations are not a before/after performance claim for the deadline fix.

An initial audit accidentally used SourcePool's empty default PublicPathways.
Its Instagram failure and donor-only latency figures do **not** describe the full
application. That measurement was rejected; the table above uses the corrected
complete-pool run. Separate frontend checks found image grids without timestamps;
those remain insufficient to certify a successful chronological feed refresh.

Reproduce explicitly (this never runs as part of the ordinary unit suite):

```sh
scripts/gradle.sh :core:routeLatencyAudit \
  -PantisocialRouteLatencyEvidence=/path/on/external/build/volume
```

Optional `antisocialRouteLatencyTargets` is a comma-separated list of supported
public profile/community URLs. Per-target JSON records include selected sources,
usable dated counts, pending reasons, probes and timings. A source returning 200,
an empty frontend, an excerpt, or an unbridged shallow window is not a completeness
guarantee. Tor and physical-phone performance need separate qualification.

## 0.38.8 requested scheduling follow-up

The earlier table predates [the scheduling change](ROUTE_SCHEDULING.md). Further
October 24 checks used the complete production Gateway/protection pool:

| Public target | Pool/read | Elapsed | Request starts | Usable dated posts | Selected route |
| --- | --- | ---: | ---: | ---: | --- |
| Facebook / NASA, before change | Fresh | 5,085 ms | 7 | 3 | Public original |
| Facebook / NASA, before change | Learned | 770 ms | 1 | 3 | Public original |
| TikTok / tiktok, before change | Fresh | 14,242 ms | 33 | 10 | RSSHub / rsshub.ktachibana.party |
| TikTok / tiktok, before change | Learned | 9,082 ms | 3 | 10 | RSSHub / rsshub.woodland.cafe |
| TikTok / tiktok, candidate | Fresh, one read | 4,361 ms | 12 | 10 | RSSHub / rsshub.woodland.cafe |

The first learned TikTok route returned a rate limit, with a 864,000 ms retry
delay, before another relay supplied the feed. The later single candidate read
was made after that delay elapsed. These are separated observations with
different winning hosts, DNS/server caches and possible upstream changes;
they do not establish a controlled percentage speedup. Controlled JVM and native
fixtures independently reproduce the mirror sweep and stalled-batch boundary.
Ten dated roots without a cursor do not establish complete account history.

Use `-PantisocialRouteLatencyReads=1` for a single fresh-pool diagnostic instead
of immediately repeating the head read. The default remains the fresh/learned
pair. Other values are rejected before constructing the transport. A new audit
pool does not restore the app's learned cooldowns: preserve and honor any retry
instructions from earlier diagnostics before starting another run.
