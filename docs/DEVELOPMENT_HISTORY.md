# Development and validation history

This is an archived product/build snapshot followed by historical implementation
records. Version names and descriptions below belong to those records. For the
current contract, use [capabilities](CAPABILITIES.md),
[architecture](ARCHITECTURE.md), [release qualification](RELEASE_QUALIFICATION.md)
and [building](BUILDING.md).

# anti social

**Archived snapshot: 0.35.47.** A native, accountless reader with chronological
feeds, local subscriptions and saved copies, and optional AI. The ordinary
reader works without a model, API key or root access.

[Download the latest Android APK](https://github.com/MimirLab/anti-social/releases/latest/download/anti-social.apk)
or read the [release notes and corresponding source](https://github.com/MimirLab/anti-social/releases/latest).
Android 8.0 or newer is required.

Local search now checks publisher coverage and occurrence locations separately,
adds public community/calendar readers, and resolves uncertain nearby addresses
within bounded traffic limits. New cards still arrive independently and enrich in
place. See [local opportunities](../docs/LOCAL_OPPORTUNITIES.md) for the chosen sources,
regional limits and deliberately excluded scope. No AI is required.

The local text engine remains an on-demand install: choose a local
text model in **Settings → AI models** to install it, or remove the engine there
while keeping downloaded models. Remote AI and ordinary reading do not install
it. See [runtime delivery](../docs/LOCAL_RUNTIME_DELIVERY.md) for the implementation,
storage requirements and tested limits.

The published APK retains the project's existing development signing key for
update compatibility. Dedicated production signing and physical-phone GPU
qualification remain outstanding. See [validation](../VALIDATION.md) and the build
instructions below before contributing. See [publishing](../docs/PUBLISHING.md) for
the permanent download URL and release procedure.

The release identity is `app.antisocial.android`. The supplied break-free emblem and
wordmark are native vector assets; see [branding and identity](../branding/README.md).
The historical workspace and build-volume paths remain unchanged.

Native Android reader for public social links, carrying Darwin's rebuilt routing,
privacy, link cleaning and frontend presentation system into a native app.
Social sources: X, Bluesky, Reddit, Tumblr, TikTok, Instagram,
Pinterest, iFunny, Tieba, Threads, Facebook, Truth Social, Mastodon, Lemmy, Pixelfed,
Misskey, GETTR, Gab, Telegram, LinkedIn and Snapchat, alongside ordinary RSS/Atom feeds. YouTube is a linked-video medium; it has no social tab, channel-upload feed or standalone search. Kotlin/Compose renders extracted JSON, RSS/Atom and HTML data;
there is no WebView, social login or algorithmic feed. YouTube uses NewPipe Extractor
for permitted accountless original fallback; it may decode player signatures locally.
Its downloader, stream manifests, both audio/video tracks and recovery probes all
use NetworkGateway with the same filters and fail-closed optional Tor.
anti social runs as an ordinary Android app on stock, unrooted phones. Users do not need
root, ADB, a custom ROM or a privileged installation; ADB is a development/test tool.

## Context (0.35.17)

Context also checks that its reading inputs stay current while the model works.
An edited parent or changed English reading cancels stale results, including
“nothing new” decisions. Earlier Context and saved copies remain readable while
the changed material waits for another check.

Context reviews known earlier replies before their children, even when public
sources deliver the conversation backwards. Literal questions and their supplied
closed answers avoid model votes that could discard them. Reading order in the
feed stays chronological.

Short replies keep their earlier question and actual speaker. Identical “No”
answers to different questions stay separate in the brief and Findings, with
“Read earlier reply” opening the preserved local source. Missing, partial or
uncertain context remains explicit. Prepared English views feed composition;
original-language copies remain available locally.

Cached Context now stays tied to the source and reply-context reviews behind it.
Matching quotation words alone cannot make older compaction work current after
a supporting review changes. Empty reviews keep unchanged work reusable.

Context keeps its brief and local citations together when a source changes while
the model is working. An outdated reply waits for another review; the earlier
brief and saved copy remain readable. Saving during generation also preserves
the reader's latest choices.

Local source excerpts now explain when the available text is captions, recognized
image text or a transcript, including recorded uncertainty and incomplete
extraction. Those distinctions remain attached through compaction. Bounded
edition previews keep the earliest and latest known dates and an undated source
visible; expanding still reveals every retained edition.

Known publication times stay attached to source statements and merged editions.
Identical wording posted on a different day can mean something different, so
Context retains those dates instead of treating them as an irrelevant copy.
Readable UTC dates also appear beside local source excerpts, separately from
when the app retrieved them. Unknown dates remain unknown.

Context's saved processing work is bound to the chosen model, usable context
window and processing recipe. Changing them takes effect on the next requested
or opted-in read. Previous readable Context and its citations stay available
while replacement reviews are pending; explicit saved snapshots remain intact.
Unchanged reviews and exact additional copies still reuse compatible work.

Context can prepare foreign-language source text in English locally before the
selected text or embedding model reads it. Original quotations remain available.
Offline language packs download separately through the app; unused automatic
packs expire, while explicitly downloaded languages stay until removed. AI-off
reading remains independent of this feature.

Spanish has end-to-end checks with the actual 2B/4B models; native translation
also passed targeted German, French, Ukrainian, Arabic and Japanese checks.
These examples do not certify every language or translation. See
[validation](../VALIDATION.md) for exact coverage and remaining hardware limits.

With a ready text model enabled, each post offers **Context**: an on-demand brief
of its collected editions, available public discussions, readable attached pages
and available video captions. Inspect findings and exact local source excerpts
from the native reader. New information waits behind **Updates ready** while you
read. AI stays optional; the ordinary reader and feed filters need no model.

Settings → **AI models → Context** holds optional read-ahead, saved-post updates,
local image-text and speech tools, and advanced reading limits. The app controls
source access and processing; models have no tools. Completed source revisions
and comment cursors are remembered. Active model-window metadata guides bounded
processing and compaction; unknown servers use a conservative budget. Public
source availability and model interpretation remain fallible. See the
[Context guide](../docs/CONTEXT.md) for coverage, privacy, storage and runtime limits.

Comment reviews share the stored merged post and use explicit reply-parent
relationships where a source supplies them. The planner keeps complete bounded
background when the counted request fits, then falls back to marked excerpts. A missing
parent remains unavailable, and its later arrival reopens affected reviews.
Changing a parent does not discard the earlier published brief or its citations.

Context now routes the *work* as well as its token budget. Phone models receive
focused semantic tasks; larger reported server models can review source batches
and compare broader groups of evidence. Parameter count and active reading space
remain separate observations. Unknown model sizes use conservative individual
review; advanced settings offer a remote processing style without changing the
selected provider. Every path keeps source receipts and exact-quote validation.

Phone-sized models select coherent source passages instead of rewriting their
findings. Short relevant contributions retain their complete qualifications.
When the selected material already fits the counted reading allowance, code
assembles attributed excerpts without another model call. Larger discussions
still need compaction; generated interpretations remain fallible.

An AI-abbreviated quotation can now recover its omitted original context when
the passages match uniquely and in order. Source excerpts retain the actual
text; ambiguous or invented quotations remain pending.

An additional complete source copy now updates citations and author associations
without rewriting an unchanged brief. Earlier supporting documents, quotations
and English working text must remain intact. Changed wording, background or
extraction quality still reaches normal composition; identical wording does not
establish independent corroboration.

Remote and self-hosted models can now use their intended two-minute request
budget while preparing an answer. A slow non-streaming reply no longer inherits
the shorter website read timeout. Stop/cancellation and provider privacy rules
remain in force.

## Feed filters (0.34.2)

Use **Filters** above a chronological feed or search, **Filter like this…** on a
post, or Settings → **Feed filters**. Create named rules for literal phrases,
hashtags, social sites, website links, media types and already-confirmed repeat groups.
**Posts from** uses icon-only buttons in exactly the order of the reader’s
chosen feed tabs, updating when those choices change. Every chosen site is
shown; the chevron reveals the rest. Existing rule selections outside the
chosen feed remain visible for editing. The globe selects all sites. Social
marks retain screen-reader names; selected tiles are highlighted. **Use in** offers Feed and magnifying-glass Search
controls. Choosing a single site tailors the editor: Reddit gets subreddit rules;
Mastodon, Lemmy, Pixelfed and Misskey get original-server rules; image/video sites
use caption/photo terminology, Telegram uses messages, and RSS uses article text.
Subreddits require a proven original Reddit post path; server rules compare exact
original post hosts, never retrieval servers or linked websites. Unknown membership
does not match. Source-dependent fields such as flair are not guessed. Existing
details stay visible when switching site choices, and incompatible choices must
be corrected before saving. These match the original social platform, independently of the frontend,
archive or search provider that supplied the content. **More options → Links to a
website** accepts pasted links and matches their website rather than their path.
Rules can apply everywhere, to a verified account, a personal channel or an exact
search. More options include exceptions and expiry.
Preview cached examples before saving. Saving never starts a crawl or marks posts
read; **Apply** adopts changed or expired rules in the reader deliberately.

**Always show** overrides restrictive rules. **Collapse** keeps a reason and a
one-tap reveal; **Hide** removes the card from the displayed list and keeps it in
the batch's **Review** drawer. Review uses ordinary bounded cache, not another
permanent archive. **Collect repeats** uses the existing verified merger and
does not group unrelated posts that merely match the same rule. Original wording,
attribution, links, saved copies and source comment streams are preserved.
Promotion presets match disclosed wording; they do not classify intent. Comments,
direct links and saved-library views are outside this first filtering release.

Entries within each condition section use OR; populated sections use AND and
must match a single source edition. Exceptions in any known edition spare the
whole collected post. Account scope uses proven original-URL account identity,
never a display name. Partial or missing extraction is not evidence of low value.
An all-filtered search pauses automatic pagination and offers an explicit next
batch instead of silently crawling indefinitely. Pending subscriptions remain
pending; a caught-up boundary distinguishes filtered material from read material.

Reading memory now distinguishes additional collected context from exact repeats.
An unseen passage or attachment cannot inherit another edition's read mark.
Admitted refreshed content at the same URL invalidates its old read mark; counters,
fetch timestamps and renewed signing tokens do not. Revision metadata is local,
bounded by the reading-memory budget and expires after inactivity. Unknown legacy
revisions are adopted once; this is source-content comparison, not semantic
judgment or a claim that every public edit has been discovered.

Recipes use UTF-8 JSON: `{"version":2,"rules":[...]}`. Version 1 imports retain
their original meaning with no social-site restriction. Version 2 adds required
`platforms` (a list of `Platform` enum names, such as `X`, `REDDIT`, or `FACEBOOK`;
empty means all sites), plus `subreddits` (names without `r/`) and `servers`
(exact original federated host names). Empty lists add no restriction. This matches each original edition's platform and is ANDed
with its other conditions. Older readers reject version 2 rather than broaden a rule.
Other rule fields are `id`,
`name`, `enabled`, `action` (`KEEP`, `COLLECT`, `COLLAPSE`, `HIDE`), `scope`
(`EVERYWHERE`, `ACCOUNT`, `CHANNEL`, `SEARCH`), `scopeKey`, `surfaces` (`FEED`,
`SEARCH`), `phrases`, `hashtags`, `domains`, `types` (`TEXT`, `IMAGES`, `VIDEO`,
`AUDIO`, `LINKS`), `exceptPhrases`, `repeatsOnly`, optional `expiresAt` (Unix
milliseconds), and `revision`. Limits: 128 rules, 32 terms per section, 160
characters per term, 80-character names and a 1 MB recipe. Unknown versions,
fields, duplicate keys, malformed types and unrestricted global rules are rejected.
Export/import has a selection preview; recipes contain no history, inference
results or credentials. Account scope keys use `CrossPosts.originalAccount`;
channel keys use the channel's ID; search keys use its canonical search URL.
Standalone recipes retain these scopes, so a missing channel never becomes a
global rule. Native reader-state exports include the encoded recipe in optional
`state.filters.contentRules`; imports merge rules and remap imported channel IDs.
Source-only packs remain sources only. Older readers may reject this new optional
native field rather than discard the rules silently.

No model is required for filtering. Per-post generative classification is not
part of this release. Typed decision models remain a separate qualification task:
Laya's [Java implementation](https://github.com/NandhaKishorM/laya/tree/d74e8be46ef5241e4ba2b16cd25e896f24f71373/laya-java)
explicitly lists Android as unimplemented, and its
[model card](https://huggingface.co/convaiinnovations/laya/blob/7b928d828b7b0e022f929d9bd2e44165aa270148/README.md)
documents domain, language and calibration limitations. Desktop or advertised
latency is not an Anti Social phone measurement. No automatic AI noise decisions
are enabled without representative retention tests and an actual Android runtime,
memory, latency and battery audit.

## Optional AI foundation (0.33.0)

AI stays off by default. The ordinary reader, its chronological feeds, local
matching, account names and content-declaration evidence continue without a model
or API key. Settings → AI models provides independent text/embedding setup,
verified resumable local downloads, pause, disable and removal (also while off).
Bonsai 2B, 4B and 9B choices show a conservative hardware-based Recommended tag;
these are resource estimates, not universal phone-speed certifications.

The local text engine installs on demand into a private application process.
Its compact installer reconstructs the exact custom Android binary using pinned
upstream bytes plus our packaged delta; no compiler or root is needed on the
phone. Removing the engine can keep model downloads for later. See
[runtime delivery](../docs/LOCAL_RUNTIME_DELIVERY.md). ARM64 includes
Vulkan with CPU fallback; x86_64 has a portable SSSE3 path. The unchanged 8.65 MB
English SSE embedding pack uses the CPU. OpenAI/ChatGPT, Anthropic/Claude,
DeepSeek and Z.ai have standard cloud slots, alongside your local server and
multiple custom providers. Each text/embedding role has Local first, Device only
and Use selected provider choices. Local first is the default; choosing cloud
never requires deleting local models. Keys stay encrypted outside reader exports.
All networking uses NetworkGateway; a selected Tor connection never silently
falls back to direct traffic. A local setup/request failure never silently
uploads its sources to a configured cloud provider.

This release establishes inference/provider access and installation. TLDR,
semantic search, AI-assisted merging and experimental attention-cache codecs
remain separate work; no reader feature has been replaced with AI. See
[architecture, exact models and build steps](../docs/AI_FOUNDATION.md) and the
[implementation/evidence ledger](../docs/AI_FOUNDATION_LEDGER.md).

## Readable names and collected updates (0.31.0)

Recognized account mentions display as linked public names throughout posts,
replies, repost attributions, profile descriptions, search cards and saved copies.
The name bank also handles reply/repost punctuation (`@WhiteHouse:`), leading
`.@VP:` notation, exact linked Facebook mentions and Reddit's user notation. Profile URLs
and author-bearing permalinks can also identify plain author labels without
guessing an account from somebody's display name.

While a public name is unavailable, recognized accounts show a clickable
identifier without `@` or `u/` notation. This fallback is not an invented name.
Tap it to inspect the exact handle, platform and authentic account URL. Source
text, saved copies, search indexing and shared links remain unchanged. Email
addresses, code, URLs and ambiguous identities are not blindly rewritten.
Saved readers use only locally remembered names. Supplied public author names
do not require an additional name lookup; missing names retain the bounded,
deferred lookup and expiry policy below. The Account names settings can restore
raw source presentation or disable supplementary lookups independently.

The reader also collects differently addressed versions of an update when public
text, imagery and source evidence support it. Shared passages appear once;
additional wording keeps its source attribution, and every original discussion
remains available in All or its platform tab. Identical images are shown once.
Closely related interview frames can share a card when the quoted account,
publisher or proven curator, subject wording and six-hour window also agree.
The original captions and URLs remain available in Collected originals.

This is bounded local matching, with no online AI service or extra image fetches.
After reopening, the comparison bank can quietly reuse already-cached pictures;
that local work yields to essential feed and media requests.
It does not infer universal semantic equivalence from a shared name or photo.
Changes to claims, corrections and independent commentary remain guarded.
Saved editions retain their tiny local image observations so an offline collection
can reopen after the ordinary image-comparison cache is cleared.

## Linked video, bounded scope (0.30.0)

Social subscriptions populate chronological feeds. YouTube videos linked from those
posts, shared into anti social, or opened from saved material use the native player with
network filtering, SponsorBlock, deliberate playback and exact-video recovery.
Video comments remain optional context. There is no recommendation feed, automatic
next video, YouTube platform tab, channel subscription or standalone video search.
Opening a video preserves the social reading scope and Back returns to it. The
player appears before the description, which expands on request; the complete
source description and any retained collected context remain available.

Android's YouTube video-link handler is independent of selected social tabs; the
system still controls default-link approval. It handles watch, embed, Shorts, live
and short video links, while channel/search/playlist URLs are not claimed on the
regular YouTube domains. Pasted channel or search links get a local explanation
without loading an upload feed. Community-post retrieval is not implemented.

Older YouTube channel records remain local and exportable under **Subscriptions →
Older video channel entries**, with removal controls. They do not refresh, appear
in social feeds, hold caught-up boundaries pending, or participate in heartbeat
probes. Saved videos, downloads and text remain protected. Older tab settings are
interpreted as social choices without rewriting their stored order; a YouTube-only
selection falls back to the normal social defaults. Source packs cannot add channel
subscriptions. Import previews explain skipped channels and still allow saved
YouTube material; full native exports retain older records for compatibility.

## Readable account names (0.29.0)

Recognized mentions such as `@POTUS` can display the account's supplied public name
instead. Tap the name to inspect its original handle, platform and authentic account
link, or open that account through anti social. Display names are account labels, not
verified legal identities. Source captions, duplicate detection, saved copies and
exports retain the complete original wording.

Names already supplied by profile/discovery reads seed the local bank. Missing names
are requested only for visible text after essential work becomes idle; collapsed
reference inventories, offscreen paragraphs and prefetched cards do not start reads.
One supplemental profile lookup runs at a time. Feed/search work and actual image or
video traffic cancel it immediately. Every request uses Darwin's routing and
NetworkGateway, including blocklists, original-access permission and fail-closed
optional Tor. Cached names can display offline; saved-library readers do not start
background name requests. Later name replacements wait for scrolling and pointer
interaction to settle.

The separate local bank holds at most 1,024 records / 1 MiB of bounded payload, with
bounded SQLite overhead and journaling. Entries unused for 30 days expire, regardless
of their old popularity. Recency and view frequency decide eviction when full; view
writes are coalesced. Seven-day-old labels refresh on a later view. Misses back off
from six hours to seven days, with separate retry decisions after Tor selection or
original-access permission changes. Public names remain reusable across those modes.
The bank is ordinary disposable cache, counted in storage and excluded from portable
reader exports; saved records and explicit downloads remain protected.

**Settings → Account names** controls readable mentions and background lookups,
and can forget remembered names. Turning off missing-name lookups keeps locally
known names available. Those preferences travel with native reader settings.

Lookup requires an exact supported account identity and an explicitly supplied
public display-name field or dedicated profile-name element. Generic page titles,
login pages and fallback usernames are not name evidence. Email addresses, URLs,
code annotations and unsupported or ambiguous mention forms stay intact. Bare
Facebook/LinkedIn mentions need a supplied exact account link; a visible tag name
does not establish a vanity URL or numeric identity. Tieba visible labels
are not guessed as stable account IDs. Reddit `u/name` is supported. Federated keys
include their actual server. A fused passage uses one real source edition's mention
context and links; identical handles on different platforms are never a global
identity. Some sources cannot expose names, and YouTube's original handle-to-stable-
channel alias is currently left unresolved rather than assumed equivalent.

## YouTube and quieter post details (0.28.0)

Linked YouTube videos have native playback, original-link handling and optional
comments. Channel aggregation introduced in 0.28 was removed in 0.30. CommentAnywhere uses the authentic `youtu.be/VIDEO_ID` permalink
for each video because its current service normalizes away URL queries; it
never collapses every YouTube conversation into `/watch`. Discussions created
under other URL forms are not guaranteed to be shared by that service.
Invidious and Piped public data readers precede the permitted NewPipe
original fallback. APIs may be disabled, challenged, blocked or unavailable;
reachable pages and metadata-only archives are not certified playable streams.
Native playback chooses combined content streams or joins separate audio/video
tracks. Page advertising and telemetry are not loaded; all actual requests still
pass the existing network blocklists. This is not a promise that blocklists alone
can remove advertisements spliced into a media stream.

**Settings → SponsorBlock** contains the master switch and each community skip
category. All nine range categories, mute-marked ranges and the highlight jump
start enabled, including filler, hooks, self-promotion and non-music sections.
These aggressive defaults can omit context; each can be disabled. Chapters and
full-video labels never discard an entire video. Segments must agree with the
actual playable duration. A deliberate seek back allows watching skipped content.
Each lookup requests one bucket identified by four hexadecimal hash-prefix characters, selects the exact video locally,
never submit viewing reports and use the selected connection. The ordinary
annotation cache expires in 24 hours and is capped at 96 records / 2 MiB.
Local-only saved playback can use retained annotations without contacting the
service. SponsorBlock contributor data is CC BY-NC-SA 4.0; see its bundled notice.

Video preparation/playback errors try other viable content routes and then
bounded, exact-ID archive recovery inspired by
[YouTube Video Finder](https://findyoutubevideo.thetechrobo.ca/). Recoverable copies
play in the same native interface; a small archive attribution identifies the
substitution. The reader does not open the finder site, upload, request a new
archive or substitute a different video merely because its title matches.
Wayback, Ghostarchive, PreserveTube, Internet Archive and Hobune protocols have
independent readers; some can be unavailable. Recovery requires an actual media
prefix, including audio when separate, and still cannot promise that an entire
recording survives. See [recovery design and evidence](../docs/YOUTUBE_RECOVERY.md).

Saved YouTube video downloads retain the existing explicit Wi-Fi/storage controls.
The app resolves a downloadable combined stream when available; separate-track
or adaptive-only offline downloads are not yet muxed. Saved text remains useful
when no downloadable video exists. Stream addresses are ordinary expiring
transport, while outgoing links stay authentic YouTube URLs.

Detached **leading and trailing** mentions, hashtags, links and link-only calls to
action now share one expandable details inventory with attachment links. Even a
single detached reference can fold after a substantive message; a post's repeated
permalink no longer consumes a separate open row. Links within the message,
meaningful prose and credits remain readable in place. Original text, saved copies
and exports remain complete.

## Collected reading and resilient links (0.27.0)

A refresh gathers each subscription's available cursor pages back to its previously
fetched newest post before publishing the combined update. Source work is bounded
to three concurrent subscriptions, eight real cursor pages / 300 posts per source,
and 45 seconds direct or 90 seconds with full Tor. A first subscription loads its
first supplied window rather than harvesting its full archive. Broken/repeated
cursors, partial or failed checks and exhausted budgets stay visibly pending;
retained readable posts remain available. A source without a continuation can only
provide its own finite window. This is not a guarantee of complete platform history.

The whole collected newer prefix becomes scrollable automatically at idle. The
visible card, its wording, media and pixel position remain steady; its additional
source/comment controls can grow in their reserved space. No Show or repeated
Continue clicks are needed to uncover fetched arrivals. **Newest** jumps to the
head and preserves **Continue** as a return to the earlier reading point. Unseen
posts stay unread. Pull down at the newest edge or tap Refresh for a manual check.
A successful manual check with no meaningful new content says **Slop consumed, go
be productive.** An unavailable subscription prevents that claim.

Confident copied posts can now collect across different accounts as well as across
platforms. Every publisher appears in the card's scrollable author row, every
original stays inspectable, and comments retain their distinct source roots. The
oldest known original supplies the chronological position so reposts do not promote
old material to newest. Exact substantial captions need 24 words, 160 characters
and 12 distinct words, and stay within 30 days without media/reference evidence.
Actual shared media or a true original-post reference can corroborate an older or
undated exact copy. Short captions require stronger media or true original-reference evidence. Contextual
variants need dominant ordered shared prose, media/reference corroboration and a
48-hour window. Matching is pairwise across the whole group; similarity chains,
conflicting claims, rebuttals and short generic slogans do not silently collapse.
Source-specific extra context is attributed to the exact contributing accounts.
Saving/exporting preserves these original records and captured threads.

On feed cards, detached leading and trailing reference inventories and attachment links fold into
one expandable details area. In-sentence references, meaningful prose and credits
stay visible. This only
changes presentation: originals, saved copies and exports retain the full text.

Copy/share and outward browser actions prefer authentic original addresses. On an
explicit action, a small expiring route cache and bounded NetworkGateway reads
verify the exact post, rather than trusting a reachable homepage or API response.
If the original is confirmed missing, the next choice is a verified exact reader
or archive, then an identical retained original. Selected fallback URLs stay intact
in the clipboard/browser instead of being rewritten to the broken original.
Attribution labels continue to name the authentic source. Checks never run merely
because a card is rendered. Login walls, timeouts and Tor failures do not establish
deletion. An offline unverified original may still be copied with a notice when
no known deletion or privacy rejection exists; opening original sites respects the
original-access setting. None of this requires root or a social account.

## Discover accounts and communities (0.26.1)

Search adds a horizontal shelf of genuine accounts and communities beside the
native post feed. Its label fits the selected platform: **Pages & people** on
Facebook, **Subreddits & users** on Reddit, and corresponding labels elsewhere.
The shelf opens the original account or community; post authors do not become
matching directory results merely because their posts appeared.

Directory discovery runs in its own cancellable job, so post results appear
without waiting for it. **Stop**, navigation and a new search cancel pending work;
results already retrieved remain available. The directory has its own bounded
first window and never consumes or replaces the post-search cursor. Facebook's
existing post reader, selected-provider two-query discovery and progressive
destination reads remain in place. Scoped searches skip the extra broad directory
lookup and retain their chosen account, subreddit or other scope.

Facebook has separate anonymous page and people searches. Developer-host requests
through NetworkGateway returned **7 page results in 6.794 seconds** and **8 people
results in 1.787 seconds**, in separate first-window observations. These are
source observations, not app or phone benchmarks, and Facebook's ranking does
not establish geographic relevance. Reddit gains an independent community
directory through supported Redlib-family frontends or permitted original access;
Bluesky gains an independent actor directory. Those new Reddit and Bluesky paths
have not yet been live verified for this release. Phice directory support follows
its public search-card format; live Phice directory coverage remains unverified.

Where needed, the selected public index discovers authentic account/community
links, then anti social obtains actual metadata from the routed destination before
showing a shelf card. An index title or snippet is insufficient. The shelf is
bounded to 40 results and shares the displayed text/metadata budget; the existing
three-session search cache, ordinary cache limits and expiry remain in place.
Saved material and explicit downloads keep their existing protection. All
requests use NetworkGateway, filters and the selected connection policy.
Alternates precede permitted originals; commercial indexes still require Tor and
fail closed, without silently switching providers. Live full-Tor directory access
and physical-phone behavior remain unverified. Darwin provenance and the existing
GPL/source notices are preserved.

The installed **0.26.1/code 37** native preview returned **16 genuine page/person
records and 11 public posts**, with no index-snippet cards, for
`melbourne organic food`. The shelf and horizontal swipes were inspected on the
native screen. This is a point-in-time observation; results and ranking can
change. The release passes 905 core and 79 Android tests; build, lint, retained
state and source limits are recorded in [VALIDATION.md](../VALIDATION.md).

## Public post search (0.26.0)

Facebook word searches can now retrieve actual public post records through its
anonymous search operation, with returned cursors bound to the same source,
query and search operation. Public Phice search is an alternate path; its next
links also retain the selected query and post-search scope. This is an
independently implemented reader informed by [Phice's public protocol research](https://github.com/Willie169/phice),
with no social account, account token or paid API key. The installed native app
returned 12 newly fetched public posts for `melbourne organic food`, with no
profile or index-snippet cards; the first post was observed by 5,092 ms in one
normal UI run. This sampled upper bound is not a benchmark or a coverage
guarantee. A separate host probe returned six posts. Protocols and anonymous
access can change; see [VALIDATION.md](../VALIDATION.md) for evidence and timing limits.

When Facebook discovery reaches the selected public index, two bounded queries
can run concurrently: one focused on post links and the existing broad query.
They use the same selected provider, deduplicate authentic destinations and give
posts priority over profiles. An actual post destination can return promptly
while its unfinished companion remains in a bounded continuation. Profile-only
results wait for the post query instead of repeatedly cancelling it. Each lane
has a ten-second request bound and at most four attempts; this does not manufacture
pagination when a provider supplies none.

The feed can overlap the next real discovery cursor with two destination reads,
publishing readable cards as they arrive. Indexed titles and snippets remain
discovery hints; they never become invented social posts. A session is bounded to
240 destination candidates, 20 discovery pages, 200 posts, 40 account cards and
approximately 1 MiB of text/metadata. Filling the account shelf does not stop post
discovery. The old two-unreadable-block cutoff is removed: valid public-index
continuations can reach later posts after filtered, duplicate or unreadable
blocks, within those limits. Repeated cursors and source-specific end conditions
still stop traversal.

**Stop** pauses the search and keeps retrieved results. **Continue search** resumes
it without treating the pause as an error. A failed source or unreadable public
destination is reported as unavailable, rather than evidence that no matching
post exists.

Native Facebook search follows the ordinary content connection setting and the
global Tor choice. Commercial public indexes still require Tor and fail closed;
their queries never silently switch providers. Alternate routes precede permitted
original fallback. Every request uses NetworkGateway and the existing filters.

## Continuous catch-up (0.25.1)

Opening Following, All platforms, a person's feed or a personal channel includes
all currently available fetched posts in that view, including newer posts above
the restored reading point. Your exact post and pixel offset remain the starting
position; scroll upward toward newer posts without first pressing Show.

Posts arriving during an active reading session are collected across completing
subscriptions before presentation, then inserted above the saved position at idle.
The visible card and offset remain steady. **Newest** jumps to the top and keeps
**Continue** available for returning to the earlier reading point. Neither action
marks unseen posts read. The read/unread labels, stopping boundary and pending-source
checks retain their existing meaning.

This covers the current fetched local window, bounded to 1,000 eligible posts.
Sources can omit older posts or provide only one feed page; this does not imply
that every post between the saved position and the present was retrieved. Expired
or out-of-window anchors resume nearby with a notice. An unavailable subscription
stays visibly pending even when other sources supplied newer posts.

## Repeated originals (0.25.0)

Repeated posts from the same public account can share one chronological card when
known dates fall within 48 hours and their captions meet the existing strong
matching rules. Original author URLs establish the account; a matching display
name or subscription folder does not. A source's explicit repost can also match
its own later post when the same Nitter curator feed, validated repost attribution,
exact video clip identity and a distinctive quoted passage establish the connection.
A shared picture or common topic alone is insufficient. The later 0.27.0
copied-content path above can also collect genuine copies from different authors
and independently corroborated undated copies. Groups contain at most 64 originals;
ambiguous or unsupported matches stay separate.

**Originals** opens every retained caption, author, date and authentic link, with
an action for that original's comments. The card's default copied link uses the
original on its primary platform with the largest supplied reply count; missing
counts fall back to the earliest original. All combines obtained replies, while
each platform tab combines its retained original threads. Reply links continue to
point to the actual comment, and writing a shared comment selects an exact room.
Shared text appears once, and additional source context stays attributed. When a
short quotation cannot be aligned into a complete fusion, the card uses the most
complete caption for each platform and keeps every original available in the source chooser.

Saving preserves every original edition, its available captured reply streams,
selected local media and paired archive snapshots. The local reader selects source
editions by original post identity, including several from the same platform.
Native reader exports retain those editions and validate their source evidence
again before import; ordinary cache cleanup does not remove saved material.

## Move your reader and share sources (0.24.0)

Settings → **Import & export** saves a documented native reader file with
subscriptions, folders, creator bundles, channel rules, network filter choices,
collections and saved text/available threads. Reading progress is off by default;
reader preferences and progress are separate opt-in choices when importing.
Connection policy and anonymous commenter identity stay on the receiving device.
Saved image/video files are not included; imported attachment manifests identify
missing files without starting downloads. The existing encrypted backup remains
separate from these readable interchange files.

**Source packs** contain only chosen original sources and public labels. Use
Subscriptions → **Packs & files**, or Share beside a personal channel. Review the
individual accounts before export. Recipients preview the file and choose what to
subscribe to; a shared pack never imports filters, personal rules or reading history.
Existing matching subscriptions keep their local choices. Native reader imports
merge saved material and collections, validate the complete file before changes,
and preserve missing source rules explicitly rather than widening them to All.

Add an ordinary public HTTPS **RSS / Atom** feed from Subscriptions. Its blog
entries appear beside social posts in the chronological feed, personal channels,
local saved library and reading-progress system. Adding/importing a source makes
no request until refresh. Opening an obtained entry reads its exact local feed
text; the original link opens the article separately. Feed text can be a summary,
publication dates can be missing, and an unavailable or empty feed stays pending.
Media still uses NetworkGateway and the selected Tor/filter policy.

OPML imports/exports ordinary RSS/Atom subscriptions and folder labels, including
standard FreshRSS feed lists. Social routing and personal reader state use native
JSON. FreshRSS scraping/credential/cURL extensions and NewPipe video service
exports are not executed or treated as social subscriptions. Native files and
OPML are UTF-8 and bounded to 16 MiB. Schemas, limits, merge behavior, examples and
the NewPipe/FreshRSS research are in [PORTABILITY.md](../docs/PORTABILITY.md).

## Personal channels (0.23.0)

Create channels from Following with **Create channel** or the channel controls icon, then switch
between them using the feed chips. Choose creator subscriptions, literal keywords
or phrases, exact hashtags, content types and exclusions. A linked creator can be
selected in several channels; the existing subscription grouping stays independent.
An empty subscription selection uses every enabled subscription. Platform tabs can
narrow an active channel without reopening an old page.

Alternatives within each rule are OR; filled rules are AND. A source edition must
meet all positive rules itself. Keywords and hashtags are separate rules. An
excluded keyword, hashtag, content type or subscription in any known cross-post edition keeps
the entire merged card out, preventing extra platform context from bypassing an
exclusion. **Why** on each admitted card explains the actual source and matched
rules in readable text. Rules use the authentic text/links shown by the reader;
matching neither infers interests nor guesses a creator's identity or location.

Posts remain chronological. Matching arrivals are collected and automatically
inserted above the reading position at idle. Channels retain the existing read/unread, continue-reading
and caught-up behavior. Source checks
remain pending until successful. Each channel, meaningful rule set and platform
scope has its own bounded reading context. Refreshing the open channel fetches only
eligible subscriptions; ordinary subscription scheduling remains shared. Source selection happens before the local 1,000-post window, so
unrelated busy subscriptions do not crowd out a selected creator.

Text only means nonempty text without attachments or external links. Images,
video, audio and links can overlap. A minimum video length uses a supplied numeric
duration; unknown lengths do not match. Selected Reddit/X public records,
Mastodon-compatible attachments, Instagram/Threads records and Media RSS can supply
it. Routes that omit duration, including current Nitter HTML and Bluesky readers,
remain unknown. No media is fetched or played to discover its length.

Channels filter the fetched local feed window, not an exhaustive platform history.
Expired ordinary cache can remove older candidates. Saved records and explicit
downloads keep their existing protection. Rules and the active selection stay in
private local settings and are included in settings backups. Invalid imported rules
are rejected before changes; an unreadable active channel asks for attention instead
of silently showing an unrestricted feed. No new account, server or network request
is needed to evaluate a channel.

## Available discussions first (0.22.4)

**All** remains the leftmost/default comment tab. Loaded platform and shared
replies appear as each discussion arrives. A compact count and discreet source
indicator leave the replies in view; **Source details** reveals each source's
loading state, error, notice and retry control. One failed shared service no longer fills the initial reading
area. An unavailable discussion is distinct from a source supplying no public
replies, and neither establishes that the post has no comments.

All keeps a small participation control that opens the shared-comment editor on
request. Opening a discussion or returning from a platform tab keeps an existing
draft collapsed behind **Continue your shared comment**. Minimize the editor to
return to reading without losing its draft; an explicit reply opens it directly.
The icon-only community tab keeps
its full editor. Original-platform tabs remain read-only, and publishing always
belongs to the selected original page's shared discussion.

Original platforms use two read permits while shared discussions use a separate
permit, so an unavailable or slow shared service cannot occupy the platform
readers. Leaving All cancels queued shared rooms; closing discussions or changing
sort cancels pending work. Late results must still belong to the current original
edition, order and conversation. Previously loaded replies remain available when
a refresh of that same edition and order fails.

These changes improve reading and failure handling. The latest read-only check
of CommentAnywhere's public endpoints returned HTTP 500/503 outside anti social too;
its published extension's read path also failed on a server-error preflight.
Permanent shutdown is unconfirmed. This release does not establish working live
participation. Instagram comments are still unsupported by the current readers.
Other supported platform threads can be read independently when their sources
supply public replies.

## AI inspection status (0.22.2)

anti social reads supplied AI declarations and credentials; it does not classify image
appearance. A small gray information icon now explains when an available media
copy was inspected but supplied no readable AI declaration. Its status remains
unknown. Incomplete inspection, including format and size limits, has its own
explanation. An uninspected file receives no inspection status. Missing metadata
does not establish AI use, human authorship or deliberate removal.

Saved copies retain these inspection reports even when attachments were not
selected, identifying them as saved observations when the bytes are unavailable.
Available local originals are inspected again. Reusing a decoded bitmap also
restores an expired or cleared report from existing local bytes. A loaded video
poster's declaration is scoped to its preview image, never to the video. These
checks add no media fetches, uploads or remote classifier requests.

Structured pages also recognize the three exact Schema.org source types for
generated media, generative edits and synthetic composites. Instagram's original
post response can supply the exact labels **AI info**, **Made with AI** or
**Imagined with AI**; they are accepted only from the selected post's validated
media record. These remain unsigned platform claims, with the first two leaving
the type of AI use unspecified. Current live samples supplied a null label;
non-null parsing is covered by fixtures and live positive behavior remains unverified.

## Authentic reader links (0.22.1)

Reader hyperlinks, captions, profiles, comment text and local saved copies now
present original site addresses. Copying a post or saved source edition returns
its authentic link. Tap an attached link to open it through the normal selected
platform policy; long-press it to copy the original address. Duplicate frontend
and original forms collapse to one attached link.

Darwin still chooses the private retrieval route internally. This change does
not fetch an original site simply to translate a link, change media transport,
rewrite raw source records or alter encrypted backups. Known frontend aliases,
including older cached Nitter links, reverse locally with their identity queries,
escaped paths and meaningful comment anchors retained; tracking parameters are
cleaned. Technical route diagnostics retain the actual source information.
Unknown hosts and opaque frontend utilities without a proven original mapping
are not relabeled as another site's content.
A final reader check also fixes a generated “Post by…” heading whose complete
caption was repeated with an added ellipsis; the original source text stays intact.

## Collected cross-posts (0.22.0)

anti social now aligns every shared sentence and quoted clause in source order instead
of selecting one longest passage. A shared opening, different middle additions
and shared conclusion become one readable card: common words appear once and
extra context stays attributed at its original position. Context shared by only
some platforms also appears once. No model rewrites or invents a combined claim.
Each original record, source URL, attribution and comment root is retained.

Generated repost headlines no longer repeat their entire caption. A small
“Reposted by @…” label preserves the original repost attribution. Exact Nitter
video-link labels and proven play-glyph heading echoes are treated as presentation
artifacts. Cached and saved records use the same formatter without rewriting
source records.

Matching remains local and bounded. The linked-account partial-fusion path needs an explicitly linked
source bundle, known timestamps within 48 hours, a matching image or specific
shared destination, and at least 20 shared prose words / 120 characters covering
60% of the shorter caption. URLs do not contribute to that overlap threshold.
A cross-link alone cannot hide a different caption. Competing numbers, negations,
operators and case-sensitive URL destinations stay separate, as do ambiguous or
reordered passages. Independent extra facts remain attributed context. Full URL destinations supplied by Bluesky facets and embeds are kept;
a shortened display label is equivalent only when a unique supplied href proves it.

Already-linked accounts can collect complete, identical captions with attached
media within ten minutes (at least three distinct words / twelve characters),
or thirty minutes for five distinct words / twenty-four characters. This collects
concurrent editions, including different video opening frames; it does not claim
their pictures are identical. Every distinct attachment and original discussion
is retained. Matching pictures support short captions over a longer six-hour
window. Unlinked publishers still require the stronger copied-content proof.
When a source omits media, a caption of at least 48 characters
and seven distinct words can match within ten minutes. Unlinked generic slogans,
ellipsized prefixes and ambiguous same-platform repeats do not get this lower
threshold. Source repeats instead require independently verifiable original-account
or explicit curator evidence and known dates; they are not inferred from a display name. Matching pictures never establish account ownership, and only images
already decoded by the reader provide appearance evidence. No new network probe,
classifier or background image request is added.

Following and combined feeds preserve the visible body and exact reading position
while the full newer prefix is automatically collected at idle. Offscreen cards
receive their merged context; the visible card keeps its wording until it leaves
the viewport or the reader explicitly jumps to Newest. Adoption includes updated known editions
outside the current retrieval window. Saving retains the validated source editions
and loaded replies together, including an exact counterpart that arrived after
the visible body was frozen; extra attachments remain an explicit selection.

Collected cards show their unique media together. A complete gallery can collect
matching separately posted pictures, including a captionless same-account reply
whose actual parent is another proven gallery member. Fetching that reply's
conversation no longer splits it out again. Ordinary replies remain separate.
The local comparison bank restores up to 2,048 already-cached image identities,
including older entries and detailed grayscale photographs; it never downloads
extra media for matching. Cropped, watermarked or substantially edited pictures
can remain unmatched. Bare and expanded HTTPS destination labels share identity;
different hosts and case-sensitive paths remain distinct. Known Instagram reader
excerpts do not repeat a short opening above the full caption. Text alignment is
capped at 16 KiB, 1,024 words and 256 passages per edition, with at most 64 original
editions per collection. Uncertain matches remain separate.

A proven repost curator and its own API-supplied original can also collect the
same clip. When a short literal quotation accompanies the full caption, the full
source text is shown once and the video is retained. Its exact RSS poster is not
rendered again as a separate image; both original captions remain inspectable.
An image-only reshare, including repeated identical flag emojis, can join the
single matching caption within six hours. It needs the same complete picture,
compatible symbols and an unambiguous destination card. Added words, different
symbols, links, replies and competing captions stay separate. The resharing
publisher and its discussion remain part of the collection.

## Native shared discussions (0.21.1)

All is the leftmost/default discussion tab, including single-platform posts. It
combines original-platform comments with shared CommentAnywhere discussions. An
icon-only community tab sits beside All, followed by the original platform icons.
Participation appears only in All or the community tab; All opens its editor on
request, while platform tabs are read-only.
Replies are explicitly published to the shared discussion, never to a social
platform. A merged cross-post has an original-edition picker: each edition joins
its own original page's CommentAnywhere conversation. Frontend hosts, archive replay URLs, media URLs and
provider fetch URLs never become the discussion address. Known Darwin frontend
aliases are reversed locally; opening comments does not fetch the original site.

The Kotlin client independently implements the public CommentAnywhere.app
extension 0.14.0 protocol. Compose renders comments and nested replies directly;
no extension, WebView or Firebase SDK runs in anti social. The optional YouTube original
extractor may evaluate signature-decoding JavaScript within its local decoder. Newest
requests the service's `New` order; Slop requests `Top`. Reply expansion and larger
root selections are explicit and bounded. There is no background comment polling,
notification polling, page-view registration or telemetry. Reads are cached for
five minutes with 12-entry/2 MiB budgets. All uses two original-platform read
permits and one independent community permit. Newest keeps reply trees together; Slop mixes
source ranks without inventing a universal score. Service failures trigger shared backoff
and honor Retry-After; they never masquerade as a successful empty discussion.

Posting, replying, voting, editing and deleting use the observed service protocol.
Only an explicit write creates its anonymous Firebase identity. Reading does not
register an identity, and there is no social-media sign-in. Identity refresh
preserves ownership; a failed refresh never silently substitutes another user.
Credentials are encrypted with Android Keystore in the no-backup directory and
excluded from settings/library exports. Reset is explicit, with an ownership-loss
confirmation. Existing CommentAnywhere username/OAuth login and notifications are
not exposed in this version.

All service and identity requests use NetworkGateway and the selected content Tor
policy. Tor fails closed. Credentials are permitted only on an explicitly scoped
service request to its exact endpoint, checked again after link cleaning and
filter rewriting. Public content requests still strip credentials. Service writes
are one-shot bodies: redirects and automatic transport replays are stopped. An
unconfirmed write keeps its draft and requires a fresh thread check before retry.
The app identifies itself as anti social; it does not impersonate the Chrome extension's
origin. Settings → Comments offers a local enable/disable switch, connection
status, an explicit public read-only connection check and a confirmed local
identity reset. No account, key or server configuration is required for reading.
The connection check uses the service's own homepage room and respects cooldowns.
The settings page explains the independent service and public posts.

Loaded CommentAnywhere discussions can accompany a saved local post, remain
available without their server, and participate in local full-text search. They
are labeled separately from the original platform's comments. New captures can
add replies without changing already-preserved comment text. Ordinary cache
clearing preserves saved captures and the local ownership credential.

The upstream extension strips queries from social URLs. anti social refuses addresses
whose query identifies a particular post or comment (for example Facebook
`story.php?story_fbid=...`) rather than mix different posts in one room. Platform
threads remain available for these entries. URL aliases, changed Reddit slugs,
renamed handles and different original host/path variants are not guaranteed to
share an upstream room; the exact original-page key is visible in the reader.

**External availability:** the latest read-only check on 2026-10-17 returned
HTTP 503 from the production list endpoint. Earlier checks also returned HTTP 500
from that endpoint and HTTP 503 from its public top-sites endpoint. The native
live check displays the failure honestly. No live comments or votes
were published, and live anonymous authentication/write acceptance is unverified.
The local protocol tests establish anti social's behavior, not the availability of
CommentAnywhere's server. Source/package hashes and protocol provenance are in
[the provenance record](../licenses/commentanywhere-provenance.json).

## Public source atlas and expanded routing (0.20.0)

The second cartography pass reviewed **268 pathways/projects individually across
all 21 platforms**, with **225 primary references**. The data-only
[source atlas](../app/src/main/assets/source-discovery.json) records request shapes,
capabilities, account/key/fee requirements, observed results and known limits.
Candidates, closed projects, login-dependent APIs and successful samples stay
explicitly distinct. This is a dated map of the reviewed web, not a claim that
all possible routes have been found or will remain available.

[public-pathways.json](../app/src/main/assets/public-pathways.json) adds **171 scoped
registrations** alongside Darwin's existing 69 sources. They include independent
providers, public protocol formats, feed renderers and selected-origin templates;
they are not 171 independent servers or 171 guaranteed working routes. Every new
candidate starts UNKNOWN. Only matching readable content establishes health.
Working samples get a modest initial priority so old unavailable hosts cannot
consume the cold-read budget before a useful new path gets a turn. Measured
performance, failure backoff and the existing bounded heartbeat then take over.
No runtime census, broad background sweep, new account or paid key is introduced.

| Platform | Additional practical paths and limits |
| --- | --- |
| X | FxEmbed typed JSON and feeds, Sotwe account JSON, Vx selected posts; existing Darwin Nitter routes remain. The official Nitter hostname currently serves a project notice, not posts. |
| Bluesky | FxEmbed typed JSON and feeds; public AppView/AT Protocol distinctions documented; public feed renderers. |
| Reddit | Public feed renderers and extra Redlib candidates; Arctic Shift post and separate comment recovery are historical only. |
| Tumblr | No-key legacy blog JSON plus public feed renderer paths; existing RSS and Darwin routes remain. |
| TikTok | One-document creator embeds, selected TikWM posts and public top-level comments; creator/bridge cards without source dates are on-demand only. No unverified nested replies. |
| Instagram | OGInstagram's advertised ActivityStreams object, public feed renderers and another Kittygram candidate. Tokenless Meta oEmbed shells remain inactive. |
| Pinterest | Public widgets for profiles, boards and exact pins, plus additional existing-reader candidates and feed paths. Observed widget records are undated and cannot refresh subscriptions. |
| iFunny | Public pages, existing Darwin reader, unofficial client contracts and abandoned project leads reviewed; no new working independent frontend verified. |
| Tieba | Credential-free public client forum/thread transport, plus RSSHub paths. No BDUSS, account session or persistent device identity. |
| Threads | Selected FxThreads JSON candidate and working public RSSHub feed sample; oEmbed shell excluded. |
| Facebook | Public RSS-Bridge candidates complement existing anonymous readers. Observed Meta oEmbed and bridge error shells are rejected; no new universal Facebook mirror is claimed. |
| Truth Social | Actor-scoped independent archive for realDonaldTrump. Direct API/RSS samples remain blocked or shells; the archive cannot establish live freshness. |
| Mastodon | Public feed renderers complement existing REST/RSS. Other instances are not interchangeable numeric-ID mirrors. |
| Lemmy | Selected-origin RSS and public feed renderer paths; API version, server allowlist and federation limits documented. |
| Pixelfed | Anonymous profile/status API, exact ActivityPub JSON object, and separate public comment transport on the selected server. |
| Misskey | Selected-origin RSS/Atom and direct public replies; feed renderer candidates. Server and visitor settings still apply. |
| GETTR | Public feed renderers complement the existing API. A successful older account feed does not establish recent account activity. |
| Gab | Public feed renderers complement existing public compact/API readers; original RSS sample was unavailable. |
| Telegram | Independent RSS relay and public renderer pools; private chats and authenticated Bot/MTProto interfaces stay separate. |
| LinkedIn | Public organization feed renderer candidates and embed/client leads; permissioned official APIs stay outside default accountless access. |
| Snapchat | Exact public Spotlight embed supplies native post/media; private snaps and allowlisted developer APIs are excluded. |

All traffic still goes through NetworkGateway, including new provider APIs, feed
renderers, redirects and media. Alternate live paths precede permitted originals;
independent archives are tried afterward. CACHE records cannot confirm a feed
refresh, become a heartbeat winner or pose as a live edition in archive-history
comparisons. A copy without a known capture date says **Archive copy**; verified
replay captures retain **Archived MM/YY**. Local saves preserve these copies.

Shared RSSHub/RSS-Bridge hosts are not social-link aliases. Feed identity and
entry identity must match the selected account/community, renderer errors and
empty 200 responses fail, and continuations stay bound to their issuing source
and target. Different hostnames that share an upstream do not create independent
failure domains. Operator cookies/configuration may be required upstream even
when the app-facing public endpoint needs no user account. TLS validation is
never relaxed. Tor-specific external availability remains source dependent.

Rebuild the two assets with `python3 scripts/build_public_pathways.py` and
`python3 scripts/build_pathway_inventory.py`. The latter reads the dated external
research evidence; neither script contacts or changes Darwin runtime/profile state.
[Source and license notes](../licenses/public-pathways-provenance.txt) and
[validation details](../VALIDATION.md) preserve the evidence boundaries.

## AI disclosures and metadata history (0.18.0)

An amber **AI DETECTED** disclosure opens a readable account of the AI use declared
with a post or attachment: creation, generative edits, composites, recorded tools
and earlier ingredients when available. It keeps each image and original platform
separate in a fused post; archived editions use their own declarations. Ordinary
software names, hashtags and computational camera processing never establish AI
use. The panel describes supplied claims rather than judging pixels or prose.

The same disclosure shows **METADATA REMOVED** for an explicit credential
redaction, or **METADATA GAP** when another observed copy with identical encoded
picture/color data contains metadata missing from this copy. A gap cannot identify
who changed the metadata, why, or whether information was removed from one copy
versus added to the other. Neither notice establishes AI use. Missing metadata by
itself produces no warning and never establishes human authorship. After an actual
inspection, the neutral gray information control can explain that AI use remains
unknown; it does not claim that metadata was scrubbed.

anti social reads namespace-qualified [IPTC/XMP declarations](https://www.iptc.org/std/photometadata/documentation/userguide/),
matching [structured page declarations](https://schema.org/digitalSourceType), and
explicit TC260 AIGC declarations with string Label `1`. Suspected/possible labels
and arbitrary platform field guesses are excluded. The three exact Schema.org
enumeration aliases and the scoped Instagram labels described above are retained
when the selected source supplies them. Embedded XMP extraction
covers ordinary JPEG APP1, PNG iTXt/tEXt packets, WebP and TIFF tag 700; extended JPEG
XMP packets are recognized as present but not reassembled. Content Credentials
use the official [C2PA Android SDK](https://github.com/contentauth/c2pa-android/releases/tag/0.0.14)
to check exact delivered bytes. File/signature integrity, signer trust and unsigned
claims are explained separately. A valid credential does not establish whether
the content is true. anti social does not configure or download a signer trust list.

Inspection uses already available files, on a bounded background queue. It adds
no image requests, uploads, credential-server requests, identity lookups, OCSP
requests or remote classifier. A remote-only credential stays explicitly
uninspected. Streams and files over 32 MiB are not downloaded for inspection.
The transient observation cache holds at most 256 entries for seven days, clears
with ordinary cache and cannot be revived by queued old work. A later image load
can inspect existing original bytes again, including a decoded bitmap cache hit.
Saved copies retain readable declarations and actual inspection observations;
signature status is checked again against available local bytes rather than
trusted from an imported backup. The optional native validator
requires Android 9; Android 8/8.1 retain unsigned declarations, comparison and UI.
The SDK archive is about 35 MiB; its native libraries add about 86 MiB to the
universal APK, which is about 206 MiB and includes all four Android architectures.
This is fixed program storage, independently of the bounded viewing cache.
[SDK source and licenses](../licenses/c2pa-provenance.md)
are preserved, with notices also bundled in the APK.

Regional transparency requirements, including the [EU transparency code](https://digital-strategy.ec.europa.eu/en/policies/code-practice-ai-generated-content)
and [China's identification measures](https://www.cac.gov.cn/2025-03/14/c_1743654684782215.htm),
do not guarantee that a social platform delivers a readable manifest. Re-encoding
often prevents an exact copy comparison. Unknown metadata and proprietary
watermarks stay unknown. No comprehensive coverage of every platform's AI labels
is claimed.

## Cross-post fusion (0.17.0)

Linked accounts can share one card even when their posts add different introductions
or closing notes around the same substantial caption and picture. The common text
appears once; additional wording stays above or below it with its source platform
shown. Original text, attribution, links and each platform's discussion root remain
intact. Platform comment tabs and **All** retain **Newest** and **Slop** ordering.
New context and regrouping are collected automatically at idle. The current
visible card keeps its wording and position; offscreen cards receive their full
merged context. Newest explicitly applies the latest collected presentation.

The legacy linked-account partial-caption path requires a linked source bundle, known dates
within 48 hours, matching image or specific destination evidence and substantial shared passages at
sentence or paragraph boundaries. Whitespace, prose case and quote typography can
differ. Competing claims, ambiguous reposts and weak matches stay separate. Image
evidence uses matching media URLs or bounded local fingerprints of pictures the
reader has already decoded; matching adds no image requests or remote classifier.
Fingerprints can support resized or re-encoded copies, but do not reliably identify
crops, watermarks or substantially edited images. Unseen images cannot supply
fingerprint evidence, and matching pictures never establish account ownership.

Collected galleries apply newly available local image observations even when the
reading card's captions and source editions are frozen. Reencoded photos and text
graphics can appear once across platform URLs. A fine area-averaged luminance grid
checks local differences so a changed chart region or crop can remain separate;
uncertain matches stay visible. Saved editions retain these versioned observations,
and an already-open image viewer keeps the chosen gallery until it is closed.
See [media collection matching](../docs/MEDIA_COLLECTIONS.md).

Saving a fused card preserves its source editions together. The local reader can
switch between **Combined** and each original platform's text and link, while
captured replies and selected offline attachments retain their existing controls.
RSS-generated attribution headings and explicit HTTPS versus identical visible
link labels no longer prevent the same caption from matching, including records
already in the local cache. Raw source fields remain unchanged.

## Image discovery (0.16.0)

Open a post image, swipe to the picture you want and tap **Search image**. anti social
submits a resized copy with file metadata removed to public image-search websites
through Tor. Search engines receive the visible image; no social account or paid
search key is required. This runs inside the standalone Android app.

Each engine discovers links. anti social selects supported public social destinations,
deduplicates canonical post/account links across engines and reads them through
its existing source pool, including permitted original and archive recovery paths.
Verified posts and account metadata appear progressively as native cards; search
snippets and login pages never become fabricated posts. Opening a match and going
back retains the current search. Scrolling requests another bounded block when a
provider exposes a valid continuation; Stop, Continue and explicit retries are
available. Failed engines do not hold back another engine's readable cards.

Different accounts using the same picture remain distinct. Existing conservative
cross-post matching can combine copies of the same message and retain separate
platform comment streams. Uploaded copies and search cursors stay in memory for
the session; ordinary fetched content still follows the normal cache policy.
Explicitly saved copies remain protected. Provider availability and public index
coverage vary, particularly on Tor; the source panel shows individual failures
and unreadable matched links remain separate from the native feed.

The initial pool is Yandex (two public origins), SauceNAO, Bing and Google Lens.
Yandex's public image cards and continuation were observed returning social links;
some Tor connections receive refusals instead. SauceNAO completed the full native
Tor → social-link → routed-post → card flow in a live check. Its
[published index status](https://saucenao.com/status.html) reports an old Twitter
index, so it is chiefly an additional route for older images and memes. Bing and
Lens currently accept uploads but often return classification/JavaScript pages
that anti social cannot extract; those remain explicitly unavailable. A successful
upload alone is never reported as a working image search. Public image search
honors the original-site access setting and has no direct fallback from Tor.

## Reader polish (0.15.0)

Post attribution shows the original social platform, with its canonical home
server for federated services. Retrieval routes remain available in source
diagnostics; archive badges still identify archived editions. All 21 platform
marks are bundled vectors with consistent optical sizes, restrained tiles and
light/dark theme tint. Artwork sources and licenses are in
[the icon notices](../licenses/platform-icons.md).

Videos play inside posts with compact native play/pause, seeking, sound and expand
controls. Expanding keeps the same player and position; leaving the foreground
pauses it. Playback supports exposed progressive MP4/WebM and HLS/DASH streams,
subject to the device's codecs and the selected public source. Source readers now
recognize lazy Nitter streams, nested video alternatives, adaptive Reddit media,
X extended attachments and declared stream types. Browser embed pages are not
treated as video files. Expired links, login-only media and DRM may remain
unavailable; Retry stays in the reader.

Remote manifests, segments, keys and redirects use NetworkGateway and the selected
privacy policy. Unknown extensionless streams use a bounded 4 KiB format probe;
known formats avoid it. Dynamic DASH uses the device clock rather than Media3's
independent network time synchronization, so live playback assumes an accurate
device clock. Remote playlists cannot read local file/content/asset URLs. Saved
playback uses only local files. Streaming support does not add offline HLS/DASH
playlist downloads; the separate video storage controls still apply.

## A local library (0.14.0)

**Save a copy** keeps a post's text, attribution, original URL and any verified
paired archive on this device. Choose individual gallery images and optional
videos; images start selected and videos require an explicit choice. The bookmark
button remains a quick text-only save. Existing bookmarks become searchable local
copies without another source request. A later save keeps the original captured
text and can add newly obtained replies instead of replacing earlier editions.

The discussion reader can include the public replies it has actually loaded,
including separate platform streams. It does not imply that an entire thread was
available. Saved copies open in a native **local reader**, with missing attachments
reported explicitly. Opening the original is a separate action. Local reading,
image viewing, video playback and search work with the network unavailable and
do not fetch missing material. Collections such as **Projects** and **Recipes**
keep related copies together; deleting a collection keeps its contents.

Library search uses SQLite full-text indexing of titles, bodies, attribution,
original links, paired editions and captured replies. It runs entirely on-device,
supports Unicode words and word prefixes, and removes a small set of English
conversational fillers when meaningful terms remain. It is literal keyword search,
not a semantic model. A large saved thread occupies one result rather than hiding
other matching copies. Results are capped at 200 copies per query.

Full-text reading history is **off by default**. Storage settings can retain text
from posts actually viewed, with independent age and text-size controls. The
defaults are 90 days, 50 MiB of retained payloads and at most 2,000 snapshots;
search-index overhead is additional. Turning retention off or clearing history
removes retained history text and its index, while saved copies remain protected.
Older link-only history has no recoverable body and is not presented as a full copy.
Retained history can be promoted to a saved copy without fetching it again.

Selected media has durable queued/completed/unavailable status and retry controls.
Already-viewed cached images become protected copies locally, even without a
connection or a ready Tor circuit. Ordinary cleanup later reclaims their former
cache files while respecting active image decoders.
Downloads use NetworkGateway and wait for required Tor; a removed or superseded
copy cannot be revived by a late transfer. Interrupted files retry from the
beginning, rather than claiming HTTP range resumption. Images must decode and
videos must have a recognized container before they are marked downloaded; codec
support still depends on Android. Login pages and unsupported playlists are not
certified as media. Source availability can prevent a selected attachment from
being saved even when its text copy succeeds.

Video storage is separate: temporary videos default to a 250 MiB cache and seven-day
retention; explicitly downloaded videos have a 2,048 MiB allowance and default to
unmetered downloads. A zero allowance means unlimited explicit video downloads;
zero temporary cache disables caching. Lowering the download allowance pauses new
files instead of deleting existing ones. **Remove downloaded videos** keeps saved
text, images, replies and collections. Individual progressive downloads are bounded
at 2 GiB per video and 16 MiB per image, with a free-space reserve. HLS/DASH offline
playlists remain unsupported. Ordinary cleanup never evicts saved copies.

Encrypted text backups include captured replies and collections, but do **not**
include media bytes. Restoring a backup marks absent selected attachments as
missing and requires an explicit retry; it does not silently contact their sources.
Existing version-one backups remain readable. The encrypted metadata import/export
limit remains 16 MiB.

## Dynamic cross-posts (0.13.0)

All platforms and person feeds combine confidently matching copies into one card,
with an icon for each platform's public comments and an **All** discussion view.
Platform-specific feeds retain their selected source membership while exposing
collected originals and their known alternate comment streams. Opening a live post retains those choices; local copies retain
the comment streams explicitly captured when saving. A direct
platform-comment button requests that platform first; All still uses bounded
parallel reads, with **Newest** and **Slop** applying to comments.

Matching compares content rather than RSS packaging: repeated caption headings,
escaped entities, Unicode spacing/punctuation and linked-account hashtag additions
can describe the same message. Substantial unlinked copies need stronger evidence;
clearly marked truncations need a substantial exact prefix and a corroborating
link or media URL. Local subscription membership helps match linked opaque-ID
feeds without treating a display name as proof of account identity. Changed
numbers, negations and conflicting destinations stay separate, as do ambiguous
unrelated reposts, short slogans and ambiguous same-platform copies. Specific
repeated originals can share a card under the source-repeat rules above while
keeping every post ID and comment root. The newer copied-content rules above
also admit substantial exact undated copies with actual shared media or an exact
original-post reference; a display name alone never proves ownership.

A late matched copy adds its platform's comment option to the existing card. It
keeps that card's text, position and read state, without another New item or a
feed jump. Different arrivals and changed matches are collected automatically
at idle while preserving the visible reading card.
A caption repeated as both RSS title and body renders once, including cached
records, while the original fields and each source's provenance remain intact.

Matching runs locally on fetched text, links and media URLs. The 0.17.0 extension
also compares bounded fingerprints of already-decoded images for linked partial
captions, as described above. Matching adds no network requests or remote
classifier; uncertain copies remain separate and missing sources cannot contribute
a comment stream.

## Reading memory and a stopping point (0.12.0)

Following, All platforms and each person's combined feed remember the post and
pixel offset where you stopped. The app also reopens your last reading view and
selected source group after a restart. Their positions stay separate; a post actually
read in one view remains read in the others. If ordinary cache has expired that
post, anti social resumes at a nearby surviving post and says so. Reading memory stores
identifiers, not another permanent copy of the content.

Opening a feed includes its available fetched posts above the restored anchor,
so the route toward the newest local content is already scrollable. Later arrivals
are collected into one coherent update and inserted above the anchor at idle,
without moving or rewriting the visible card. **Newest** jumps to the head and
keeps a **Continue** point so you can return. Jumping or scrolling past a post never
marks it read. The native viewport marks a post read
after it rests substantially in view; backgrounded, covered and prefetched cards
do not count. **New** identifies unread additions to the accepted batch; older
unread posts retain their **Unread** label.

A stable boundary separates the reading batch from previously read posts, with
**Earlier posts** available explicitly. **Caught up** requires the batch to be
read, no waiting arrivals, and a successful latest check for every enabled
subscription in that view. It shows when those checks succeeded and covers only
the public posts the sources supplied. Failed, interrupted, skipped and empty
checks remain **pending** even after an earlier success; their saved content stays
readable. Tap the pending summary or **Source status** for each account's result.

Reading metadata stays local, with up to 32 reading contexts, 4,000 shared read
identities, 2,000 source check records and a 4 MiB total limit. Inactive reading
contexts and check records expire after 90 days; the shared read ledger expires
after 90 days without reading activity.
Older histories compact to their reading/Continue anchors before contexts are
evicted. Existing ordinary cache expiry still applies; saved posts and explicit
offline downloads remain protected.

## Shared links and archive recovery (0.11.0)

**Settings → Link opening → Choose supported links** opens anti social's own Android
defaults page. On Android 12 and later, enable Open supported links and approve the
domains you want. Android requires this one-time choice because anti social does not
own the social sites. Enabling a platform tab activates its link handler; hiding
it deactivates that handler. Android may still list inactive domains in its setup
page. Sharing works for recognized federated servers. For an unfamiliar server,
choose its platform in anti social's link dialog and paste the URL. anti social no longer asks
to replace your browser for every website.

Tapped supported links, Share → anti social and pasted social URLs enter the same Darwin
routing path. Mobile/legacy hosts are normalized, and common shortened/share URLs
are resolved through NetworkGateway when original-site fallback permits it. Normal
links for unselected sites stay with your browser; explicit sharing can open an
unselected platform. A sign-in wall or unresolved share token is never treated as
a usable post. Some share tokens require an authenticated browser to expose the
underlying post address and cannot be recovered without that identity.

If alternate and permitted public original routes cannot supply an exact post,
anti social checks a saved copy, then Internet Archive and Arquivo.pt. A public preview
can stay visible while recovery runs. Readable recovered content uses the native
post card, labeled **Archived MM/YY** (for example, **Archived 11/08**). Its provider
appears in the usual source footer. It can be saved and downloaded offline.
It has no current/older clock toggle because the
live edition is unavailable; its text and media may differ from the current post.

Recovery tries recent captures, then older captures when recent ones are unreadable,
with at most four small indexes and four replay bodies, a 24-second direct/
40-second Tor lookup deadline, coalesced requests and a separate 30-lookups/hour
courtesy limit. Positive results expire after a day; confirmed missing captures
after six hours; failures remain typed and briefly cached. Recovery records use
bounded ordinary cache; saved posts and explicit offline media stay protected.
Replay redirects and media stay on the selected archive, using the content
connection and fail-closed Tor. No scripts run and no archive submissions are made.

**Settings → Privacy & connection → Recover unavailable posts** controls automatic
recovery independently of the existing older-version checks. Public archive
coverage varies. Private posts, login-only shells and binary-only media links do
not become readable merely because a URL exists in an archive.

## Connection controls and platform order (0.10.1)

Tap the Direct / Search Tor / Tor badge for a small connection sheet over the
reader. It shows the content and search routes separately, live Tor status, and
a **Tor for all traffic** switch. Changes take effect immediately and persist;
turning content Tor off keeps Bing and DuckDuckGo search on Tor. Tor requests
remain paused until connected. The Settings icon keeps its normal role.

Default tabs and platform pickers now use one approximate mainstream-first order,
starting with Facebook, Instagram, TikTok and Telegram. It is a navigation choice,
not a precise active-user ranking across incompatible public statistics. New
installs retain the existing four selected platforms, ordered Instagram, TikTok,
X, Reddit. **Settings → Platform tabs → Sort by popularity** applies this order to
your current choices; manual ordering remains available.

## Search scopes (0.10.0)

One **Everywhere** pill narrows the search without adding a permanent filter bar.
Tap it to choose a subreddit, account, hashtag, board, forum or channel, using the
options available for that platform. The chosen scope stays visible beside the
search heading; tap it again to switch scope or return to Everywhere. Searching
from a profile, community or board selects its scope explicitly. Switching
platforms resets the scope. The platform chooser is now a compact logo/name menu.

Every platform has a public container option. Reddit offers subreddit and author;
Lemmy offers community and account; Pinterest offers board and account; Tieba
offers forum; Telegram offers channel. Other platforms offer their public account
or organization feed. Facebook's option is a public Page, and LinkedIn's is a
company/school. Hashtag scopes are offered on X, Bluesky, Tumblr, TikTok and
Instagram. Unsupported Facebook groups, Misskey channels and unauthenticated
Mastodon full-text search are not advertised as working scopes.

Reddit subreddit searches use restricted search URLs, X account/hashtag searches
use native search syntax, and Bluesky uses public author/tag API filters. Public
index fallbacks carry the restriction; URLs and hydrated posts are checked before
entering the scoped feed. Search snippets and matching display names cannot prove
that a post belongs to the selected scope.

If an account or community search endpoint fails, its selected public feed can
supply a bounded fallback with a coverage notice. Successful native searches stay
on their existing path; cancellation and rate limits do not trigger extra scans.

Other scopes search the public posts supplied by the selected account, board,
forum or channel, with a visible coverage notice. This is a bounded search of
available feed history, not a promise of platform-wide historical full-text search.
It reads up to three source pages per block, stopping at 12 pages, 400 inspected
posts or 1 MiB of inspected text. Quoted phrases and words are matched literally;
source-specific advanced operators apply only to native searches. Snapshot-only
and unavailable sources retain those limits. Matching posts populate the normal
chronological native feed, with the same continuation and cancellation controls.

Scope is retained locally with the search identity and isolated across cache,
pagination and routing cooldowns. The internal scope descriptor never enters a
network request. Federated addresses retain their selected server; ambiguous
remote-community proxy links do not silently select a different server.

## Older post versions (0.9.0)

After a readable post rests on screen for eight seconds, anti social can quietly check
Wayback and Arquivo.pt for a usable older snapshot. Scrolling, leaving the app or
opening a dialog cancels the check. A small clock appears only when extracted
text or identifiable media differs; tapping it shows the archived version and
replaces the clock with **Undo** to return to the current post. The date is the
oldest available readable snapshot found within the bounded check, not a claim
that the archive captured the first edition. Matching text does not prove there
were no intervening edits; missing fields and ambiguous media are inconclusive.

Bookmarking keeps the current and discovered archived text together, whichever
version is displayed. Offline download attempts both versions' media; missing
historic files are reported. A version discovered after saving attaches to the
saved post, and an existing offline download also attempts its archived media.
Removing the bookmark prevents pending transfers from recreating saved files.
Ordinary history lookups expire with the bounded cache; saved pairs survive
ordinary eviction and are included in encrypted backups.

**Settings → Privacy & connection → Check older post versions** controls these
checks. The clean post URL is sent to public archives through NetworkGateway,
using the content connection and its optional fail-closed Tor mode. Replay bodies,
redirects and archived artwork are pinned to the selected archive so a replay
cannot silently load the original social site. No JavaScript or archive submission
is used. Coverage depends on readable public captures and the post's markup;
modern login walls and JavaScript-only snapshots often cannot supply an old post.

Checks allow one lookup at a time, at least 30 seconds between starts and six per
hour, with that courtesy budget retained across app restarts. A post is checked
at most daily; confirmed empty archive indexes are cached for three days.
Each lookup reads at most two small indexes and two replay bodies. Rate limits,
errors and cancellations do not establish that a post has no archived history.

## Adaptive data routes and quiet heartbeat (0.19.0)

anti social learns from actual profile, post, feed and comment reads. Success means usable
content for that operation; a reachable shell, wrong account, login wall or preview
cannot establish a working full-content route. Darwin's imported alternatives stay
ahead of permitted accountless original fallback. Within each tier, the router
uses successful delivery time, variability, recent failures and observation age.
It starts one route, hedges a stalled read with up to two delayed backups, and
cancels losing requests. A quick failure releases the backups immediately.

Local routing observations are isolated by source, operation and direct/Tor mode.
Rate limits and connection failures produce bounded cooldowns. HTTP 429/503
`Retry-After` delays are honored, up to a day, across restarts; format fallback
does not immediately retry a rate-limited endpoint. An empty or
restricted account briefly suppresses that target without disabling other users
on the source. At most 512 observations are retained for seven days in ordinary
local cache. They contain route metadata, not search terms or post bodies. Failed
target identities are hashed and retained only in bounded session memory.

Ordinary reads replace maintenance probes. Each recently used site learns its
own quiet period, separately for feeds, timelines, profiles and direct/Tor mode;
federated servers also learn separately. Repeated independent observations of the
same usable route plan progressively extend an initial roughly 30-minute interval
toward 24 hours. Dense repeated reads postpone checks without pretending to be
independent evidence. Meaningful winner/backup changes shorten the interval;
repeatedly failed paths back off independently instead of being hammered. A missing
or private account never establishes a whole-site outage. A probe proves usable
data, while only real content reads train delivery-speed estimates.

One coalesced Android job waits for the earliest eligible plan, with at least
15 minutes between scheduled opportunities, unmetered connectivity, device idle
and a healthy battery. It covers only activated scopes from enabled subscriptions
on selected sites, using one representative per scope and a shortlist of three
promising paths. Selecting a tab alone does not start probing. No background
heartbeat runs when background refresh is off. A quiet foreground opportunity
can use the same budget; active reads cancel the probe child and keep priority.
The heartbeat never starts Tor or waits for it; unavailable selected Tor defers
without direct fallback. A heartbeat-only process initializes local privacy policy
without launching Tor or search preconnections.

Automatic soundings share persisted limits: two per five minutes, twelve per hour,
one per host per fifteen minutes, and one in flight. The budget is written before
opening the probe connection. Each sounding allows at most four HTTP exchanges
(including redirects/transport retries), 512 KiB of decoded text, and an 8-second
direct/16-second Tor read limit. These are HTTP/body limits, not a cap on TLS or
DNS bytes. Exceeding a budget is inconclusive and preserves prior route health.
The local heartbeat cache retains at most 512 scopes and 512 path records for
30 days; demand expires after seven days. It stores routing hashes, hosts and
server origins, never account URLs, entered search terms or content. It is ordinary
cache, excluded from saved material and backups.

Search, posts, custom RSS, media and comments do not trigger automatic soundings.
**Test sources** remains an explicit fuller diagnostic. A redirect to recognized
original data is not credited as an independent working alternate; that content
waits behind usable alternate paths. Source details show operation, mode and age.
Android timing is [best effort under its work constraints](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work),
not an exact alarm. Server pause handling follows [HTTP Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after).
Deterministic local tests establish the policy and request behavior; they do not
claim a measured battery saving or a live speed improvement for every frontend.

A federated server is a distinct origin, not a complete mirror of another server.
Enter `@name@server.example` or a full link with the selected platform. Restored
subscriptions preserve that server identity. Source inspection uses the selected
target when opened from its reader. Mastodon also has a separate same-server RSS
backup when its public RSS feed is available; RSS does not supply comments or
continuation. No new third-party frontend is claimed healthy merely because it
was found in a project list. Existing alternate RSS subscription URLs remain useful.

| Added platform | Native data path and current limits |
| --- | --- |
| Facebook | Public Page/profile owner resolution, dated timelines, selected posts, public word search and source-bound next pages, Newest/ranked replies and comment continuation. Anonymous access is source dependent; private groups and all-account coverage are not supported. |
| Truth Social | Mastodon-shaped account/post reader is implemented; current live requests return HTTP 403. No working pool or RSS feed is claimed. |
| Mastodon | Selected server's public account/status/context APIs; older pages and comments. Separate validated RSS backup. Some servers restrict anonymous access. |
| Lemmy | Selected server's public v3 community/user/post/comment APIs; older pages and New/Top replies. v4-only deployments and direct comment resolution are unverified. |
| Pixelfed | Public, opted-in profile Atom feeds with attached media. API access can require authentication; standalone posts/comments and continuation are unavailable. |
| Misskey | Public user/notes APIs with older pages and selected notes. Empty/private notes do not count; comments are unavailable. |
| GETTR | Public profile/post API, reference expansion and older pages. Comments are unavailable. |
| Gab | Public profile/timeline and individual-status APIs, including compact media records and older pages. Comments are unavailable. |
| Telegram | Public channel previews, older messages and exact public messages. Private chats and discussion comments are unavailable. |
| LinkedIn | Public company/school JSON-LD posts and matching selected public posts. Current snapshot only; personal profiles, comments and continuation are unverified. |
| Snapchat | Public profile metadata and dated public Spotlight highlights. Current snapshot only; comment decoding, continuation and media playback are unverified. |

These paths extract data into Compose cards; they do not execute website scripts.
All requests, including API forms, JSON, RSS, media, probes and redirects, pass
through NetworkGateway and the existing native filters. Tor remains optional and
fails closed when selected. Original fallback controls selected federated servers
and learned original media hosts as well as the centralized social domains.

## Reading

Open or share a link with anti social, paste a link using the search button, or subscribe
to a public username, community, #tag or thread. Subscriptions and optional groups
stay on the device. Alternate feed URLs can discover posts independently of their
content source. Reorder/hide platform tabs in Settings. The library holds bookmarks,
offline copies and reading history. Source inspections validate profiles, timelines,
posts, search, feeds, comments and media independently. Instagram profiles use a native photo grid; Pinterest uses
native boards and a staggered pin grid. Images open in a swipeable, zoomable viewer.
Failed images expose a retry button without needing to reopen the post.
Recent pages and their scroll positions are retained across tabs and reader
navigation. Cached content remains usable during refreshes and source failures.
Following shows subscribed sources; shared-link reading stays in your history.

Platform tabs use larger bundled marks with accessible names. The stacked-layers
tab opens **All platforms**, a chronological feed of all local subscriptions.
Subscriptions can link a person's or organization's public accounts. **Find linked
accounts** reads that profile's explicit links and, at most, one linked website;
it suggests public profiles for you to select. It does not guess identity from a
shared username. Legacy HTTP profile links are tried over HTTPS only. Accounts can also be linked manually. Existing RSS settings and
groups survive linking; everything remains in the local subscription backup.

Open a subscription to see its combined feed. Matching posts share a card with
platform buttons for their separate public discussions. Exact substantial text
and corroborated partial captions follow the bounded rules above. Linked-source
evidence can lower a threshold; names or common URLs alone cannot. A specific
original-post reference may corroborate substantial identical text when dates are
unavailable, but never hides a different caption. Undated posts remain visible.
Uncertain wording and media-only cross-posts can remain separate; no global
identity directory or matching server is used.

**Comments / Discussions** opens a native quick reader. Choose a platform or **All**
to mix its available threads. **Newest** orders supplied replies by date;
**Slop** requests Reddit/Lemmy top order, Facebook ranked replies or Nitter's X relevance order. Other
readers retain the source's supplied order and explicitly disclose that popularity
ranking is unavailable. All interleaves those platform orders rather than inventing
a shared popularity score. These choices affect comments only; feeds stay chronological.
The current comment readers support X through Nitter, Reddit, Bluesky, Tieba,
Facebook public posts, Mastodon public contexts and Lemmy,
subject to public source availability. Instagram, TikTok, Tumblr, Pinterest, iFunny
and Threads currently show an unavailable explanation. Recommended posts and
ancestors are excluded from discussions. No supplied replies is explicitly distinct
from proof of zero comments. Nitter's known conversation container supplies thread
membership, but does not expose every immediate reply-parent ID.

Platform threads load on demand with at most two simultaneous platform reads;
shared discussions have a separate single read permit.
Closing or changing order cancels pending work. Separate bounded five-minute memory
caches retain each order; persistent thread data uses ordinary expiring storage.
Each quick thread is capped at 200 entries or approximately 200 KB of text/metadata;
source-bound next-page buttons stop on duplicate or exhausted pages. Saved posts
and explicit offline downloads keep their existing protection.

The magnifying glass searches words by default. Enter an `@username`, `#tag` or
full link to open that exact destination; **Profile or link** mode also accepts
bare usernames. The keyboard search action submits the same query as the button.
Word-search coverage depends on the selected platform's public sources. Facebook
also has a native anonymous post-search operation; other expanded platforms can
use the selected public index when original fallback is enabled. Public
profile/post readers do not by themselves establish native word-search support.
Discovery tries social frontends before the selected index. **Settings → Search**
offers Bing RSS, DuckDuckGo's HTML search, or Darwin's reviewed LibreY routes.
Bing and DuckDuckGo require **Use original sites as fallback** and always use Tor;
choosing one does not silently send the query to another index. The additional
providers are identified separately from the Darwin donor in source provenance.
anti social prepares a reusable connection to the selected commercial index through Tor
while you browse or type. Preparation sends a query-free HEAD request and reads
no response body; submitting a search cancels unfinished preparation rather than
waiting for it. Recent explicit search endpoint refusals and DNS failures cool
down for ten minutes, so repeated searches avoid immediately retrying known
failures. Explicit source testing can retry sooner. These are connection and
retry optimizations; cold Tor startup and upstream availability still affect speed.
Search opens a native feed that slides up from the bottom. Discovery runs behind
the scenes: post destinations are resolved into the same full post cards used by
Following, and public profiles become account cards in a People row. Each readable
result fades in as it arrives. Indexed titles and snippets are never presented
as social posts. Near the bottom, the feed automatically resolves the next small
batch or requests the provider's next page. Continuations stay with the source
that issued them; DuckDuckGo's next-page forms use the guarded gateway and Tor.
Native social search cursors and same-source Darwin search links also continue
when supplied. Bing RSS supplies no verified next-page cursor; Facebook's bounded
pair of queries can still discover different destinations through that provider.
The app does not invent endless pages when a source supplies no continuation.
Bluesky retains native account discovery when its post-search endpoint is denied,
then uses the selected index to discover posts. Instagram combines account results
with public topic posts for a single-word search when the source supplies them.
Navigation links and branding do not count as working timelines. Repeated cursors,
source-specific end conditions and the session's limits stop fetching. A validated
public-index next page can continue past duplicate, filtered-empty or unreadable
blocks; there is no fixed two-unreadable-block cutoff.
Feeds deduplicate posts and sort supplied dates newest first, retaining undated
posts with their date marked unavailable. Search sessions are bounded to three
recent sessions, 240 destination candidates, 200 posts / 40 accounts, 20 discovery
pages and an approximately 1 MiB text/metadata budget per session. The account-card
quota does not consume the post quota. Recent feed data also uses the ordinary expiring cache;
saved records and explicit offline downloads keep their existing protection.
Index coverage may be incomplete, older or temporarily unavailable. Resolving
real posts adds source requests, so index-response timings do not represent the
time to fill this feed. Saved results are labeled and remain usable on failures.
Source inspection selects the failed capability and reports original fallbacks
as well as alternate sources.

Alternate instances precede accountless original fallback. Public availability is
source dependent: an app cannot make a private, deleted or login-only post public.
Challenges, empty frontends and missing dates are reported rather than treated as
working feeds. Individual-post previews remain available if fuller sources fail;
a fast preview no longer suppresses fuller alternate/original reads. The bundled catalog preserves Darwin's reviewed routing policy and expanded
instance pools. Instance health is separate for Tor and direct connections. Later working instances
remain reachable when the initial choices fail; small concurrent batches keep
failover bounded. TikTok short links are resolved through the same guarded gateway.

All requests, including media, redirects, probes and filter updates, use one gateway.
It strips authorization and discards ordinary cookies, cleans links, applies a Rust/adblock filter
engine, rejects private destinations and limits response sizes. Darwin's 25 custom
filter sources and seven standard lists seed the app. Cosmetic/scriptlet filtering
is unnecessary in the native renderer; this is not a full uBlock Origin port.
The complete seed is compiled during the build and bundled for first launch;
the app restores that snapshot before making requests. Raw lists remain available
for recovery. Compiled URL-cleaning and routing expressions are reused. Native
filter restoration avoids copying the default snapshot into the app's private
storage; updated/customized protection is retained. Settings show actual request
checks and blocks for the current session. Routine maintenance runs after the
first day, while idle, charging and on an unmetered connection.
Pinless's anonymous pagination bookmark is the sole cookie exception: a bounded
cursor is captured explicitly and replayed only at its source endpoint for the
same search/profile. No general cookie jar, account session or tracking cookies
are retained. Reddit, Tumblr and Tieba use their own returned next-page links;
all continuations stay bound to the source that issued them.
Unproven post/media alternates receive a four-second direct / eight-second Tor
pool budget before permitted original fallback; recently working alternates keep
their normal budget. With original fallback disabled, the full alternate budget
remains available.
Tor is bundled. **Tor for all traffic** is off initially; with the default DuckDuckGo
index selected, Tor runs separately for engine requests and stays ready for later
searches. Social content, feeds and media retain the ordinary connection unless
all-traffic Tor is selected. Turning off public original fallback or selecting
Darwin's search routes stops search-only Tor. All required Tor requests fail closed,
including redirects; failed engine searches never fall back to direct connections.
The engine still receives the query, which can itself identify a person. Opening
a result follows the content connection setting. DuckDuckGo challenges are shown
as failures, not empty successful results. A DuckDuckGo HTTP-200 server-error page
gets one ordinary form retry with the same query/provider and required Tor route;
challenges and refusals are not retried automatically. Each Tor run lives
in a fresh private app process; disabling it releases that process. The published
tor-android Java wrapper retries listener readiness and exposes its control connection
only after authentication; native binaries are
unchanged. Its source, license and artifact hash are retained in the repository. External-browser
actions leave anti social; the selected browser manages its own connection and settings.

TikTok and Threads profiles that withhold a public timeline can discover a limited
selection of that author's public posts through the selected search provider.
Profile details appear while those reads run. Only readable posts belonging to
that author are included. Metadata-only excerpts carry a **Public preview** label;
video playback is not implied by a thumbnail. These sources can still restrict
content. Tieba thread bodies, images, supplied Chinese timestamps and individual
replies are extracted separately; missing years on forum listings remain unknown.

Default ordinary cache: 500 MB, with age expiry and LRU eviction. Unviewed items
expire after seven days and untouched viewed items after thirty. History/dedup
records expire after ninety days. Active content is protected while visible.
Bookmark records and explicitly downloaded media live outside the ordinary budget.
Remove a saved item to release its unreferenced offline media. Selected library
downloads have the separate image/video controls described above; offline playlists
are not yet supported. Settings expose
storage usage, limits, intervals, list selection, theme and encrypted backup/restore.
Page-cache records reference cached post records, so expiry removes post bodies
without a duplicate surviving in a page snapshot. Decoded images use a separate
24 MB memory limit; downloads and decoding have bounded concurrency.

Settings opens a grouped overview with focused pages for platform tabs, appearance,
privacy, search, feed updates, storage, link opening and encrypted backups. Tap a
value to choose a preset or enter a custom amount. Tab controls show the current
order and let you move tabs both ways. Network protection, filter lists, recent
blocks and source tests have their own pages under privacy. Back returns to the
page you came from; choices and the current page survive activity recreation.

## Build and test

On this laptop, double-click **anti social Preview** on the desktop, or run:

```sh
python3 scripts/preview.py
```

The launcher opens a visible Android 10 emulator and a non-debuggable, locally
signed preview APK when one is packaged. It installs only when the APK or installed
package changes, retaining compiled code on ordinary launches. Its separate
`AntiSocialPreview` device keeps trial data on RedtopStorage. Close the emulator window
to finish. Mount RedtopStorage first after a laptop reboot. Both modes use a normal
boot, preserving the latest app data. Software snapshot restoration failed; KVM
restored a manually saved snapshot but this SDK skipped automatic saving on exit
and required a window resize to fix a black display. Saved-state startup is
therefore disabled by default. `ANTISOCIAL_PREVIEW_SNAPSHOTS=1` enables experimental
manual snapshot testing; restoring an old snapshot can roll back preview data.
`python3 scripts/preview.py --cold-boot` bypasses loading even in that mode.

The launcher checks actual KVM availability. With acceleration it uses four virtual
CPUs, 3 GB RAM and a 720 × 1280 / 320 dpi / 60 Hz display. Its software fallback uses one virtual CPU, 2 GB RAM and a
576 × 1024 / 256 dpi / 30 Hz display, retaining the same 360 × 640 dp interface
while drawing 36% fewer pixels. Preview animations are disabled in software mode
and restored to normal in accelerated mode.
Host GPU rendering remains enabled. Physical-phone performance still needs testing.
If a running preview stops resolving domains while the laptop still resolves
them, close and reopen its emulator window. A fresh boot recovered this failure
during testing and keeps the app's data.

KVM was verified working on this ThinkPad P52 on 2026-10-16 after enabling VT-x.
If a future
firmware reset disables it, the kernel reports `VMX (outside TXT) disabled by BIOS`.
Enable **Security → Virtualization → Intel(R) Virtualization Technology** in the
F1 startup firmware menu and save with F10. Then run:

```sh
bash scripts/enable_preview_kvm.sh
python3 scripts/preview.py --doctor
```

The helper loads the already installed KVM driver; sudo may request the local
administrator password. The launcher switches to the accelerated profile when
the emulator's own check succeeds. [Lenovo's P52 guide](https://download.lenovo.com/pccbbs/mobiles_pdf/p52_ug_20m9_20ma_ug_en.pdf#page=87)
documents the setting; [Android's acceleration guide](https://developer.android.com/studio/run/emulator-acceleration)
describes KVM requirements. One-time app code preparation is available with
`python3 scripts/preview.py --compile`; Android restricts optimization of debuggable
builds, so use the non-debuggable preview for performance checks.
The first preview creation requires the
Android 29 `default;x86_64` SDK system image, which is already installed here.
For graphics-driver issues, try `ANTISOCIAL_PREVIEW_GPU=software python3 scripts/preview.py`.

Linux tooling, caches, emulators and outputs are isolated on RedtopStorage:
`/run/media/red/RedtopStorage/DarwinBuild/LiberBuild`. Override with `ANTISOCIAL_BUILD_ROOT`.

```sh
python3 scripts/bootstrap.py
bash scripts/native.sh android
bash scripts/gradle.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
python3 scripts/package.py
# Non-debuggable laptop preview, signed locally for installation/testing:
bash scripts/gradle.sh :app:assembleRelease
python3 scripts/package.py --preview
# With an owned Android emulator/device connected:
bash scripts/gradle.sh :app:connectedDebugAndroidTest
# Optional external availability checks, separate from unit tests:
bash scripts/gradle.sh :core:liveSmoke
# Diagnose every reviewed candidate and retain public responses externally:
bash scripts/gradle.sh :core:sourceAudit -PliberAuditPlatforms=instagram,pinterest
# CPU-only protection and link-cleaning measurements, without network requests:
bash scripts/gradle.sh :core:filterAudit :core:linkAudit
# Replay a retained public HTML response locally, without network requests:
bash scripts/gradle.sh :core:extractionAudit -PliberExtractionInput=/path/to/retained-nitter-response.html
```

Requires JDK 21, Gradle 9.3.1, AGP 9.1.1, Android SDK 37.0 / build tools 36.0.0,
NDK 27.3.13750724 and Rust with the three Android targets. Android 8.0+ (API 26).
Native Android libraries are generated, not tracked. Use `scripts/import_darwin.py`
and `scripts/bundle_filters.py` to refresh reviewed donor snapshots. Recompile
updated lists with `bash scripts/native.sh`; APK builds reject stale compiled
protection. The gzip seed
has a `.seed` name: Android asset packaging automatically expands a `.gz` file and
removes its suffix, which breaks explicit gzip asset loading.

Preview signing uses the local debug key for installation/testing. A production
release needs a maintained release key. No store publication or production signing
has been performed. See `VALIDATION.md` for actual checks and external limitations.

## Provenance and licensing

anti social and its Darwin routing derivative use GPL-3.0. Independently implemented
social readers record protocol discovery sources, upstream licenses and snapshot
hashes in `licenses/social-data-sources.txt`; no Phice or RSSHub runtime is bundled. ClearURLs is LGPL-3.0; its
rules and notices are retained. Assets include donor hashes and source-list metadata;
the saved Darwin export was imported without modifying Darwin's runtime/profile.
The live browser export was unavailable and has not been represented as verified.
Filter-list authors retain their individual licenses; source headers are in
`licenses/filter-notices.txt`. Dependency notices are in `licenses/DEPENDENCIES.md`.

### Local business and event discovery (0.35.37)

The bottom **Search** destination now has Posts, Businesses and Events. Choose a
city/area explicitly; up to twelve selected areas, their radius and timezone stay
local. No GPS permission or background location tracking is used. Regional lookup
and index queries use `NetworkGateway` through Tor; publisher reads use the
reader's selected content connection. Existing social accounts open through
Darwin's native source routing.

Overture supplies identity links, not just map pins: GERS IDs connect a physical
business to original social accounts, websites, addresses and phone numbers.
Bounded public Fused lookups enrich the local graph; regional Overture GeoJSON
packs can also be imported from the area picker. OpenStreetMap and the business's
own structured metadata/header/footer declarations enrich the same record.
Different branch locations remain separate. Fields retain retrieval provenance
and directory licences; fetching a record is not independent verification of its
claims or proof that a linked social page is currently readable.

Events use public iCalendar, JSON-LD, microdata, h-event markup and advertised
The Events Calendar feeds. Occurrence dates and actual venue locations remain
separate from publication dates and organizer addresses. Recurring sessions stay
distinct; explicit corrections/cancellations update a known occurrence, and a
missing feed entry never invents cancellation. Posters, undated public posts and
JavaScript-only calendars do not become dated local events by guesswork. OCR and
unstructured social-announcement date extraction are not part of this release.

Page acquisition uses the existing passive `NetworkGateway` extractor and the
native reader's inert Markdown cleaning, preserving structured fields before
removing page husks. It runs no page script, browser session, embedded widget or
subresource loader. HTML/Markdown remain transient in memory; discovery retains
only typed records and bounded source hints. Calendars and directory JSON are
parsed directly as data. No AI model, key, account or optional model download is
required for this feature.

Cached results appear first. Slow directory/index lanes cannot hold back a fast
identity result. Each request reads at most ten publisher pages with two readers
at a time; known sources back off after failure. Directory batches are limited
and initially cover at most 15 km, while publisher search uses the chosen area.
The graph expires ordinary records and bounds its database payload/index/source
caches. Saved business/event snapshots are independent protected local copies,
searchable with **Saved** and accessible from Library's collection menu. Reader
exports include these snapshots and eligible area settings; source packs do not.
See [the native format](../docs/PORTABILITY.md).

The hosted routes are source dependent. Fused's public example has no production
availability guarantee; Open-Meteo hosted free access has non-commercial terms.
Private.coffee offers Overpass access for applications; its
[provider privacy policy](https://overpass.private.coffee/) applies, with
[OpenStreetMap attribution](https://www.openstreetmap.org/copyright). The public
Overpass fallback is a small-scale route, not infrastructure for an unbounded
commercial directory. Larger distribution needs suitable hosting/service terms.
Complete parser and data-source notices are bundled under
`assets/local-discovery-notices/`.


### Business discovery refinement (0.35.38)

Businesses now use bounded parallel name/category lookup through Photon (OSM),
query-specific Overpass, the chosen Tor-protected public index, Overture identity
cells around actual matches, and passive publisher/directory readers. A fast
publisher result can populate its native card before slow geographic routes finish.
All app traffic stays in NetworkGateway. Publisher HTML is washed to inert Markdown
in memory, with structured/contact fields retained; no page code executes and no
HTML/Markdown temporary files are written. No AI, paid key, GPS or root is needed.

The selected radius is used for named/category directory requests. Taco/tacos,
common category spellings, business-name variants, street abbreviations and
compatible coordinates improve matching. Existing cached duplicate businesses
are reconciled while saved snapshots remain protected. Map branches at distinct
locations remain separate. Plain HTML contact fallback requires a single explicit
local address; review writers and roundup publishers do not become businesses.

Business accounts, explicitly named owners/operators/founders, and directory
publishers are different relationships with retained source URLs. Header/footer
accounts require matching site identity to become business accounts. Earlier
mistaken footer associations are reclassified as directory-publisher information.
Account buttons require usable routed public profile/content data; unknown,
empty and failed accounts stay hidden, with bounded positive/negative lookup caches.
Failure backoffs and per-request budgets avoid repeatedly testing dead paths.

The area picker offers km/mi and remembers the choice (US locales default to miles).
Changing units does not alter the chosen geographic radius. Radius presets retain
the existing kilometre storage precision. Business sheets have labelled contact
rows, dial/email actions, readable categories, source details and separated related
entities. Directories are distinguished from business websites when provenance
establishes that distinction.

Source limits remain: the public Photon and Fused example services have no uptime
promise; Overpass may be unavailable. Incomplete/JS-only pages, login walls and
undated announcements cannot be made into complete business/event records.

### Cards first, deeper business details second (0.35.39)

Local search now separates delivery from enrichment. Useful cached or newly found
cards are ready immediately; a small background indicator replaces the large
search state while missing details are filled. Later businesses append, and field
updates retain the position of cards already being read. Open business sheets
receive new details without reopening them. Changing the query, leaving Search or
pausing details cancels that work.

A deterministic field planner prioritizes missing websites, phones, public accounts,
hours, email and visit information. It learns what each read supplied. Repeatedly
unchanged sources rest longer; failures back off separately. Supplemental requests
yield to foreground reading/media, and each search has finite page, index, brand
and time budgets. There is no background crawl after the screen closes and no AI
classification or paid service requirement.

[AllThePlaces](https://alltheplaces.xyz/) adds published brand slices and regional
place packs. anti social reads at most two matching brand files, filters them locally to
the selected area and retains their upstream lineage. It never downloads the global
archive or runs the spiders. Overture and AllThePlaces can share upstream data;
matching records are not counted as independent confirmation.

Business-scoped metadata and owned contact pages can supply menus, reservations,
ordering/service links, cuisine, price range, payment, accessibility declarations
and dated special hours. Available links appear in the detail sheet; longer visit
information stays folded. Nothing infers stock, public admission, accessibility or
ownership from missing fields. These facts use the existing evidence/saved/export
format and remain available in protected saved snapshots.

Advertised Localist, Trumba/25Live and Events Manager calendars join the existing
JSON-LD, microdata, ICS, RSS and WordPress event routes. Localist reads have bounded
pagination; range-limited calendar caches track their date windows. A healthy empty
calendar is distinct from an unreadable page. See [routing and source coverage](../docs/LOCAL_DISCOVERY_ROUTING.md)
for the actual limits and the additional source families considered for future adapters.

### Event reconciliation and social connections (0.35.40)

Incoming and cached event copies now share occurrence reconciliation. Specific
event links and matching venue/wall-clock evidence can reconcile a calendar's
incorrect timezone declaration without swallowing a separate performance. Minor
year suffixes and an address appended to a venue name do not create duplicate
cards. Sources and evidence stay attached; saved snapshot bytes and ordinary
expiry are preserved. The newest stated edit wins, with retrieval time used when
an edit time is absent. Open event sheets receive those updates as they arrive.

Business enrichment preserves newly extracted accounts and contacts when adding
a washed description. Matching domains support compatible shortened/compacted
publisher names. Later source candidates participate in routing, and newly known
owners can bind previously unowned sources while retaining failure cooldowns.
Account checks run alongside page enrichment with a bounded independent allowance.
Only usable public account data enables account buttons; declared links remain in
the source records when Facebook, Instagram or another route is unavailable.

### Following the signal (0.35.41)

Context can follow selected citations from a retrieved article into the underlying
study, report, dataset, filing or full recording. Paper identifiers can lead to
publication updates and their actual notice text. Community Notes supplied by an
existing X route retain their citations and can feed those sources into Context.
The app does not assign a rating that the route did not supply.

This is bounded supplemental work: primary material can produce the first brief
while deeper sources continue, repeated destinations share a read, and stored
paths retain their parent documents across interruption and refresh. Existing
Darwin public-copy, archive and caption routes do the retrieval. No additional
model, paid lookup service, page scripts or automatic scan submissions are needed.
The native article reader exposes the selected underlying sources without AI.
Optional source/account details stay behind the existing controls. See
[signal discovery and source limits](../docs/SIGNAL_DISCOVERY.md).
