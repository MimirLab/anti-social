# Capabilities and limits

anti social has public Android releases for stock, unrooted devices. This page
describes the current checkout; select the installed release's tag for its exact
source and qualification. [Architecture](ARCHITECTURE.md) connects the implemented
protection and routing layers; [reviewing](REVIEWING.md) distinguishes automated
qualification, dated live checks and ongoing user-reported phone testing.
The [0.38 subscription/search qualification](PRODUCT_POLISH.md) records both
successful live paths and unavailable sources.

| Capability | What the reader gets | Limits |
|---|---|---|
| Public social reading | Native cards, selected subscriptions, public search, alternate sources and archive recovery | Coverage depends on platform and route. Login walls and empty frontends are failures. Private/account-only content is unavailable. |
| Collected posts | Cross-platform editions, repeated text/media cleanup, original links and comment streams | Similarity is imperfect. Originals and meaningful differences remain inspectable. |
| Instagram public comments | Native All/platform discussions and recent comment pagination, also available as retrieved Context material | Original-site access is required. Replies can be incomplete; source gates/quotas remain failures. See [qualification](INSTAGRAM_PUBLIC_COMMENTS.md). |
| Threads public pages | Complete supplied galleries, selected reply snapshots and native keyword-search posts | Original-site access is required. Search windows can be limited; account search, source-side recent sorting, continuation and preview-quota renewal remain unqualified. See [reader details](THREADS_PUBLIC_READER.md) and [keyword search](THREADS_PUBLIC_SEARCH.md). |
| Related-account discovery | Streamed public follows, creator recommendations, observed participants and communities; identity bridges across platforms | Sampled public coverage, bounded work, unavailable routes visible. No complete-audience or common-ownership inference from a shared name. See [Discover related](ADJACENT_DISCOVERY.md). |
| Chronological reading | Preserved position, pending updates, caught-up boundary and channels | An unavailable subscription remains pending rather than falsely caught up. |
| Local discovery | Businesses, actual branches, published contacts, social accounts, menus and event occurrences | Directory entries can be stale. Menus/prices retain their source and mode; a location pin is not proof of current opening or stock. |
| Social announcements | Explicit event details from identified local publishers and their public posts | Missing dates/venues stay under Possible events. Publication time is not the occurrence time. |
| Flyer reading | Local English OCR after initial results, without enabling AI | Recognition can be wrong; uncertain images remain candidates. At most two new flyers per search. Extra language packs remain optional. |
| Saved material | Protected local copies and local search/export | Ordinary expiring cache is separate. Availability of selected media/thread content is recorded at capture time. |
| Post information | Immediate file declarations; on-demand recipes, NovelAI PNG signatures and linked Content Credentials | Deterministic and available with AI off. Format, size and request limits apply; no statistical image detector or external image upload. See [Media Info](MEDIA_INFO.md). |
| Context | Optional model-assisted briefs over retrieved post/link/comment evidence | Selection can omit useful facts; preserved sources are inspectable. Unavailable material contributes no invented facts. Read progress and usable briefs are cached. |
| Linked scientific articles | Native text from matching public HTML, PDF candidates and Europe PMC structured full text; the same retrieved material can feed Context | Public-copy coverage varies. Abstract-only XML and wrong publication identifiers are rejected. Figure/table images retain captions and source links; pixels and supplementary files are not automatically read. See [scientific full text](SCIENTIFIC_FULL_TEXT.md). |
| Video links | In-app playback, SponsorBlock where supported and recovery routes | Hosting, captions and surviving copies are source dependent. YouTube is a controlled linked-video medium, not a new social-feed tab. |

## Connection behavior

**Settings → Privacy & connection → Tor for all traffic** applies Tor to the
app's ordinary content, photos, videos, probes, archives, downloads and updates.
The embedded transport needs no root or separate Tor app. This is an app-wide
control in addition to the Tor-required search connection; selecting another
frontend does not change the selected privacy mode. See the
[connection matrix](ARCHITECTURE.md#connection-modes-and-app-wide-tor).

Every app request uses NetworkGateway, including redirects, images and updates.
Regional discovery and public-index search use Tor. Reading follows the selected
connection policy, and selected Tor fails closed. There is no silent direct
fallback when Tor is unavailable. Passive page extraction executes no website
scripts or embedded widgets. Image/PDF decoding still processes untrusted bytes
in bounded local workers; passive acquisition is not a claim that formats are
incapable of containing decoder vulnerabilities.

X has an additional [logged-out profile extraction path](X_PROFILE_FALLBACK.md)
behind alternate routes. Its short public window is explicitly limited; an
unbridged reading gap remains pending. It adds no X search/history/comment claim.

## Optional models: shipping versus research

Ordinary reading, deterministic filters, matching and local discovery require no
language model. Local text runtime installation, model download and removal are
separate from enabling a remote provider. Preferences and keys remain local.

The qualified shipping local text baseline uses a **2,048-token active window and
F16 KV**. Actual runtime metadata, counted prompts and reserved output determine
Context task sizes. A model catalogue's advertised window does not override that
active limit. Larger remote runtimes use their reported capacities conservatively.

Protected compressed caches, positional scaling and speculative decoding have
research implementations and measured experiments. Availability in native source
does not mean a profile has passed physical-phone quality, memory and latency
qualification. Experimental debug options must not be represented as shipping
defaults. See [Context evaluations](CONTEXT_EVALUATION.md), [AI foundation](AI_FOUNDATION.md)
and [release gates](RELEASE_QUALIFICATION.md).

## Event interpretation

Search understands common activity/audience terms and exclusions without a model.
An unspecified age range or outdoor setting is marked rather than invented.
Explicit conflicts are excluded; Free requires stated free admission. Complete
results remain chronological and geographically scoped. Incomplete announcements
are optional inspection material, not confirmed nearby events.
