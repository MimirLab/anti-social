# Feed windows and declared history routes

A fresh HTTP response and a joined reading window are separate facts. A relay
can supply dated posts while exposing only its most recent twenty items. If
that window misses the subscription's retained previous head, the app keeps the
useful new posts while leaving the missing interval visibly pending.

## Finite windows

Generic social RSS, RSSHub/RSS-Bridge, protocol syndication backups, Mastodon
RSS, Pixelfed Atom and Telegram RSS retain `limitedTimeline` through their
source-specific filtering and Page reconstruction. An absent cursor cannot clear
an unbridged interval. First subscriptions still take one window; overlapping
heads finish without requesting unrelated history.

Ordinary RSS/Atom is finite by default too. A namespaced, feed-level `fh:complete`
can explicitly describe the logical feed's full current set, provided parsing
did not truncate its item count and there is no conflicting history link. Nested
item fields and unrelated namespaces cannot make that claim. This is a publisher
declaration about its feed, not verified completeness of its website or social
account. It does not delete local history or saved material.

Namespace lookup preserves XML prefix/attribute case and empty undeclarations;
HTML-style case folding cannot authorize a complete-feed marker. The supported
marker must be empty. Valid uppercase prefixes remain supported.

[RFC 5005](https://www.rfc-editor.org/rfc/rfc5005.html) distinguishes feed documents
from logical feeds and defines explicit complete feeds, paging and archives.
Its history namespace is `http://purl.org/syndication/history/1.0`; the XML prefix
itself is arbitrary.

## Follow the published path

[RssSources](../core/src/main/kotlin/app/antisocial/core/RssSources.kt) preserves
feed-level Atom `next` links, or `prev-archive` when no `next` is declared. RSS
can carry these Atom-namespaced links too. Item links and unrelated namespaces
are not navigation. Relative references honor `xml:base`; `?page=2` on
`/feed.xml` retains the filename rather than becoming `/?page=2`.

Continuation preserves the logical subscription target and GUID-based fallback
entry identities. The fetched URL remains separate provenance. The previous
Page must identify the same subscription, carry current ordinary-feed provenance
and supply the continuation. Cached/saved pages and other subscriptions cannot
authorize it.

Ordinary navigation stays on the last fetched HTTPS origin, including its port. A first
read can follow a legitimate migration through Gateway; later pages cannot
roam across new hosts. Gateway pins their redirects, applies supplemental
action-link policy, filters and privacy-session checks, then parses the returned
feed. An off-origin or incomplete response cannot finish catch-up. Known account
deletion, reset and unsubscribe paths wait for deliberate opening; articles and
guides about those subjects remain readable.

The existing bounds remain **eight pages, 300 posts, 45 seconds direct or 90
seconds with app-wide Tor**. Extra pages are requested only when the retained
head is missing and the publisher supplies a route. Initial and already joined
windows make no history requests. There is no new polling job, setting, model,
runtime or full-archive crawl. Every request uses NetworkGateway, selected Tor
fails closed, and completed pages survive an unfinished later read under the
[publication contract](FEED_REFRESH.md).

Initial heads retain [revalidation](FEED_REVALIDATION.md). Ordinary continuation
pages currently perform full protected reads. A source without usable history
remains pending across a gap; the app does not guess pagination parameters.

## Blogger's published feed route

A real custom-domain Blogger feed exposed an additional path: its Atom `next`
link pointed to `http://www.blogger.com/feeds/ID/posts/default`, and the same
numeric blog ID appeared in its namespaced Atom feed identifier. The HTTPS
equivalent supplied public feed pages with that same identifier. Google's
[protocol retirement notice](https://developers.google.com/blogger/docs/2.0/developers_guide_protocol)
explicitly retains certain listing GETs as feed URLs. This integration uses
the observed public feed read, without a key or login session.

[BloggerFeedHistory](../core/src/main/kotlin/app/antisocial/core/BloggerFeedHistory.kt)
qualifies only that known read endpoint with the matching blog ID. It can use
the canonical HTTPS provider origin for a published HTTP link. Ports,
credentials, fragments, other operations, duplicate/unknown parameters, a
different blog ID and unsupported formats cannot authorize the route. The
published start index and page size are bounded; parameters are not guessed.

The bounded optional `Page.syndicationId` retains the feed's exact identifier.
Before following the qualified link, continuation checks the previous logical
subscription and its fresh source. Gateway pins this particular read to the
provider's HTTPS origin. The returned Atom ID must match before post extraction;
an unrelated response cannot enter the subscription or close its gap. The
publisher's original subscription URL and item permalinks remain intact, while
provenance records the actual provider page. This adds no database migration or
social-account identity claim. Legacy Pages without the identifier cannot
authorize a cross-origin provider read.

## Evidence

Four regressions failed on unchanged 0.38.14: ordinary sliding RSS, generic social
RSS and an RSSHub snapshot could close a missing interval, and ordinary pagination
was discarded. Candidate checks cover RSS-Bridge too. A query-only reference
exposed Java URI resolution dropping the filename; the existing HTTP URL resolver
preserves it. Another check exposed the exact `/account/delete` shape missing
from shared supplemental policy; its scoped guard preserves documentation URLs.

Tests cover namespaces, bases, stable identities, initial/overlapping windows,
missing history, truncation, unsafe/cross-subscription/cached navigation,
incomplete responses and Gateway admission. Native and full-suite results are
in [release qualification](RELEASE_QUALIFICATION.md).

Explicit October 24, 2026 production-Gateway observations found Android's old
Blogger feed redirecting to `https://developer.android.com/static/blog/atom.xml`:
20 readable posts, no accepted next page, finite window, with the original logical
subscription retained. Blogger's older official blog feed encountered an insecure
destination; Gateway rejected it and the audit stopped without retry. Neither
observation qualifies live Tor or phone performance. A separate explicit Blogger
provider read retrieved two declared pages of 25 posts each with no overlap and
the same blog identifier. The integrated route then read 25 posts from
`https://www.righto.com/feeds/posts/default`, followed by 25 different posts from
the published Blogger endpoint over HTTPS. Both retained the original logical
subscription and exact blog ID. This is a dated two-page observation through the
production Gateway and compiled filters, not complete-history or provider-wide
availability. No article bodies were retained as audit evidence.

The opt-in audit reads at most two pages, follows only a declared continuation,
stops on failure and records counts/routes without publisher article bodies:

```sh
scripts/gradle.sh :core:feedPaginationAudit \
  -PantisocialFeedEvidence=/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/new-feed-pages \
  -PantisocialFeedUrl=https://publisher.example/feed.xml
```
