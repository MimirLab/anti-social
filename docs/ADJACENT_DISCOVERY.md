# Discover related

Open a public account and tap the compass beside **Subscribe locally**, or choose
**Discover related** from a subscription's menu. This is adjacent-account and
community discovery. **Find linked accounts** remains the separate identity tool.
No AI, social login, developer key or extra download is required.

Cards appear as independent sources finish. Platform icons filter the collected
results; the magnifier searches within them. Tap an account to read it, the
person-plus to subscribe, or its connection reason to inspect the evidence and
observation date. **Sources** distinguishes unavailable routes from usable empty
lists and reports sampled coverage. **Explore further** performs another bounded
block; closing discovery cancels its work. The reading feed stays chronological.

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

The original account's graph starts independently of identity resolution. In
parallel, existing account linkage can supply up to two useful bridge identities
on graph-capable platforms. Matching usernames and arbitrary bio links cannot
establish common ownership. Bridges use explicit local subscription grouping,
exact directory associations, or a single scoped website identity declaration that explicitly lists both accounts. A shared website or display name is insufficient.

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
| Existing public posts/discussions | Exact links, forwards, public participant intersections | Only material actually acquired locally; no background global graph collection |

Truth Social, Pixelfed and Gab can use compatible public federation routes when
provided by their hosts. Those implementations have not all been live-qualified.
Other supported social accounts can benefit through existing post references,
participant observations where exact authors are available, and identity bridges.
This does not imply that Facebook or Instagram exposes a complete public graph.

## Scheduling, privacy and storage

Two live collection lanes stream independent results. Each block claims at most
six graph tasks and is limited to 3 MiB / eight actual gateway requests, including
redirects. A session allows at most 24 endpoint attempts, 80 displayed accounts,
128 candidate buckets and bounded second hops. A session can end before all
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

Identity work is secondary: at most six visible-result identity lookups per
session, one at a time, using existing identity caches and request allowances.
The active progress indicator includes seed linkage; individual cards identify
in-progress identity enrichment. Published rows append or enrich without
reordering beneath the reader's finger.

Neighborhoods use ordinary cache: fresh for 15 minutes, retained for at most six
hours, at most 48 snapshots / 2 MiB. Public participant observations retain no
comment text, expire after seven days, and are capped at 2,048 edges / 512 KiB.
Neither cache uploads a reader's graph. Clear-cache generation guards reject late
writers. Saves, explicit downloads and subscription records are unaffected.

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
