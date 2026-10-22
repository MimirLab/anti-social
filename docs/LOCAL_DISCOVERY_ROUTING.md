# Local discovery routing

anti social reconstructs a local business or event record from observed public sources.
Darwin supplies the social routing donor; this remains a standalone native reader.
The business layer uses deterministic routing and extraction, without a model,
website script execution, GPS, root, service account or paid key.

The [local opportunities extension](LOCAL_OPPORTUNITIES.md) adds geographically
gated publishers, LiveWhale/SpaceAPI/Linked Events readers, offered calendar exports
and bounded address resolution while keeping this staged delivery model.

## Delivery and enrichment

Cached records publish first. Independent place, identity and selected-index routes
can deliver the first new cards. A ready card ends the large loading state even
while other lanes finish. Later businesses append to the existing deck; matching
identifiers preserve position through a merge. Open sheets observe updated records.

The second stage checks missing field groups: website, phone, public accounts,
hours, email, description and visit information. Known contact/menu/calendar routes
are ranked by the fields they can provide and by their observed yield. Two targeted
index queries can find missing publishers, while published brand data can connect
branches to websites and contacts. Unknown index links remain discovery candidates;
a search snippet is never a business fact or proof of account ownership.

Supplemental traffic uses NetworkGateway's cancellable deferred child, yielding to
foreground navigation and media. A cancelled read is not a source failure. Leaving
Search, changing its query/area/mode or pausing details cancels the search generation;
late callbacks cannot write into the next request. There is no recurring enrichment
worker or idle crawl. The existing adaptive social heartbeat remains separate.

Public-account validation starts alongside supplemental page enrichment, with its
own two bounded slots (at most eight checks per search), so slow pages cannot use
up every account-validation opportunity. Account candidates rotate across matching
businesses before checking additional aliases. Successful checks share canonical
account cache identities; unknown, empty and unavailable accounts remain hidden.
The full bounded source queue participates in route selection, including later
discoveries. A subsequently identified owner can bind an earlier unowned source
without clearing its failure cooldown. In-memory description fallback extends the
newly extracted record and preserves its contacts and social connections.

## Current routes

| Route | Use | Bound and source distinction |
| --- | --- | --- |
| Overture via the existing Fused route | Physical identities, websites, phones and social accounts | City cell plus two precise-match cells; ordinary results expire. Public example availability is source dependent. |
| Photon / OpenStreetMap | Named/category place discovery | One geographic query, 20 results, 512 KiB. Shares OSM identifiers with Overpass. |
| Overpass | Targeted names/categories and local organizer hubs | Selected radius, 350 results, 2 MiB; unavailable routes rest. |
| Selected public index | Discover publisher pages | Two initial regional queries; two additional field-directed queries, at most six hits each. Uses the selected provider and Tor. |
| AllThePlaces | Existing first-party-derived brand outputs | Published run HTML and statistics select at most two matching files. Only spiders with 1–6,000 reported features qualify; each decoded file is limited to 8 MiB. No global ZIP or spider execution. |
| Publisher/directory readers | Structured branch facts, contacts and explicit source links | Eight initial page reads plus ten supplemental reads; two readers share a semaphore. Each page is limited to 2 MiB and 18 seconds. |
| Public social routing | Verified business profiles and linked public material | Existing Darwin-derived source pool, alternates and allowed original fallback. Eight account checks, two concurrent, 16 seconds each; an empty frontend is not an account. |

The supplemental stage has a 70-second deadline after primary discovery finishes.
It can end sooner, yield to visible traffic or exhaust its request budget. Missing
information stays missing. A specific public service may contribute nothing while
other lanes continue to produce useful cards.

## Restaurant menus

Menus are an additional field-directed lane, after the first business cards.
Opening a restaurant can also follow its known menu routes without starting a new
web-index search. Cached menu text appears immediately. An explicit menu request
has at most four reads, 14 seconds per read and a 35-second overall deadline.
Two menu readers are admitted at once; simultaneous reads of the same restaurant
route share their result. Query/navigation cancellation prevents late writes.
Event searches do not spend their supplemental read budget on menus.

The bounded pool is ranked **before** its six-route cap. Verified location menus
and hosted tenant routes precede aggregator fallbacks; a featured-items excerpt
does not complete the missing-menu field. One useful full-menu/text acquisition
ends an explicit read instead of spending its remaining budget on fallbacks.
Websites backed by the matching street address rank above unverified brand-name
domains; known directories and identified source publishers retain their roles.

A directory listing does not own every “Menu” or “Order online” link in its DOM.
Follow the matching listing's menu path/record ID, fresh typed menu declarations,
a separately established restaurant website, or an isolated matching business
card. Recommended restaurants, regional category links and upload/edit tools are
excluded. Existing cached menu evidence passes these checks too; inadmissible
cached queue entries cannot spend the supplemental budget. Protected saved copies
are not rewritten to repair ordinary routing evidence.

Location identity precedes menu extraction. Typed restaurant names, numbered
streets, cities, postal codes and declared countries reject a conflicting branch.
A restaurant-endorsed link authorizes reading that source; it does not by itself
confirm branch-specific prices. An observed matching address, single matching
phone, or location-directory menu declaration supplies branch scope. Shared
menus say “Location prices unconfirmed.” Brand IDs never become location IDs.

| Route | Current behavior |
| --- | --- |
| Restaurant-owned pages / Schema.org | Inert Restaurant → Menu → MenuSection → MenuItem JSON-LD, local graph references, explicit offers, sizes, currency, availability and declared dietary/allergen fields. HTML menu records keep the dish beside its own price; ordering forms are unwrapped, controls removed, and nothing is submitted. |
| OpenStreetMap | `website:menu` and `contact:menu` retain the place identity and ODbL evidence. |
| FromTheRestaurant | Recognize the published locator fingerprint and matching `data-lid`; derive that tenant's public `/menu/?lid=…` GET. No session selection, timezone post, account, ordering action or script execution. Ambiguous branch IDs are rejected. |
| GloriaFood / FoodBooking | Literal published `data-glf-cuid`/`data-glf-ruid` button identifiers lead to the public menu page. Never execute the embed. |
| Menumigo / GetMyMenuOnline | Recognized tenant links can use the documented public JSON menu representation. No citywide sweep or invented tenant slug. Contracts are fixture-tested; documentation examples returning 404 are not working-content evidence. |
| Other ordering, hosted-menu and directory pages | Recognize offered Toast, ChowNow, Popmenu, BentoBox, iMenuPro, Menutech, GoTab, Clover, BeerMenus, StreetFoodFinder, Allmenus, MenuPix and Restaurantji links; use supported passive HTML/structured data. Recognition does not imply that a provider's JavaScript-only, unavailable or account-gated menus are extractable. |
| Offered PDFs | Download at most 4 MiB through NetworkGateway. A separate local media process reads embedded text with PdfBox-Android, without enabling AI or downloading a model. Limit reading to six pages and 20,000 characters; scratch streams are bounded to 8 MiB. Ambiguous columns remain readable document text rather than invented dish-price pairs. Scanned image-only menus remain available as original documents; automatic OCR is not part of this menu lane. |

Pickup, delivery, dine-in, catering and specials remain separate menu editions.
Unspecified sales channels stay “Published menu.” A newer edition replaces older
dishes and titles for the same source/channel; it does not union removed dishes
back into the menu. Redundant same-site “Menu highlights” disappear when a larger
menu contains those exact dish prices. No price, currency, allergen, availability
or dietary property is inferred. The native sheet offers local dish search,
section headings, check date, partial-coverage labels and the original menu.
An expandable “Other published menus” also retains offered documents that could
not be converted to text, including the restaurant's newer scanned sheets.

Unchanged useful reads increase their rest from six hours to at most one day for
menu data. Failures use the existing 15-minute-to-one-day backoff. There are no
extra menu health pings. PDF content hashes avoid reparsing unchanged binaries.
Ordinary records retain at most four menus, 160 dishes and a 40,000-character
combined menu budget within the existing bounded discovery store. Saved copies
and native exports retain their captured menu text independently of expiry.
Old saved records may omit the optional `menus` field; nested imported menu data
has strict types, HTTPS source validation and size checks.

HTML/Markdown is processed in memory. Disposable PDF binaries are deleted after
success, failure and cancellation; ordinary startup/cache maintenance removes
abandoned files while protecting active readers. No menu PDF is a saved download
unless the user explicitly saves material through the existing reader. The media
worker has no networking client or remote fallback; it is a separate app process,
not a separate Android permission sandbox. PDF actions, scripts and attachments
are never executed by the text-only extraction path.

Source references: [Schema.org Menu](https://schema.org/Menu),
[Menumigo developer reference](https://menumigo.com/developers),
[GetMyMenuOnline public format](https://getmymenuonline.com/developers),
[OSM menu tag](https://wiki.openstreetmap.org/wiki/Key:website:menu),
[PdfBox-Android source](https://github.com/TomRoush/PdfBox-Android/tree/v2.0.27.0).
PdfBox's full upstream license/notice is bundled in `restaurant-menu-notices`;
unused transitive encryption/signing libraries are excluded.

## Field and identity rules

Typed JSON-LD/microdata describes a particular business or branch. Additional
contact-page facts require matching site identity and reject a conflicting sibling
branch. A single published phone can fill a contact gap; unrelated directory
footers cannot declare business ownership. Explicit owners, operators and founders
retain their role and source. A directory publisher is a separate relationship.

Visit facts include published menu/reservation/ordering/service/calendar links,
cuisine, price range, payment, available services, explicit accessibility features
and dated special hours. Negative accessibility declarations remain negative;
absence never means accessible. General hours, special hours, event occurrences
and registration availability remain different facts. Public institutional event
audiences are retained in descriptions rather than implying public admission.

The existing merger normalizes compatible business names, addresses and phones,
with explicit branch/suite conflicts kept separate. Strong address/phone evidence
can correct a coarse map pin. Source evidence retains values, source URLs, retrieval
time and kind; field diversity prevents many later copies of one name from evicting
a useful menu or accessibility observation. Reading several copies of the same
upstream data does not establish independent confirmation.

AllThePlaces records preserve spider, stable branch reference, brand identifier
when supplied, CC0 attribution and upstream source URI. A brand identifier never
becomes a physical-branch identity. Geographic filtering happens locally; branch
data elsewhere in a brand file is not stored in the selected region's graph.

## Calendar adapters

| Family | Discovery and extraction |
| --- | --- |
| Generic ICS | Offered calendar/feed links; existing bounded recurrence, exceptions, timezone and cancellation parsing. |
| JSON-LD, microdata, h-event | Typed occurrence dates and actual venue/location facts. Publication dates cannot become occurrence dates. |
| RSS / Atom | Feed entries lead to actual announcement pages. Ordinary publication dates do not become events. |
| The Events Calendar | Existing detected WordPress public event JSON route. |
| Localist | Recognized generator/application metadata, script hosts or advertised API links; institution `/api/2/events`, selected dates, 50 entries per page and at most two pages. Stable instance IDs preserve distinct occurrences. |
| Trumba / 25Live Publisher | Recognized embed host and literal advertised calendar web name; published ICS, bounded selected date range. No embed code executes. |
| Events Manager | Recognized installed plugin script path leads to its conventional public ICS export. |

Range-limited routes record the window they read, so switching from a week to a
month does not reuse incomplete coverage as a completed search. Failure cooldowns
still apply. Structurally valid empty calendars can be healthy without inventing
events. Rolling feed disappearance never implies cancellation. Partial-page limits
are reported in Source details, and the original listing remains available.

Occurrence reconciliation applies to both incoming and already cached events.
Candidate keys include normalized title/date and specific event links; the merge
still requires matching occurrence and venue evidence. A shared specific event
link, matching wall clock and a calendar observation can reconcile a publisher's
conflicting timezone declarations. A shared feed or an approximate one-hour gap
cannot. Different dates, performances and venues remain distinct. A suffix naming
the occurrence's year does not create a second event. Other source links and field
evidence remain attached to the merged card.

For matching occurrences, the newest stated update time wins; retrieval time is
used when a source omits an update date. Sequence numbers only override freshness
within the same explicit source-occurrence identity. An open event sheet follows
updated records. Complete matching text can fill a newer listing's truncated
excerpt. Reconciliation does not renew cache expiry or rewrite protected saved
snapshots. A listing without a public update date cannot prove when its publisher
last edited it.

## Rest, storage and protection

Observed field capability and a timestamp-independent payload fingerprint are
cached per source. Independent unchanged observations double a six-hour rest toward
seven days; changed content resets that stability. Failures back off from 15 minutes
toward 24 hours. Explicit refresh can retry sooner. This learns from useful reads
and does not add health pings.
Event searches instead start with a 30-minute source rest, capped at six hours;
changed venue, price, registration and occurrence details reset that stability.

| Cache | Normal rest / expiry |
| --- | --- |
| AllThePlaces run and bounded catalog | One day |
| Selected brand/region slice marker | Seven days; failed slice one hour |
| Initial index hits / extra field query | 30 minutes / six hours |
| Verified public account | Six hours; failures two to 30 minutes |
| Per-source yield history | 14 days |
| Ordinary graph / source queue | Existing 30-day expiry, record and byte ceilings |

Graph payloads remain capped at 32 MiB, auxiliary cache payloads at 8 MiB with 256
entries, and source queues at 768 entries. Protected saved copies are independent.
Visit details use existing field evidence, so older native exports remain readable
and newly saved copies retain the extra facts without a new export schema. Database
upgrade 5 requeues successful pages for the improved parser on the next explicit
search, retaining failed-source cooldowns and saved snapshots.

Every app request uses NetworkGateway, including redirect/filter checks. Regional
lookups, public index queries and AllThePlaces requests use Tor and fail closed.
Publisher reads follow the selected content connection. HTML is washed to inert
Markdown in memory; typed facts and bounded source hints survive. No page script,
widget, browser session, temporary HTML/Markdown file or unseen link-clicking engine
is introduced.

## Further source families

The supplied research is a collection map rather than a claim of implemented
coverage. Public offered feeds/pages from libraries, chambers, religious/community
groups and municipal calendars can already enter generic extraction. The following
remain candidates for explicit adapters or regional data packs:

- AllThePlaces regional extracts and additional store-locator formats; scoped
  Wikidata identity enrichment and specialist place datasets.
- Library and recreation catalogs, OpenActive, OpenAgenda, Gancio/Mobilizon and
  additional published-calendar families.
- Municipal GIS, licenses/inspection records, meeting agendas and permitted events,
  retaining storefront versus legal-address and application versus approval roles.
- Public newsletters, flyers, menus/PDFs, products and temporary business notices.
  Existing media engines are not automatically wired into this collector: poster
  OCR, product stock inference and prose-to-event date extraction are not claimed.

Additional endpoints should earn a place through useful public data and bounded
retrieval, then reuse the same field evidence, branch matching, deferred traffic,
cache and failure-isolation rules.

## Sources

- [AllThePlaces](https://alltheplaces.xyz/), [data format](https://github.com/alltheplaces/alltheplaces/blob/master/DATA_FORMAT.md), [published-run approach](https://github.com/alltheplaces/alltheplaces/blob/master/docs/WEEKLY_RUN.md).
- [Schema.org LocalBusiness](https://schema.org/LocalBusiness).
- [Localist API](https://developer.localist.com/doc/api).
- [Trumba published feeds](https://www.trumba.com/help/api/customfeedurls), [25Live Publisher](https://25livepub.collegenet.com/help/api/customfeedurls).
- [Events Manager iCalendar feeds](https://wp-events-plugin.com/documentation/event-ical-feeds/).
