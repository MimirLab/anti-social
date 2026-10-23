# Substack source atlas

Research date: **2026-10-23**. Substack is a practical addition to anti social:
publications have working RSS and structured article routes, while public profiles,
Notes and discussions have separate anonymous read routes. This audit catalogues
**37 route families, 43 feed-relay instances and 42 repositories**, with **109 HTTP
probes**. Eleven relay instances returned real feeds. Counts include rejected and
unqualified candidates; they are not a count of independent working frontends.

The [machine-readable atlas](data/substack-routes.json) retains endpoint templates,
capabilities, upstream dependencies, publisher scope, sources and dated probe results.
It is research data, **not an enabled runtime source list**. Release 0.38.0 already
accepts publication feeds through RSS / Atom. First-class Substack profiles, Notes,
search and comments still need native adapters and qualification.

## Working reading paths

| Path | What the anonymous probe retrieved | Integration fit |
| --- | --- | --- |
| [Publication RSS](https://support.substack.com/hc/en-us/articles/360038239391-Is-there-an-RSS-feed-for-my-publication) | 20 dated entries each from `on.substack.com` and a custom-domain publication | Existing RSS reader; recent posts, bodies or previews, attachments and canonical article links |
| Publication `/api/v1/archive` | Three publication hosts; two successive pages produced six distinct article IDs | Small initial cards, chronological backfill and publication-scoped search |
| Publication `/api/v1/posts` and `/api/v1/posts/{slug}` | Article lists and selected article JSON with stable IDs, bylines and bodies | Native metadata/body adapters; paid previews remain partial |
| Static article HTML | Selected article text and embedded state without executing JavaScript | Existing protected article extraction, with a strict JSON decoder for embedded state |
| Public profile API | The same selected profile through `substack.com`, `open.substack.com` and a publication host | Resolve handle → user ID → publications, display names and explicitly published external accounts |
| Profile activity API | Two pages containing 24 distinct entity keys | Notes, article activity and restacks, preserving their distinct meanings |
| Note JSON, HTML and embed | A selected Note, its author and available parent context | Direct-link reading; embeds are not complete discussions |
| Article comments and reader replies | Four real branches on a custom-domain article | Native comments; one small thread is not a qualification of large-thread coverage |
| Publication search, recommendations and authors | 20 publication search results; one recommendation and one author on the selected publication | Search cards, adjacent discovery and identity enrichment |

The [anonymous reader implementation in substack-mcp](https://github.com/conorbronsdon/substack-mcp/blob/main/src/api/public-reader.ts)
and [NHagar's client](https://github.com/NHagar/substack_api) are useful protocol
references. Their authenticated writing, Chat and subscriber functions are separate
from the public reads tested here. Our adapter can be small Kotlin HTTP/JSON code;
neither an MCP runtime nor a browser is required on the phone.

Representative endpoints:

```text
{publication}/feed
{publication}/api/v1/archive?sort=new&offset=0&limit=3
{publication}/api/v1/archive?sort=new&search={query}&offset=0&limit=3
{publication}/api/v1/posts/{slug}
https://substack.com/api/v1/user/{handle}/public_profile
https://substack.com/api/v1/reader/feed/profile/{user-id}
https://substack.com/api/v1/reader/comment/{note-id}
https://substack.com/api/v1/reader/comment/{note-id}/replies
{publication}/api/v1/post/{post-id}/comments?all_comments=true&sort=newest_first
{publication}/api/v1/reader/post/{post-id}/replies?publication_id={publication-id}
https://substack.com/api/v1/publication/search?query={query}&offset=0
{publication}/api/v1/recommendations/from/{publication-id}
{publication}/api/v1/publication/users/ranked?public=true
```

These are observed web endpoints, not a claim of an official stable public API.
Origin aliases share Substack infrastructure. A custom domain is a publication
identity, not automatically an independent mirror.

## Additional readers, extractors and copies

| Service | Actual result | Boundary |
| --- | --- | --- |
| [SubstackTools](https://www.substacktools.com/downloader) | Article/publication metadata, three directory items and a matching Markdown download | `/post/markdown` returns a signed file URL first; a second fetch returned 20,909 characters. Metadata has the title and canonical identity; directory entries alone are excerpts. |
| [SubstackDownloader](https://www.substackdownloader.com/) | Anonymous read-only conversion returned the selected title and 21,163 characters of Markdown | `POST /api/convert` with URL, `format: "md"`, `platform: "substack"`, `browserCapture: false`; no session supplied. Its credentialed mode is separate. |
| [Pullsy](https://pullsy.online/tools/medium-reader/) | Its `proxy.pullsy.online` service returned the selected article HTML | A transport exit followed by local extraction; advertising about paywalls does not establish complete paid content. |
| [SubstackFwd](https://substackfwd.xyz/) | Returned selected article HTML at the proxy URL without an HTTP redirect | Body was present, unlike a mere share-card stub. Operator durability remains unqualified. |
| [Jina Reader](https://github.com/jina-ai/reader) | JSON with matching `data.url`, title and Markdown body | One request took about 15.8 seconds; a fallback rather than a reason to delay initial cards. |
| [AnyCrawler](https://anycrawler.com/free/) | Anonymous GET returned selected URL and matching Markdown | Includes navigation and subscription text; local cleaning remains necessary. Service permits cached results up to 24 hours. |
| [MarkdownifyWeb](https://www.markdownifyweb.com/) | Read-only `POST /api/convert` returned selected URL/title and matching Markdown | Public web endpoint, not a promised stable integration contract. |
| [Morss](https://morss.it/) | Valid RSS for the publication, containing four entries | Smaller coverage than the original twenty-entry feed. Useful content does not establish a complete catch-up. |
| [Joel Sunnehall's proxy](https://github.com/joelsunnehall/substack-proxy) | Hosted Vercel endpoint returned three publication article records | Metadata and short excerpts. Its code can manufacture fallback IDs/dates; our adapter must reject those. |
| [Ryan Hockstein's proxy](https://github.com/ryanhock2003/substack-proxy) | Hosted endpoint returned three article records | Hardcoded to one publication. Keep that publisher scope; it is not an arbitrary-Substack proxy. |
| [Internet Archive](https://github.com/internetarchive/wayback/blob/master/wayback-cdx-server/README.md) | Exact-URL CDX hit and a readable matching replay | Reuse the archive engine; preserve capture date and original article URL. |

For the selected free article, each of the five Markdown services retained at
least 35 of 38 sampled six-word passages distributed through the original body.
Pullsy, SubstackFwd and the Wayback replay retained all 38. This checks identity
and body coverage on one article, not universal extraction quality. Some Markdown
downloads omit the title, so body text must be paired with verified metadata.

The signed SubstackTools download link is short lived. Validate the returned HTTPS
destination through NetworkGateway, bound its size and redirects, and discard the
signed URL after acquisition. Do not turn ephemeral storage links into shared
article URLs or persist their query credentials in logs and export files.

## Feed-relay pool

The [RSSHub Substack adapter](https://github.com/DIYgod/RSSHub/blob/master/lib/routes/substack/subscribe.ts)
reads a publication's existing `/feed`. The [RSS-Bridge adapter](https://github.com/RSS-Bridge/rss-bridge/blob/master/bridges/SubstackBridge.php)
expands that feed. Both provide useful alternate exits and caches, while sharing
the original publisher as their upstream dependency. Neither adds Notes simply
because it can read publication articles.

These hosts returned twenty-entry feeds for the selected publication:

| Family | Tested base |
| --- | --- |
| RSSHub | `https://rsshub.rssforever.com` |
| RSSHub | `https://rsshub.woodland.cafe` |
| RSSHub | `https://rsshub.ktachibana.party` |
| RSSHub | `https://rss.noleron.com` |
| RSSHub | `https://rsshub.rss.tips` |
| RSSHub | `https://rsshub.isrss.com` |
| RSSHub | `https://rsshub.cups.moe` |
| RSSHub balancer | `https://rsshub-balancer.virworks.moe` |
| RSS-Bridge | `https://rss-bridge.org/bridge01` |
| RSS-Bridge | `https://rss.bloat.cat` |
| RSS-Bridge | `https://wtf.roflcopter.fr/rss-bridge` |

Templates:

```text
{rsshub-base}/substack/subscribe/{publication-subdomain}
{rssbridge-base}/?action=display&bridge=Substack&url={encoded-publication-feed}&format=Atom
```

The atlas also retains all failed/unsupported candidates from the current
[RSSHub list](https://github.com/RSSNext/rsshub-docs/blob/main/.vitepress/theme/components/InstanceList.vue),
the [RSS-Bridge list](https://rss-bridge.github.io/rss-bridge/General/Public_Hosts.html)
and previously known hosts. A listed server can have Substack disabled. A balancer
can reuse an operator already in the pool; eleven working URLs do not establish
eleven independent failure domains.

## Candidates that need different treatment

* [OpenRSS](https://openrss.org/feeds/substack) documents Notes, replies and
  restacks. The selected handle returned an HTML feed-creation page; a publication
  request returned 429. This is not a qualified generic feed route. A separately
  provided, verified feed remains a possible input.
* [Readbetter](https://www.readbetter.io/substack-to-epub) offers three anonymous
  EPUB conversions per day. Its public client exposes generation and download
  calls, but conversion was not tested. Keep it as an explicit export-tool lead.
* [Superstack](https://github.com/kees-/superstack),
  [substack-edgelord](https://github.com/that-mathevs/substack-edgelord),
  [the Data Mine adapter](https://github.com/Marvin-The-Bodega-Cat/substack-block-adapter)
  and [the AEO proxy](https://github.com/dgtldept/substack-aeo-proxy) demonstrate
  publisher-specific mirrors. They require exact publisher/article matching;
  their existence does not establish a universal hosted service.
* [Sidestack's scraper](https://github.com/JaredTweed/sidestack_scraper) supplies
  directory/feed leads, including custom domains. Its live directory returned
  403. Old snapshots are discovery candidates to verify, not current facts to
  dump into every phone's database.
* The migrated [Twitter sharing proxy](https://github.com/OmarShehata/twitter-substack-proxy)
  and [Share The Link](https://github.com/selcukcihan/substack-sharing) document
  metadata-and-redirect wrappers. They help recognize incoming links; a landing
  page is not a recovered article.
* Generic CORS proxies and RSS2JSON returned 403, 502 or 522 in these probes.
  Several public host lists also led to TLS/DNS errors, a Folo redirect, a shutdown
  page or “bridge not whitelisted.” Preserve the failure category and cool down.
* Arquivo.pt's index answered, with no capture for the selected article. That is
  a target miss, not proof the archive is broken. The sampled publication's
  `/sitemap.xml` returned 404; sitemap discovery is not a guaranteed backfill path.
* [Publisher API](https://github.com/dkships/substack-publisher-mcp) needs a
  publication owner's API key. Authenticated browser readers, IMAP readers and
  private Chat clients are not anonymous reading fallbacks. Ghost and other
  newsletter publishing replacements are not Substack frontends.

## Traps found by checking real responses

1. **A cursor can repeat a page.** The selected Note returned eight reply
   branches, `moreBranches=1` and a continuation cursor. Requests using that cursor,
   including the client-supplied `comment_id`, returned the same eight IDs. Stop
   after zero novel IDs; keep the thread partial. Profile-feed and archive pages
   did produce new IDs. Never use a changing cursor alone as evidence of progress.
2. **Requested limits can be ignored.** Publication search returned twenty
   records despite `limit=3`. Enforce byte, record and parser limits locally.
3. **Long text can still be a preview.** One `only_paid` article declared 4,153
   words but exposed roughly 2,272 body words. It had no simple paywall CSS marker.
   Preserve its audience and partial status; word count is corroborating evidence,
   not an exact completeness test for every post type.
4. **A public profile can be heavy.** The sampled profile exceeded 1 MB because
   it includes publicly listed subscriptions and embedded publication records.
   Cache a compact projection and avoid repeated full-profile fetches while scrolling.
5. **A successful empty discussion is scoped.** Zero comments on two selected
   article responses is neither proof of a dead provider nor proof no alternative
   thread exists. Keep post comments separate from Notes and their replies.

## Native integration shape

Use capability-specific banks: publication feed/archive, article body, profile,
Notes, comments and discovery. A metadata proxy cannot satisfy a body request.
Exact article archives cannot satisfy current Notes. Keep health by capability,
origin and upstream dependency, with target misses separate from service failures.

Start from cached verified identities and cards. Choose a bounded healthy alternate
for the requested capability; permit accountless originals only according to the
existing fallback policy. Stage body, discussion and identity enrichment behind
the initial deck. Reuse adaptive cooldowns and cache validators where supplied;
do not send heartbeats to all 43 hosts or fan out every article to every converter.
All app requests, file downloads, media, redirects and archive replays go through
NetworkGateway. Selected Tor continues to fail closed.

Identity should use publication ID, user ID, article ID and Note/comment ID, with
verified custom-domain aliases. A person's profile and their publication are
related entities, not interchangeable handles. A recommendation is a discovery
edge, not a cross-platform ownership assertion. Explicit profile links and bylines
can feed the existing account-linkage and proper-name caches.

The same extraction pass can preserve article references, available media and
declared transcript links for Context; publisher recommendations for discovery;
and image-fetch wrapper targets for the media deduplicator. Validate media targets
and actual bytes before treating an unwrapped image as equivalent. Distinguish a
restack from newly authored information so resurfacing an article does not create
another duplicate feed card. Keep canonical publisher links on sharing and source
attribution, regardless of the relay used to retrieve the text.

No AI is needed for these routes. The portable pieces are small parsers and typed
records, rather than Node/Python/browser stacks in the APK. Preserve donor Darwin
provenance and any upstream notices if implementation code is adapted. Repositories
without a declared license are protocol references, not permission to copy code.

## Validation and remaining qualification

The source audit used anonymous HTTP, no Substack cookies or keys, bounded responses,
at most four concurrent requests and pacing between requests to the same host.
Public client JavaScript was inspected as text, never executed. Latencies in the
atlas are individual laptop observations, including probe pacing and possible queue
time, not a benchmark or a prediction for Tor or a phone.

The existing production JVM NetworkGateway fetched two publication feeds, RSSHub
RSS and RSS-Bridge Atom. Production RssSources parsed twenty dated native posts
from each, retaining original article URLs. Profile and Notes JSON also passed
through the gateway. This diagnostic used an allow-all request filter to isolate
transport/parser compatibility; it does not qualify the bundled protection lists,
live Tor routes or physical phones. No app runtime change is included in this atlas.

The existing RSS foundation passed 21 JVM tests. On the isolated API 35 emulator,
eight of nine RSS instrumentation cases passed in the initial run; the undated-feed
case failed its broad no-network-event assertion after its chronology and refresh
assertions had passed. That exact case and the native mixed-feed/reader journey
then passed together in an isolated replay. Their saved native screens were inspected.
The intermittent observer failure remains undiagnosed; this is not an all-green
instrumentation claim or a first-class Substack UI qualification.

Before enabling first-class support, qualify fixture and native journeys for
custom-domain identity, feed/Note subscriptions, direct links, partial threads,
repeated cursors, filtered network failures, chronological backfill and source
provenance. Paywall/coverage honesty and bounded acquisition are acceptance checks,
not a reason to discard usable public text.
