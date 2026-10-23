# Discovery source investigation

Investigated October 23, 2026. These are implementation notes, not a promise that
every listed service is an active dependency. The decision is whether a route
adds a useful observable relationship within the reader's latency, privacy and
storage budgets. Small native parsers and public HTTP routes are preferable to
shipping entire research dashboards or browser automation runtimes.

## Qualified mechanisms used in this increment

| Primary reference | Finding and use |
| --- | --- |
| [Followgraph source](https://github.com/gabipurcaru/followgraph/blob/main/components/Content.tsx) (MIT) | Actual code resolves a seed, follows its public following lists, coalesces second-hop accounts and records which first hops follow each result. Anti Social independently implements bounded distinct-path counting with two live lanes. It does not copy the project's 2,000-account scan or popularity tie-break. |
| [FxEmbed v2](https://docs.fxembed.com/api/twitter/) | Live following and follower responses use `results` and sometimes `cursor`, not the older single-profile envelope. Requested counts are not always honored; transfer and parsing limits remain necessary. Native Android Gateway qualification returned 30 usable NASA follows. |
| [Bluesky public routes](https://docs.bsky.app/docs/api/app-bsky-graph-get-follows) | Handle-based routing identity includes a reader path prefix; the graph API needs the actual handle or DID. Subject validation prevents unrelated returned lists being accepted. Native Gateway qualification returned 23 follows for bsky.app. |
| [Constellation](https://constellation.microcosm.blue/) | Independent exact-DID follow backlinks work publicly and identify underlying record owners. Live desktop probe returned records and a cursor. Historical backfill is incomplete; huge advertised totals are not a complete audience denominator. |
| [Mastodon account methods](https://docs.joinmastodon.org/methods/accounts/) | Featured-account endorsements are public on supporting instances and arrived in Mastodon 4.4. Array pagination lives in HTTP Link headers. Both lookup and featured account read worked through Android Gateway; following worked in a separate live probe. |
| [Arctic Shift API source](https://github.com/ArthurHeitmann/arctic_shift/blob/master/api/README.md) | User interactions and community participation can be obtained without transferring every comment body. Native Gateway qualification returned an actual non-deleted user interaction. Empty lists are valid empty observations, not evidence the account has no audience. |
| [Ghost recommendations](https://docs.ghost.org/recommendations/) | Small publisher-selected recommendations at a well-known URL; a live 404 Media probe returned eleven destinations. Follow only a bounded selection into declared feed discovery. |
| [Feedscout](https://feedscout.dev/reference/discover-blogrolls) (MIT) | Its supplied-response/custom-fetch design is the useful pattern: reuse HTML and HTTP Link declarations before path guessing. Anti Social implements those declarations in Kotlin and shares typed results with idle account-site lookup. No JavaScript dependency or path-guessing sweep was added. |
| [Feedsmith namespace reference](https://feedsmith.dev/reference/namespaces/blogchannel) (MIT) and [Podroll specification](https://podcasting2.org/docs/podcast-namespace/tags/podroll) | Preserve feed-level reading-list pointers before normalization. Native implementation recognizes OPML categories, blogChannel lists and Podroll entries with public feed URLs. GUID-only resolution is deferred. Episode enclosures are not downloaded. |
| [Telegram parser reference](https://github.com/DIYgod/RSSHub/blob/master/lib/routes/telegram/channel.ts) and [TelegramScraper](https://github.com/specialteam/TelegramScraper) (MIT) | Forward source attribution is useful before flattening public channel HTML. Existing native Telegram retrieval now retains a typed source account in posts and offline snapshots; no scraper dashboard/server is installed. |
| [Schema.org CreativeWork](https://schema.org/CreativeWork) | Scoped article citations can improve source retrieval as well as discovery. The Context article extractor now retains citation/isBasedOn references for the selected work. A publisher's Organization object or another article cannot donate unrelated references. |

The adapter, tests and cache limits are documented in
[Discover related](ADJACENT_DISCOVERY.md). These are protocol/method references;
no new vendored libraries, native engines, model files or upstream code copies
were added by this increment. Existing Darwin and GPL attribution remains intact.

## Useful extraction references and next consumers

| Inspected source | Useful puzzle piece | Integration decision |
| --- | --- | --- |
| [yt-meta](https://github.com/ShaneIsley/yt-meta) (MIT), [YouTube.js](https://github.com/LuanRT/YouTube.js) (MIT) | Lazy comment pagination, chronological cutoffs, explicit creator hearts/pins, collaborator objects, cards and end screens | Linked-video support and Context may preserve these fields when their existing payload includes them. Do not turn YouTube into a social subscription platform or run commenter scans for every embedded video. The 0.38 discovery bridge now extracts creator-selected channel tiles and scoped About links from literal public channel JSON. Video/comment collectors are separate; no YouTube subscription platform was added. |
| [YouTube Data Tools v2](https://github.com/bernorieder/youtube-data-tools-v2) (GPL-3.0) | Shared-commenter networks and owner/reply flags | Reference for participant deduplication and collection windows. Its API-key/data-processing deployment is not bundled. Existing fetched-discussion participant bank captures the transferable cheap technique. |
| [gallery-dl Instagram extractor](https://github.com/mikf/gallery-dl/blob/master/gallery_dl/extractor/instagram.py), [Instaloader structures](https://instaloader.github.io/module/structures.html), [instagrapi](https://github.com/subzeroid/instagrapi/blob/master/docs/usage-guide/media.md) | Accepted coauthors, tagged users and sponsors are different relationships | The new typed post relationship record has these slots. Current successful lightweight Instagram routes do not consistently provide those payloads. Do not invent collaboration from a caption mention. Extractor adaptation requires representative acquired fixtures first; full Python/session collectors not installed. |
| [Threads AI Radar](https://github.com/BaYue-SYJ/threads-ai-radar), [Agentic-Threads](https://github.com/tjdwls101010/Agentic-Threads) | Embedded JSON can retain reply, quote and repost account references | Preserve typed references if supplied by a successful account/post payload. Login-session collection and a viral-post ranker are not accountless discovery foundations. No session collector activated. |
| [facebook-scraper](https://github.com/kevinzg/facebook-scraper) (MIT) | Shared post/author IDs, full comments and reactors | Share attribution can improve fusion, original-link recovery and Context. The older collector's cookie-dependent advertised features are not proof of a working anonymous route. New acquisition route deferred. |
| [Tumblr API / NPF](https://www.tumblr.com/docs/en/api/v2), [NPF trail](https://github.com/tumblr/docs/blob/master/npf-spec.md) | Reblog root/parent trail and typed notes | Potential distribution and deduplication evidence when an app-key route is available. Not silently configured with credentials or presented as a qualified anonymous graph. |
| [pyktok](https://github.com/dfreelon/pyktok), [TikTok-Api](https://github.com/davidteather/TikTok-Api) | Embedded account/source-work IDs | Future existing-payload preservation. `duetEnabled` permits reuse; it is not evidence that a duet occurred. Browser/token machinery not bundled. |
| [PinterestDownloader](https://github.com/x7007x/PinterestDownloader) (MIT) | Public board owner and curated pin source relationships | A narrower curation adapter could help discovery; counts alone do not enumerate followers. Requires dedicated fixtures and live qualification before activation. |
| [FediLive](https://github.com/FDUDataNET/FediLive) (MIT) | Public federation reply/boost/favourite collection | Existing discussion retrieval and the passive bank avoid a parallel crawler. Status-interaction endpoints are an additional bounded candidate; platform-wide collection is not required. |
| [Public Bluesky post analyzer](https://romiojoseph.github.io/bluesky/bluesky-post-analyzer/) | Actual reply/quote/like/repost participants | Adjacent post-neighborhood adapter candidate. Repost identities do not include timestamps in every endpoint. Current increment uses follows and existing discussions first. |
| [twscrape](https://github.com/vladkens/twscrape), [FxEmbed](https://docs.fxembed.com/api/twitter/) | X communities/lists, recency conversations, quotes and repost authors | FxEmbed recency conversation route is a promising existing comments/Context consumer. Authenticated collector sessions remain separate from a phone's accountless read. Not added without comment-parser and fallback qualification. |
| [Nitter types](https://github.com/zedeus/nitter/blob/master/src/types.nim) | Quote, thread and repost identifiers | Preserve provenance in existing successful frontends; frontend reachability still does not prove usable target content. No claim that every fork exposes graph routes. |

## Additional bridges, kept out of ordinary search and feed work

| Source | Useful mechanism and boundary |
| --- | --- |
| [TwitchMap](https://twitchmap.com/) | Shared active chatters in a limited channel/time panel; its public page actually requests `data.json`. That small JSON graph is now an on-demand bridge adapter. Channel indices and edges are checked; the panel remains incomplete. Twitch is not a reader subscription platform. |
| [Twitch APIs](https://dev.twitch.tv/docs/api/reference/) | Teams/shared-chat are explicit collaboration. Mirrored chat must not count as independent audience overlap; token requirements remain real. |
| [soundcloud.py](https://github.com/7x11x13/soundcloud.py) (MIT) | Featured profiles, reposts and playlists are richer than generic related-artists output. A live public NASA profile contains `apiClient` and `user` hydration records. Those supply the public client identifier and exact user/URN without fetching or running scripts. Native public featured/following/web-profile reads now form a discovery bridge; NASA follows ESA. No music runtime/platform added. |
| [bandcamp-retriever](https://github.com/lufinkey/bandcamp-retriever), [Mixcloud metadata](https://www.mixcloud.com/developers/) | Public curation/following and advertised relationship routes | Mixcloud's anonymous profile and following JSON are now a discovery bridge. Bandcamp remains a parser/reference candidate; no private collections or new subscription tabs were added. |
| [Nostr NIP-02](https://github.com/nostr-protocol/nips/blob/master/02.md), [nostr-social-graph](https://github.com/mmalmi/nostr-social-graph) (MIT) | Signed follow lists and compact graph serialization | Good native graph/relay adapter candidate when Nostr reading is supported. No global relay mirror is needed; no Nostr backend added here. |
| [PodPeopleDB](https://github.com/madeofpendletonwool/podpeople-db) (GPL-3.0), [podcast socialInteract](https://podcasting2.org/docs/podcast-namespace/tags/social-interact) | Credits and publisher-declared discussion locations | Potential Context/video-audio source references. Small corpus and role/source verification matter. Direct Podroll URLs are the included inexpensive part; person APIs and discussion fan-out are deferred. |
| [Blogroll Network Map](https://github.com/robalexdev/rss-blogroll-network) (Apache-2.0), [rss-feed-index](https://github.com/lwindolf/rss-feed-index) | Catalogs can discover reading-list locations | A catalog is a lead, not a current endorsement. Fetch current publisher OPML using the included parser; no stale global catalog imported. |
| [Webmention.io](https://webmention.io/api) | A single public target's typed responding authors | Promising existing comments/Context enrichment. Avoid site-wide scans requiring an owner's token. Not an automatic request for every article. |
| [Pinlyx overlap](https://pinlyx.com/data-api/reference/graph-x-overlap) | Precomputed sampled X graph | Optional external provider candidate, not a complete-X denominator or default paid dependency. No provider key assumed. |
| [extruct](https://github.com/scrapinghub/extruct), [mf2py](https://github.com/microformats/mf2py), [turbohtml](https://github.com/tox-dev/turbohtml) | Preserve typed metadata before Markdown conversion | Existing bounded Jsoup tree supplies selected declarations without another runtime. Broad parser replacements require output-equivalence and resource tests; project benchmark claims alone do not justify them. |

These notes deliberately distinguish a working acquired route, a useful parsing
reference and a proposed adapter. Missing access or absent fields stay missing;
a failed graph source never becomes a claim that no related accounts exist.

## Wider discovery verification

Desktop probes located twelve creator-selected channel tiles on NASA's YouTube
About/channel page, the public TwitchMap graph, and SoundCloud NASA → ESA follows.
Android gateway tests then verified the full YouTube round trip: Learn With NASA
returned Facebook, X and a Pinterest board; Kennedy Space Center returned X,
Facebook and Instagram. SoundCloud's actual public profile/follow/web-profile
requests also passed through NetworkGateway. These are observed routes, not a
promise of future provider availability or exhaustive graph coverage.

Reuse opportunity: SoundCloud's public hydration avoids a multi-asset client-ID
scrape. Keep only typed public identifiers; discard anonymous browser identifiers,
analytics initialization and the page/scripts. The same literal-payload extractor
can support future relationship preservation without executing website code.
