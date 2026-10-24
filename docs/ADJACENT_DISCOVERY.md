# Discover related

Open a public account and tap the compass beside **Subscribe locally**, or choose
**Discover related** from a subscription's menu. This is adjacent-account and
community discovery. **Find linked accounts** remains the separate identity tool.
No AI, social login, developer key or extra download is required.

Discovery opens a scrollable deck. Each method has its own card containing a
horizontal row of accounts. A method appears with its first usable results;
later methods append below, while existing cards fill in place. Platform icons
filter the readable accounts and the magnifier searches the collected results.
Tap an account to read or subscribe locally. Native connection reasons expose
their evidence. Further bounded graph blocks run automatically; visible bridge
neighbors trigger their social-account lookup. Closing the deck cancels its work.
The reading feed stays chronological.

## What establishes a connection

- Creator-selected featured accounts and published reading lists.
- Public follows, and accounts reached through at least two distinct sampled
  following paths. Shared followers means observed follows, not all viewers.
- Public archived Reddit interactions and community participation. An interaction
  can be a disagreement; it is not an endorsement.
- Exact account mentions, public links and Telegram forward attribution in posts
  the reader has already fetched.
- Shared public commenters in discussions already fetched by this installation.
  Two distinct commenters are required. Repeated pages cannot inflate counts.

Each reason retains its source URL, owner, date, path, counts and available sample
information. Source-provided ordering is not assumed to be random or complete.
A limited sample can miss a useful account. No claim of a complete social graph
or immunity to source omissions is made.

## Across platforms

All locally grouped subscription accounts enter discovery, up to the subscription
limit of forty. Identity lookup streams additional public associations while the
first graph results arrive. Primary work is balanced across providers before
second hops and pagination; fast X results no longer suppress another provider.
A fresh graph cache supplies immediate cards but does not skip identity expansion.

Discovery identities are broader than subscription platforms. YouTube, SoundCloud,
Mixcloud, Twitch and Substack can be routing bridges without becoming feed tabs. For example:
X NASA → declared NASA YouTube channel → creator-selected Kennedy Space Center
channel → its publicly linked X, Facebook and Instagram accounts. The same process
works through usable Bluesky routes even if the reader does not use Bluesky.

A public profile link can be followed as an association for discovery; that does
not establish common ownership. Actual alias merging still requires the existing
local, exact directory or scoped identity-declaration evidence. Matching usernames,
shared names and incidental mentions do not automatically merge people.

For visible result cards, the same bounded identity tool finds other platforms.
Cards sharing an evidence-backed identity collect under their first published
position. Similar names do not merge cards. The original relationship remains
attached to the platform where it was observed; an alias does not acquire a
fictional follow, endorsement or interaction on another platform.

## Acquired sources

| Route | Mechanism | Coverage limits |
| --- | --- | --- |
| X / FxEmbed v2 | Public following/follower lists, bounded second hops | One qualified graph frontend; source-selected ordering, access and cursors vary |
| Bluesky AppView | Public follows/followers with subject validation and exact DIDs | Public AppView can be incomplete; original-source setting applies |
| Constellation | Independent follow-record backlinks for an already known DID | Incomplete historical backfill; record owners are sampled before outgoing follows |
| Mastodon-compatible | Account lookup, featured accounts, follows/followers | Instance restrictions and hidden lists; endorsements requires supporting server versions; one extra same-account page |
| Reddit / Arctic Shift | Recent archived interactions, communities and sampled community authors | Past-30-day observation window; archive coverage and response time vary |
| Independent publishers | HTML/HTTP feed declarations, XFN, OPML, Ghost recommendations | Explicit declarations first; no guessed site-wide crawl |
| Existing RSS feeds | Feed-level Podroll URLs and blogChannel reading lists | GUID-only recommendations lack a resolved feed URL; no audio is downloaded |
| YouTube bridge | Literal channel-page metadata, creator-selected channel shelves, scoped About links; Invidious alternatives before original fallback | Not autoplay or suggested-video ranking; exact stable channel ID/returned handle required; original-site setting applies |
| SoundCloud bridge | Public profile hydration, featured profiles, follows and web-profile declarations | Public app identifier comes from the page; no login, tracking scripts or browser execution; API availability can vary |
| Mixcloud bridge | Public profile and following reads | Public source-selected sample; not all listeners |
| TwitchMap bridge | Public `data.json` sampled shared-chat edges | Limited channel/time panel; absent accounts are outside observed coverage, not zero-overlap claims |
| Substack bridge | Public profile links, owned publication feeds, published recommendations and public reading lists | Users and publications have distinct IDs; private subscriptions are discarded; no Notes-reading or complete-audience claim |
| Existing public posts/discussions | Exact links, forwards, public participant intersections | Only material actually acquired locally; no background global graph collection |

Truth Social, Pixelfed and Gab can use compatible public federation routes when
provided by their hosts. Those implementations have not all been live-qualified.
Other supported social accounts can benefit through existing post references,
participant observations where exact authors are available, and identity bridges.
This does not imply that Facebook or Instagram exposes a complete public graph.

## Scheduling, privacy and storage

Two live collection lanes stream independent results. Each block claims at most
six graph tasks and is limited to 3 MiB / eight actual gateway requests, including
redirects. The app session allows at most 64 endpoint attempts, 160 displayed native accounts,
320 candidate buckets and bounded second hops. A session can end before all
candidate paths are visited. Per-request and per-block deadlines bound waiting.
Quick reads allow seven seconds directly or twelve over Tor. Archive aggregation
allows fourteen/sixteen seconds inside the same eighteen/twenty-eight-second
block, so a slower index can still stream results after cheaper work completes.

Graph routes reuse Darwin's adaptive latency, cooldown and eligibility machinery
under distinct discovery route identities so a failed graph endpoint does not
poison ordinary profile reading. Original sources follow available alternate
routes. All acquisition uses NetworkGateway and existing automatic-link,
protection-list and redirect policies. Selected Tor fails closed. Settings changes
invalidate the active policy rather than silently switching transport.

Identity work is secondary: two root-expansion slots cover at most twelve
providers and eight bridge identities. Two additional enrichment slots handle at
most sixteen visible native identity lookups and twenty-four bridge-neighbor
lookups. The first four neighbors of each bridge method are prefetched; subsequent
work follows the viewport. Each lookup retains its existing byte/request/time
allowance. This is a bounded neighborhood, not a global crawler.
The active progress indicator includes seed linkage; individual cards identify
in-progress identity enrichment. Published rows append or enrich without
reordering beneath the reader's finger.

Bridge readers admit at most 3 MiB per document and 8 MiB / five actual requests
per lookup. They parse literal metadata/JSON only, execute no downloaded code and
discard the page husk. A typed memory cache holds at most thirty-two entries /
1 MiB for fifteen minutes, keyed by transport policy and clear-cache generation.

Neighborhoods use ordinary cache: fresh for 15 minutes, retained for at most six
hours, at most 48 snapshots / 2 MiB. Public participant observations retain no
comment text, expire after seven days, and are capped at 2,048 edges / 512 KiB.
Neither cache uploads a reader's graph. Clear-cache generation guards reject late
writers. Saves, explicit downloads and subscription records are unaffected.

Checking again retains unexpired positive cards, bridge methods and resolved
aliases while requesting fresh data. Partial/empty responses cannot replace a
richer neighborhood or extend the original evidence's lifetime. Empty successful
pagination does not mark the account's working first page unavailable. Child
enrichment shares the parent operation's byte/request allowance.

The existing idle account-website lookup now preserves small typed recommendation
and feed declarations from its already parsed response, including HTTP headers.
Discovery reuses them for up to the existing 30-minute warm-cache lifetime. It
keeps their original observation date, does not retain the HTML, and does not train
network route speed from a cache hit.

## Reuse outside discovery

Telegram posts and offline snapshots preserve typed forward provenance. The
article reader preserves scoped Article/Report JSON-LD citations before text
washing so Context's existing bounded source resolver can follow actual cited
work. Organization/footer citations and ambiguous unrelated article declarations
are excluded. Neither addition changes Context's AI requirements or causes
unbounded supplemental retrieval.

See [source investigation notes](DISCOVERY_SOURCES.md) for inspected projects,
other consumers and deliberately unactivated routes. Android gateway probes and
native screens are emulator qualification, not a physical-phone battery benchmark.

## 0.38.0-rc1 qualification

- Full core suite: 1,829 cases, zero failures, one optional dependency skip.
  Final targeted linkage/bridge/scope/graph suite: 50 cases passed.
- API 35: final discovery, cache, cleanup and live-source batch passed all
  17 cases. This includes fresh-cache identity expansion, all local roots,
  stable method order, cancellation and unavailable-versus-empty bridge status.
- Live twelve-account NASA fixture automatically produced X and Reddit methods,
  checked the available Mastodon routes, and added a YouTube method with readable
  social accounts. No manual continuation or source selector was used. Empty
  Mastodon lists remained empty observations. The observed run reached this
  state in about thirteen seconds; this is not a phone latency guarantee.
- Live gateway checks recovered Kennedy Space Center's X/Facebook/Instagram and
  Learn With NASA's X/Facebook/Pinterest board from their YouTube channels, and
  NASA's SoundCloud following relationship to ESA.
- Related reading-progress, portability, names, account-linking, scoped search,
  privacy and share-route batch: 77 passed, one live opt-in skipped, one timing
  assertion failed because the initial search was requested twice. That exact
  search-scope test passed on an isolated repeat. No search behavior was changed
  to conceal the observation.

Actual native deck, filtering and live NASA screens were inspected. Physical-phone
battery, throughput and every proposed research adapter remain unqualified; the
source investigation table distinguishes active routes from future candidates.
