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

## Public-read request personas: research path

The owner's October 24 request concerns a coherent disposable browser persona:
realistic matching HTTP headers, TLS/HTTP protocol characteristics and anonymous
session state, alongside the selected transport. It is broader than suppressing
fingerprint fields or changing a User-Agent. Candidate implementations include
[curl-impersonate](https://github.com/lexiforest/curl-impersonate) and
[wreq](https://github.com/0x676e67/wreq). Their documented emulation mechanisms are
research inputs; neither is installed or enabled in this increment.

[Tor Browser's identity design](https://support.torproject.org/tor-browser/features/managing-identities/)
and [Brave's ephemeral storage](https://brave.com/privacy-updates/7-ephemeral-storage/)
provide useful isolation references. Evaluate a bounded public-read profile by
target and operation: retained useful content, latency, memory/package cost,
refusal behavior, and whether one internally consistent profile improves delivery.
Keep temporary state separate from service credentials, other sources and other
Gateway sessions. An implementation must remain beneath Gateway's admission,
redirect, TLS, byte/request, cancellation and selected-Tor controls; this is not
a second unprotected downloader or a persistent social login jar.

The current app already makes anonymous reads without a general cookie jar and
has a narrowly scoped cursor-cookie path. It does not yet fabricate complete
browser personas or automatically renew them on refusal. Findings about a public
preview must distinguish its available content from a login wall, and measure
each site's actual transport/session behavior rather than claim infinite access.

### First-party construction and qualification, October 24

The implementation direction is a custom anti social component. External
projects supply mechanisms to investigate, test inputs and protocol references;
they are not installed as an extension or adopted as the app's architecture.
[PublicPersona](../core/src/main/kotlin/app/antisocial/core/PublicPersona.kt)
now constructs a coupled browser release, reduced UA/appVersion, client-hint
brands, virtual device and declared wire contract. The initial mobile and desktop
cohorts preserve their observed group/ALPS differences and HTTP/2 settings.
Headers and their JavaScript-facing descriptor derive from the same identity;
this descriptor does not itself implement or execute a browser realm.

The component uses first-party Kotlin and existing HTTP cookie parsing. It adds
no fingerprint-generator dependency, inference model or device interrogation.
Its memory-only bank holds at most sixteen default sessions, eight bounded
anonymous cookies each, with a ten-minute absolute lifetime. Source, full target,
exact origin and the owning Gateway session bind each lease. The bank reads its
owner's current session rather than accepting caller-supplied snapshots. A mode
round trip, expiry, eviction, clock rollback or clear invalidates old state.
This state is separate from service credentials, reading history and saves.
The bank grants no permission to fetch: Gateway still owns admission and budgets.

Ten new unit cases cover construction, independent contradiction checks, request
semantics, actual Gateway mode changes, scope forgery, cookie paths/prefixes,
deletion, bounds and absolute expiry. The full candidate JVM run covered **2,100
cases**, zero failures/errors and one optional weight-dependent skip. A separate
API 35 batch covered fifteen named Android checks: fourteen passed and one
opt-in live Tor/direct-exit check was skipped. Two new Java instrumentation cases execute
the actual core bytecode, checking sixty-four constructed profiles and disposable
state; the others retain search privacy and RSS reading coverage. The mixed
RSS/social native screen was inspected. These are foundation results, not a new
public-access or complete-browser qualification.

The custom packet transport and its Gateway dispatch are still being developed.
The public APK and live laptop preview remain the qualified **0.38.17** release;
the foundation candidate is isolated from them and is not an enabled read route.
Applying plausible headers to an unrelated TLS stack is not considered success.

### What the reference experiments established

The native calibration probe relinked the hash-pinned curl-impersonate 2.3.0
Android static archive with 16 KiB ELF alignment and static C++ linkage. Its
stripped arm64 reference library is about four MB; the original shared object's
LOAD alignment was 4 KiB. The probe loaded on ordinary Android 29/35 x86_64
emulators. ELF admission is not an actual 16 KiB-device test, and this reference
library is not the product transport.

An Android 35 owned-wire harness checked four configured browser profiles,
actual TLS ClientHello fields, HTTP/2 settings and pseudo-header order. It also
checked hostname/CA rejection, one-hop redirects, an owned SOCKS hostname tunnel
and a gzip expansion limit: nine cases passed. The prototype needed explicit
decompression configuration even though its browser headers already advertised
compression. Its corrected decoded-body callback stops the expansion; that was a
prototype finding, not a defect in the shipping Gateway. A newly created fixture
certificate also correctly failed for a three-second emulator clock difference;
the fixture validity interval was repaired without weakening certificate checks.
No public previews or actual Tor exit were tested by this wire harness.

Repository metadata led to the small
[Veilus generator](https://github.com/veilus/fingerprint-generator/tree/f0bdfe732b72c9652d3d4f766b951c26f8564f9a),
alongside [BrowserForge](https://github.com/daijro/browserforge) and
[Apify's suite](https://github.com/apify/fingerprint-suite). Its upstream unit
checks passed, but an independent strict, `en-US`, seed-0-through-999 audit found
contradictions between browser metadata, actual UA and HTTP/JS hint brands.
Some returned Chrome profiles carried Brave brands on one surface. Rare
constraint combinations also exhausted rejection sampling. Those findings
motivate construction from qualified whole cohorts and independent invariants;
they are not a reason to replay arbitrary generator output on the phone.

The metadata search also found the one-star
[fpgen-js port](https://github.com/JWriter20/fpgen-js). Its conditional generation
and targeted projections are useful offline-catalog leads; its documented model
expands to about 210 MB, so a small download is not evidence of a small runtime.
[Camoufox](https://github.com/daijro/camoufox) supplies related cross-surface
consistency mechanisms, while
[CreepJS source](https://github.com/abrahamjuliot/creepjs) supplies independent
contradiction checks to examine. These are references, not shipped dependencies.
Further work follows small forks, dependency users and histories as well as
major repositories; star counts do not replace source and runtime qualification.

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
