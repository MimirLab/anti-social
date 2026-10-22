# Capabilities and limits

This describes the development branch. The latest official APK may precede its
announcement/OCR and search improvements; consult its release notes.

| Capability | What the reader gets | Limits |
|---|---|---|
| Public social reading | Native cards, selected subscriptions, public search, alternate sources and archive recovery | Coverage depends on platform and route. Login walls and empty frontends are failures. Private/account-only content is unavailable. |
| Collected posts | Cross-platform editions, repeated text/media cleanup, original links and comment streams | Similarity is imperfect. Originals and meaningful differences remain inspectable. |
| Chronological reading | Preserved position, pending updates, caught-up boundary and channels | An unavailable subscription remains pending rather than falsely caught up. |
| Local discovery | Businesses, actual branches, published contacts, social accounts, menus and event occurrences | Directory entries can be stale. Menus/prices retain their source and mode; a location pin is not proof of current opening or stock. |
| Social announcements | Explicit event details from identified local publishers and their public posts | Missing dates/venues stay under Possible events. Publication time is not the occurrence time. |
| Flyer reading | Local English OCR after initial results, without enabling AI | Recognition can be wrong; uncertain images remain candidates. At most two new flyers per search. Extra language packs remain optional. |
| Saved material | Protected local copies and local search/export | Ordinary expiring cache is separate. Availability of selected media/thread content is recorded at capture time. |
| Context | Optional model-assisted briefs over retrieved post/link/comment evidence | Selection can omit useful facts; preserved sources are inspectable. Unavailable material contributes no invented facts. Read progress and usable briefs are cached. |
| Video links | In-app playback, SponsorBlock where supported and recovery routes | Hosting, captions and surviving copies are source dependent. YouTube is a controlled linked-video medium, not a new social-feed tab. |

## Connection behavior

Every app request uses NetworkGateway, including redirects, images and updates.
Regional discovery and public-index search use Tor. Reading follows the selected
connection policy, and selected Tor fails closed. There is no silent direct
fallback when Tor is unavailable. Passive page extraction executes no website
scripts or embedded widgets. Image/PDF decoding still processes untrusted bytes
in bounded local workers; passive acquisition is not a claim that formats are
incapable of containing decoder vulnerabilities.

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
