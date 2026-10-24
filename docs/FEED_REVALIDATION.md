# Feed revalidation and parser reuse

Ordinary RSS/Atom subscriptions and the main social syndication adapters share
[SyndicationReader](../core/src/main/kotlin/app/antisocial/core/SyndicationReader.kt)
through NetworkGateway. This covers external feeds, frontend/original RSS,
RSSHub/RSS-Bridge, Mastodon RSS, Pixelfed Atom, the protocol-feed adapters and
Telegram RSS. Substack publication feeds use the ordinary RSS path. Public search
indexes and historical archive readers retain their own contracts.

Every refresh still contacts its source through the Gateway. There is no new
polling, subscription schedule, model call or offline success fallback. A failed
network read remains a failed refresh; retained content cannot make an unavailable
subscription appear checked. FeedCatchUp still owns historical-window coverage.
An unsolicited partial HTTP 206 cannot certify a complete feed refresh.

## Reuse only after a network response

After a readable feed passes its existing parser and source/identity/date checks,
the reader can retain its parsed Page and a SHA-256 body fingerprint. The key
includes the exact requested URI, source and role, selected target, response-size
allowance and supported request headers. Matching words or a shared hostname
cannot join different accounts, feeds or language variants.

On a direct connection, the next read can send the source's ETag or Last-Modified
for the exact HTTP representation retained in that same Gateway session. A 304
requires that approved record, the exact final URI and proof that the final
request actually carried validators. Conflicting returned validators are rejected
and their binding discarded so a later read can recover unconditionally. The
Gateway strips validators on redirects and filter/cleaner rewrites; they cannot
authorize an unchanged response from a different destination.

A valid 304 needs no XML extraction. A new 200 with the same fingerprint, final
URI and content type can also reuse the parsed Page after receiving its body.
Changed content runs the complete original parser and identity checks. A bad
replacement cannot hide behind the previous page. Publication times, original
links, attachments, attribution, coverage flags and continuation ownership stay
with the source records; observation times describe the new successful check.

This adapts the existing [article-validation privacy rules](ARTICLE_VALIDATION.md).
HTTP validation is specified in [RFC 9111 §4.3](https://www.rfc-editor.org/rfc/rfc9111.html#section-4.3).
It is a source confirmation of a retained representation, rather than evidence
that the source exposes complete account history.

For [Nitter RSS header navigation](FEED_COVERAGE.md#rss-header-navigation-03817),
the reusable Page represents parsed XML; derived `Min-Id` navigation is a separate
bounded entry field. A new identical 200 body cannot replay an old response's
cursor when its current header changed or disappeared. An exact validated 304
can retain an omitted header, while an ambiguous supplied header clears it.
Navigation bytes count toward the same memory budget. No-store responses remain
readable without retaining either their parsed Page or header metadata.

## Tor and retention

Tor reads neither retain nor replay the source's validators. Opaque entity tags
can identify a client across circuits, which can change without a local port or
policy change; see [RFC 9110 §17.14](https://www.rfc-editor.org/rfc/rfc9110.html#section-17.14).
The full body is fetched on each Tor refresh. Exact-body matching can still save
local parsing after that current network response. The body fingerprint is never
sent to a provider.

The cache is memory-only: at most **32 records** within a **4 MiB accounting
budget** for retained parsed data and metadata. It adds no downloaded XML files,
database tables or export fields. Records expire **30 minutes from the full read**;
a 304 does not renew that lifetime. A new 200 is a new full read. No-store and
unsupported Vary dimensions prevent cross-request retention. Oversize entries
remain readable but are not retained by this cache.

A process restart, expiry, eviction or connection-session change requires a new
full read before validator reuse. Clearing ordinary cache clears this memory too,
without deleting saved copies. Generations and operation order prevent obsolete
in-flight work from repopulating cleared memory or displacing newer bindings.
All Gateway filters, request/byte budgets, credential stripping, cancellation and
selected-Tor fail-closed behavior remain in force.

## Dated public observation

An explicit October 24, 2026 check used the production Gateway and bundled
compiled protection against `https://www.nasa.gov/feed/`. The first read returned
HTTP 200, **249,777 decoded body bytes** and ten feed posts. The next returned
HTTP 304 with **zero body bytes**, retained all ten posts and made no additional
parser call. Its recorded elapsed times were 2,947 ms and 30 ms.

Those are successive direct reads with warm connection/server effects, not a
controlled before/after speedup, wire-byte total, physical-phone benchmark or
live-Tor qualification. Source behavior can change. Reproduce explicitly with
a new external evidence directory:

```sh
scripts/gradle.sh :core:feedRevalidationAudit \
  -PantisocialFeedEvidence=/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/new-feed-audit
```

Optional `antisocialFeedUrls` accepts at most three public HTTPS URLs separated
by `|`. Each gets at most two reads; a failure or refusal stops that source's
pair rather than immediately retrying it. Normal tests use owned fixtures.
The audit writes response counts, decoded sizes, parse counts and timings rather
than publisher article bodies. [Release qualification](RELEASE_QUALIFICATION.md)
records the controlled/native checks for the increment.
