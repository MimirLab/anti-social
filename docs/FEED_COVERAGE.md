# Feed windows and history recovery

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

Sotwe's public account API also supplies a finite window: its adapter explicitly
has no qualified older-page operation. That response now carries the same flag;
a successful API head does not establish that the missing interval was read.

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
head is missing, through declared navigation or the scoped native recovery below.
Initial and already joined
windows make no history requests. There is no new polling job, setting, model,
runtime or full-archive crawl. Every request uses NetworkGateway, selected Tor
fails closed, and completed pages survive an unfinished later read under the
[publication contract](FEED_REFRESH.md).

Initial heads retain [revalidation](FEED_REVALIDATION.md). Ordinary continuation
pages currently perform full protected reads. A source without usable history
remains pending across a gap; the app does not guess pagination parameters.

## Native feed recovery, 0.38.16

A cheap syndication head can be useful even when its window has no cursor.
The same frontend may expose an HTML timeline with real pagination; another
qualified API or frontend may cover the missing interval. The collector can now
try those existing readers before leaving the gap pending. A repeated or failed
real cursor and an explicit continuation limit can use the same recovery path.

Recovery belongs to the current catch-up coroutine, exact SourcePool and target.
Only a fresh, readable native feed read binds its initial policy. Pages remain
plain data: serializing, copying or retaining a Page does not retain authority to
start recovery. Ordinary RSS/Atom continues to use declared navigation and its
qualified provider bridge, rather than entering social-source discovery.

The allowance is **three additional native adapter starts** across the refresh,
including cancelled hedges. This is an adapter-operation limit, not a claim of
three HTTP exchanges: an adapter can already implement multiple bounded reads.
It shares the existing page/post limits and entire source deadline. Available
slots, healthy-method priority, method diversity, cooldowns and cancellation use
the normal router. When originals are allowed, a shortlist can reserve a slot
for that later tier instead of spending every slot on alternate mirrors.

Adapters distinguish a separate cheap feed representation from `feed()` simply
calling `fetch()`. Recovery does not repeat the latter's identical initial read.
It starts each newly selected timeline without another source's cursor; any
cursor that timeline subsequently supplies stays with its issuer. Archive/cache
copies, public-index previews and undated timelines cannot certify catch-up.
If no route reaches the retained head, even another cursorless API result leaves
the gap pending. New posts already found survive failures and the owned deadline.

Additional timeline reads and real history continuations record TIMELINE health
and latency separately from the initial FEED head. A slow/dead history format
therefore does not replace a working RSS head's observation. Repeated canonical
posts from recovery preserve the snapshot already collected, including its
caption, gallery and provenance; new unique roots join the same chronological
publication. Ordinary same-source pagination retains its existing replacement
behavior before recovery begins.

The initial privacy-selection identity and original-access policy remain in
force through planning, continuation and final return. A mode round trip or
permission change cancels stale work. Later continuation arguments can further
restrict original access, and cannot expand the initial permission. Every
network operation still passes through NetworkGateway. No Page schema, database
migration, persistent route cache, polling job, model or setting is added.

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

## RSS header navigation, 0.38.17

Nitter's [RSS router](https://github.com/zedeus/nitter/blob/master/src/routes/rss.nim)
publishes its opaque cursor as a `Min-Id` HTTP response header and accepts it as
the `cursor` query parameter. Its RSS body need not contain a next-page link.
The [Shitter fork's router](https://codeberg.org/mv12star/shitter/src/branch/master/src/routes/rss.nim)
preserves this mechanism. [NitterFeedHistory](../core/src/main/kotlin/app/antisocial/core/NitterFeedHistory.kt)
now preserves an admitted header as a bounded data bookmark, allowing catch-up
to continue in RSS before spending a native-recovery start on another format.
The existing overall page/post/deadline limits still apply.

Admission requires a readable finite feed, its actual alternate Nitter source,
the matching canonical X subscription, and the same source root and operation.
Supported operations are an account head, `with_replies`, `media`, `articles`,
and numeric `i/lists/ID`. Search queries are outside this header adapter's scope.
The cursor is a bounded opaque value escaped as one query parameter, never a
publisher-supplied destination. Duplicate, empty, suspended or malformed headers
cannot grant navigation; the readable head remains available.

The bookmark binds source, target and the current Gateway session. A process
restart, another Gateway or a connection-mode round trip requires a fresh head.
The local session epoch is never sent in HTTP. The issuing session is carried
through the IO handoff so a mode change between decoding and dispatch cancels
the read. Gateway pins a cursor request to its exact selected address through
cleaning, filter rewrites and redirects. It cannot move to another account,
operation, host or cursor. Head reads retain their existing migration behavior.

Parsed XML and current header navigation are stored separately inside the
existing bounded [feed memory](FEED_REVALIDATION.md). Identical successful 200
bodies can supply a different cursor or remove it without repeating extraction.
An exact validated 304 can retain an omitted header; a malformed supplied header
clears that navigation. Tor still makes a full protected read without retained
validators, and applies the new response's header even when XML parsing is reused.
No Page field, database migration, dependency, setting or background job is added.

Header continuations record TIMELINE health separately from the initial FEED
head. If continuation fails or cannot bridge the retained reading position,
bounded native recovery remains available. Absence of a header does not certify
complete history.

### Dated deployment observations

October 24 production-Gateway checks, with compiled protection and a 1 MiB/read
limit, found several maintainer-listed roots unavailable, refused or outside the
audit budget. Other reads returned HTML. The audit tool then appended `/rss`
after Darwin's `#m` fragment, so those observations qualify HTML rather than
establishing an RSS redirect or the absence of RSS support. Owned unit and native fixtures qualify
that protocol path separately; source code support is not a live-uptime claim.

The [Shitter maintainer's directory](https://codeberg.org/mv12star/shitter/raw/branch/master/instances.json)
also supplied deployments absent from the previous pool. Two selected roots,
`https://nitter.meowing.monster` and `https://nitter.mie.foundation`, each returned
two dated twenty-post NASA HTML windows with zero overlap through Gateway.
They are now separately registered Nitter-compatible alternatives. Both start
UNKNOWN, use normal content-based learning, and retain the existing budgets and
backoff. The observations do not establish independent upstream infrastructure,
every target/operation or live-Tor availability. The corrected October 25 RSS
qualification follows below.

### Secure legacy rendering references (0.38.32)

Both deployments serve RSS over HTTPS but declare their own HTTP rendering URLs,
including the ordinary `#m` item fragment. The old decoder discarded those
references and loaded HTML instead. The scoped Nitter decoder now accepts this
record identity only when the fetched HTTPS feed matches the selected source and
account, the channel names that account on the same mirror's default HTTP port,
and each status handle/numeric ID agrees with its creator and GUID. Only the empty
or `m` fragment is admitted. Foreign HTTP render hosts, credentials, unusual
ports, queries and disagreeing records retain their rejection.

Admitted records become original HTTPS X URLs. Their own-host media/link
references become HTTPS references to that same selected mirror, retaining their
path and query; they still pass through Gateway when fetched. Other HTTP links
remain excluded. This does not permit an HTTP connection or register a global
alias. Generic feeds retain their previous behavior.

Corrected direct production-Gateway checks on October 25 read two dated
twenty-post NASA RSS windows from each deployment, with zero overlap and distinct
published header cursors. Bodies were approximately 23–26 KiB, rather than the
previous RSS-plus-HTML fallback. These are dated observations, not a phone speed
benchmark or a claim about every account or connection.

The operator-published `teapawt.net` entry adds a fallback Nitter-compatible route.
Its NASA and WhiteHouse RSS heads returned dated posts; both advertised RSS
continuations repeated their head. Separate two-window HTML checks returned
older posts for both accounts. Existing bounded catch-up detects repeated cursors
and can try native timelines without borrowing another source's cursor. The
operator's spare hostnames share this entry; they are not counted as independent
infrastructure. Search, other operations and live Tor remain unqualified.

The opt-in audit accepts at most two explicit HTTPS roots and two pages each;
it does not add a tested candidate to the runtime pool automatically. It stores
counts, dates, sizes and navigation hashes rather than bodies or raw cursors:

```sh
scripts/gradle.sh :core:nitterFeedAudit \
  -PantisocialNitterEvidence=/run/media/red/RedtopStorage/DarwinBuild/LiberBuild/evidence/new-nitter-check \
  -PantisocialNitterRoots=https://operator.example
```

By default it follows only admitted RSS header navigation. Explicit
`-PantisocialNitterFollowHtml=true` also tests a returned ordinary HTML cursor,
within the same two-page limit. `-PantisocialNitterOperation=timeline` selects the
production timeline reader instead; `-PantisocialNitterAccount=WhiteHouse` selects
another explicit public account. The initial RSS read uses the production
adapter's `feed()` method. Response type, query-field names, body hashes and XML
extraction outcomes distinguish a source response from parser fallback, without
retaining bodies or raw cursors. See [release qualification](RELEASE_QUALIFICATION.md)
for the exact build and Android regression record.

## Further route research

Maintainer-published onion services and transport-sensitive public previews are
separate research leads. They are not automatically activated by an instance
listing or by the header adapter. Any integration must preserve selected Tor,
target identity, response admission, budgets and source refusal/backoff. A new
connection is not evidence that a login requirement has disappeared.
