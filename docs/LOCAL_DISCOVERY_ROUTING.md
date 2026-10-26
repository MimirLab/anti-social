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

### Detail delivery and reading panels (0.38.33)

An explicitly started regional search uses a local scheduling marker in
NetworkGateway. Its initial directory/index requests can coexist with the search's
deferred detail reads; ordinary article navigation and media still preempt deferred
work. The marker changes scheduling only, retaining Tor/session enforcement,
filters, request purposes, redirect checks and existing source/byte allowances.
The first deck starts enrichment without waiting for unrelated discovery lanes.
Published brand-location downloads have one separate slot, so they cannot hold the
light contact-search slot. Late initial discoveries trigger another bounded pass;
there is no unbounded rescan or background subscription.

### Chain cards and distance-ordered branches (0.38.40)

The reading deck groups locations after relevance/locality filtering and before
its 160-card allowance. The existing 2,000-record local read bound remains; a
chain's branches therefore share a card allowance while independent businesses
can still appear. Stored business identities, addresses, phones, hours, menus,
source evidence and saved copies remain separate branch records.

Overpass and AllThePlaces retain scoped brand declarations, including brand
identifiers when supplied. The matcher also reads Schema.org brand relationships
and compatible first-party website/name pairs. Intact legacy OSM tag/value keyword
pairs can recover an explicitly recorded brand name from cached records; arbitrary
keywords, a matching name or a shared operator do not establish a chain. These
rules use the distinction between OSM's [brand](https://wiki.openstreetmap.org/wiki/Key:brand)
and [brand identifier](https://wiki.openstreetmap.org/wiki/Key:brand:wikidata).
They describe a reading group, not a legal ownership assertion.

Prepared per-record keys and indexed candidate buckets replace repeated pairwise
website parsing. Before applying weak associations, the matcher checks their full
potential components for conflicting brand IDs. A no-ID or multi-website record
cannot transitively bridge different known brands. Conflicting official websites
also prevent name-only grouping. Ambiguous records retain distinct card keys.
Identity preparation and grouping run in the existing background projection.

The outer deck preserves card positions and keys as discoveries stream in. Each
chain's own location list independently sorts by distance from the selected area
point, with unknown coordinates last. A nearer branch becomes the card's initial
location without replacing a branch already open in the details panel. The picker
shows branch distances and expands twelve locations at a time. Activity lookup
keys are retained across unchanged card compositions. No extra search request,
provider, model, dependency or permission is introduced.

Independent search, menu and filter transitions update their shared state
atomically. Completing a search cannot restore an older menu-loading value;
late search progress also rechecks its generation inside the state update.

The business directory batch marker advances so fresh regional reads can retain
brand fields; unchanged event-hub batches remain reusable. Existing directory
backoff, Tor/session policy, locality boundaries and ordinary cache expiry remain.

### Regional directory admission (0.38.39)

One repository-wide reader handles Overpass batches. It sends the existing
generated geographic/category query as a form POST through `NetworkGateway`,
using required Tor, an exact session and origin pin, and the existing 2 MiB
response cap. It retains the two existing independent operators, at most two
serial attempts, the 24-second per-read deadline and the controller's 50-second
outer allowance. It adds no probes, hosts, concurrent directory attempts, model
or package. Other discovery lanes still deliver their own results progressively.

Recognized business categories compile to their typed tag predicates without
also adding name, brand and alternate-name regex branches for category words.
Unknown names, names mixed with unresolved words and quoted literal searches
retain their identity branches. The original query still reaches the existing
name-directory and publisher-search banks. Business batch-cache identity advances
for this plan; unchanged event-hub batch identity remains reusable.

Successful nonempty network delivery times rank the next eligible endpoint. Queue time,
JSON extraction and SQLite ingestion do not become network-latency samples.
Small endpoint records use the existing bounded ordinary cache for seven days;
they contain no entered queries or coordinates. Query-specific pauses retain
only a hash of the endpoint and generated expression, for five minutes.

A reported query timeout/memory allowance or HTTP 400/414/422 does not disable
unrelated queries. Endpoint capacity/transport failures instead start a short
15-second adaptive pause. HTTP 429, authentication and challenge responses stop
the current directory operation, with a default 60-second adaptive pause.
Bounded `Retry-After` seconds or HTTP dates can extend an endpoint wait up to one
day, including an explicit wait accompanying a query-status failure. These
waits retain the longest valid value if a response repeats the header. Empty
query results do not train an outage or a productive delivery sample. Retry
deadlines survive skipped calls and forced searches. Existing legacy pauses
remain honored until their original cache expiry.

Admission checks known pauses before starting/waiting for transport and again
after acquiring the shared directory slot. Cancellation or a changed connection
selection/session neither trains an outage nor advances to another operator.
Origin-changing redirects remain blocked. Overpass remarks still prevent an
incomplete response from becoming a complete directory result; an ordinary
successful empty `elements` array remains a query-specific empty result.

The [Overpass resource documentation](https://dev.overpass-api.de/overpass-doc/en/preface/commons.html)
distinguishes HTTP 429 rate refusal, HTTP 504 resource admission and per-query
runtime/memory limits. Server queuing can consume part of the unchanged client
deadline; a client timeout alone does not establish a long endpoint outage.
[Private.coffee's published guidance](https://overpass.private.coffee/) recommends
form POST, a meaningful project User-Agent and limited concurrency. The reader
follows those mechanisms while retaining OSM identities, field evidence and
ODbL attribution. Qualification and external observations are recorded separately.

### Default-provider recovery (0.38.38)

DuckDuckGo search now shares one native representation reader between social
search, entity discovery and local publisher discovery. The chosen provider and
entered concrete query stay fixed: HTML GET, the existing HTML form recovery and
the official Lite page are representations of that provider. This never switches
a DuckDuckGo query to Bing, Darwin or another search operator.

A productive first read within the grace period uses one request. After 3.5 seconds of waiting,
a second representation can run alongside the first; the grace period does not
shorten the first read's deadline. A known HTML server-error page promotes the
existing form recovery immediately. Each concrete first-page index query spends
at most three representation reads, including forms. Two ordinary readers and one reserved
Lite reader are shared across the Gateway, preventing queued ordinary searches
from occupying every recovery slot. Each local read also takes the existing
repository-wide local index permit. The productive format is remembered for six
hours across at most sixteen source roots, bound to the current opaque network
session. This memory contains no queries and adds no idle worker or probe.

Foreground search preempts deferred network work before waiting for a reader
slot. Initial discovery retains its scheduling marker and can coexist with its
own supplemental detail reads. Cancellation releases the slots. Unready required
Tor, a changed session/connection selection, changed original-access permission or
reader-policy revocation prevents the old operation from continuing. Requests and
forms retain exact origin pins and their byte allowances: 1 MiB for local index
material, with the existing public-search and health-read bounds elsewhere.

The common parser handles ordinary HTML results and Lite table results, excluding
advertisements and decoding destination wrappers locally. It scans a bounded
organic window before platform filtering, preserving later platform matches after
earlier off-site results. Article titles or snippets discussing server errors or
challenges do not themselves establish a provider refusal. Actual challenge forms
still do. Publisher search retains links/titles; snippets do not become business
or event facts. Linked material still goes through the existing acquisition,
washing, extraction and geographic/date admission pipeline.

Lite continuations use a distinct `search-lite-form` envelope; legacy
`search-form` envelopes remain HTML-only. Each retains its actual issuer URL,
path, query and bounded allowed form fields. Foreign origins, credentials,
changed queries, navigation paths and ambiguous controls are rejected. An HTML
`base` element cannot redirect the continuation. A continuation does not retry
through another representation. Existing outer query-plan and directory bindings
remain in force. The selected logical source stays DuckDuckGo; provenance records
the actual acquired HTML or Lite URL.

Refusals stop new representation attempts. Shared feedback retains the strongest
observed refusal across parallel replies; a later shorter wait cannot overwrite
it. Already completed useful material can coexist with a pause. Local routing
records that combined outcome as one partial observation. A pause learned while
work was queued is denied admission, rather than counted as a new server failure
or used to renew its deadline. Known query-specific empties also remain local
eligibility decisions.

The [official non-JavaScript documentation](https://duckduckgo.com/duckduckgo-help-pages/features/non-javascript)
identifies both HTML and Lite. Separate protected Android observations returned
organic material in 31,376 decoded bytes for HTML and 22,339 bytes for Lite.
Those are dated responses, not a controlled speed or phone benchmark. This native
implementation adds no search package, model, permission or setting. See
[release qualification](RELEASE_QUALIFICATION.md) for the controlled counterexamples
and current native validation.

### Independent publisher links (0.38.37)

The local publisher bank now includes one qualified 4get endpoint alongside the
existing LibreY routes. This is a separate `publisherSearchSources` bank: it does
not advertise native social-search support or add a new user-selected provider.
Selecting Bing or DuckDuckGo still uses that provider alone. Regional requests
remain Tor-only through NetworkGateway, with pinned origins and captured sessions.
The existing three-read ceiling and three-reader concurrency limit also cover the
additional protocol. No idle probes or continuous crawl are introduced.

The small native reader uses `/api/v1/web?s=…`, retaining only organic `web`
destination/title pairs after checking the protocol's explicit `status: ok`.
HTTP 200, answer panels, snippets and related searches do not establish content
or facts. Missing/wrong shapes, duplicate critical fields and bounded-structure
violations are rejected. Continuation tokens are deliberately ignored here: local
publisher discovery takes one bounded batch, and 4get documents single-use,
short-lived tokens that cannot safely be treated as reusable page URLs.

If a cheap LibreY API returns no links or lacks its supported representation, its
retained HTML candidate moves ahead of unstarted mirrors. This preserves the
server-assisted coverage as the bank grows, spending an ordinary attempt rather
than adding work. Authentication, challenge and explicit server waits do not
trigger a representation retry. Endpoint/transport waits cover the new protocol;
cancelled losers remain absent from failure learning. Redirect wrapper decoding
also requires a real Bing/DuckDuckGo hostname boundary.

The 2026-10-25/26 Android qualification inspected published candidates once per
selected representation and cached the responses outside public source. Only
`4get.canine.tools` supplied usable organic JSON links among the inspected new
engines. Other observations included a JSON failure under HTTP 200, challenge
HTML, authentication/rate/403 refusals and SOCKS failures. No SearXNG route was
activated. A catalog entry or an operator's uptime report is candidate evidence,
not proof of usable content through the reader's transport. One successful read
does not guarantee continued availability or regional coverage.

Protocol references: [4get API](https://git.lolcat.ca/lolcat/4get/src/branch/master/api.txt),
[published instance directory](https://4get.ca/instances), and
[SearXNG formats and requests](https://docs.searxng.org/dev/search_api.html).
This is a native protocol implementation; no external engine, scripting runtime,
model, dependency, permission or service account is bundled.

### Selected index routing (0.38.36)

Regional publisher discovery and field-directed searches share one local index
router. It keeps the selected provider and original-access choice fixed throughout
an operation. Darwin's configured search mirrors use bounded rolling attempts:
a readable later mirror can return while an older read is still pending. Completed
results take priority over an expired hedge timer, and a failed latest attempt can
advance its successor immediately. Source and representation fallbacks share a
three-attempt ceiling per concrete query, with at most three local index readers
active across the repository. A winner cancels redundant pending work. The existing
two initial queries, field-query limits and publisher-read allowances remain.

The router shares SourcePool's bounded endpoint/transport learning and existing
local persistence. A refusal at one mirror does not park the entire Darwin bank.
Explicit waits cover other queries and platform copies using that same endpoint
and Tor transport; an in-flight success cannot clear the wait. Empty query results
remain query-specific. Skipped, cancelled and policy-invalidated work does not
train an outage or renew a deadline. Known pauses are checked before transport
preparation; cached field-search hits can return without that wait. Old coarse
`local-index-rest` entries are no longer consulted.

An explicitly qualified `jsonApi` flag enables LibreY's small public JSON
representation. The native adapter streams bounded numeric-key PHP result objects
or arrays, retaining organic destination/title pairs. It ignores special answers,
upstream labels and snippets as factual evidence. Body size, nesting, row and
field limits apply before accepting results. Login/challenge pages, API errors,
unsafe URLs and search-navigation links do not become publisher material. An
optional login form does not hide already public organic results.

The structured first attempt disables LibreY's own instance fallback, frontend
rewrites and special answers. A retained HTML path can still provide the previous
server-assisted coverage within the same attempt ceiling. A missing/disabled API
can select HTML for six hours in a bounded in-memory format bank; explicit
refusals are not retried through another representation. Only the existing
`librey.private.coffee` instance has the flag. A protected Android observation
returned ten links in 4,698 bytes of decoded response text; a separate HTML read
of the same generic query contained 10,709 bytes. These are dated payload
observations, not wire-byte or controlled phone-latency measurements. Additional
catalog candidates included failed, empty and parked responses and were not added
to the active pool. An upstream list or HTTP 200 alone is not delivery evidence.

All requests still use NetworkGateway and mandatory Tor for regional indexes.
Each lookup captures its network session and connection selection; requests and
redirects stay within the selected source origin. A later policy change cancels
the operation rather than silently adopting another session, provider or direct
connection. Generic index delivery trains endpoint observations; it does not
establish that a particular social post can be retrieved. SourcePool still checks
that requested content before delivery.

A separate native regression exposed a batch that passed the query check and then
waited for the store monitor. It could write after the reader changed searches.
Generation admission now runs inside that monitor and before transaction commit;
revoked packets roll back their facts and publisher hints. The controller also
stops obsolete queue work. Native checks use explicit gates and an observed
monitor wait, protecting saved copies and avoiding a timer-dependent assumption
about when the user action occurred.

Interface references: [LibreY API](https://github.com/Ahwxorg/LibreY/blob/main/api.php),
[request options](https://github.com/Ahwxorg/LibreY/blob/main/misc/search_engine.php)
and [server fallback](https://github.com/Ahwxorg/LibreY/blob/main/engines/librex/fallback.php).
The implementation is native Kotlin and adds no packaged search engine, model,
service account or dependency.

### First-card admission and accumulated caches (0.38.35)

The two ordinary publisher readers retain their existing slots. While no useful
deck has appeared, one additional publisher can be admitted after 750 ms of
waiting. Both admission paths use a semaphore bracket and claim the action only
once: this races admission, not duplicate HTTP reads. Once the first deck exists,
new rescue admissions stop. Pending normal work can still contribute useful data;
one fast result does not discard the other publishers. Cancellation releases both
admission paths and preserves the existing request generation and Gateway policy.

The first result no longer depends on finding a readable publisher among only the
first six queued links. A query can continue through its available candidates
while no deck exists, borrowing unused enrichment capacity within the unchanged
overall source/venue-attempt limit. The same policy handles newly streamed hits
and a combined cached hit list. Once cards exist, the usual eight-initial and
ten-supplemental phase limits apply, with the total limit still authoritative.
The required private transport also starts before cached-record preparation, so
local processing does not postpone connection startup. Saved-only reads stay local.

Business matching prepares immutable name, brand, address, phone, destination and
geographic keys once per record in a merge batch. Candidate comparisons reuse
those keys; branch, suite, house-letter, phone, distance and identity rules retain
their existing decisions. Empty/link-only extractions do not reconcile unrelated
businesses or events or invalidate card contents. Typed factual batches prepare
their relevant record types. Account metadata writes enforce their own cache
bounds; publisher queues enforce their own expiry and size. Batched queue offers
share a transaction. By-ID business reads enforce expiry before physical cleanup.
Ordinary record cleanup and protected saved copies retain their separate paths. This follows the approach of reading/processing only needed
data and batching mutations in [Android's SQLite performance guidance](https://developer.android.com/topic/performance/sqlite-performance-best-practices).

Controlled API 35 diagnostics used the same synthetic records and three samples
per fixture, with frozen before/after APKs. Medians below describe the named local
operations in these debug emulator runs, not full search latency or phone speed.

| Cached businesses / events | Business reconciliation, before → after (ms) | Link-only ingestion, before → after (ms) |
| --- | --- | --- |
| 20 / 40 | 157 → 25 | 251 → 1.39 |
| 300 / 800 | 9,447 → 164 | 11,102 → 0.78 |
| 1,200 / 2,500 | 38,132 → 548 | 42,684 → 0.74 |

The frozen prior predicate and prepared matcher agreed across 32,400 synthetic
business pairs. Native tests hold two publishers open while a third supplies the
first business/event card, and cover later useful publishers in both streamed and
cached batches. Dedicated databases check unchanged unrelated payloads, saved
bytes, expiry, rediscovery and bounds. Physical-phone latency, battery and memory
distributions remain unmeasured; [Android's benchmark guidance](https://developer.android.com/topic/performance/benchmarking/benchmarking-in-ci)
explains why emulator timings do not establish those results.

List cards and opened details distinguish actual owned work (Updating), continued
searches that may find more information (Searching), and no pending work (Ready).
Search complete and Search stopped are distinct. Stable status footprints and fixed
reading viewports avoid moving sheet anchors as content changes. Content scrolling
does not also drag the containing sheet; pinned Close, Back and outside-tap dismiss
it. This shared viewport also applies to menus and the native article reader.

Business chain grouping is a display projection over distinct location records.
A shared declared brand or compatible name plus first-party domain can establish
the group. Directory domains and name similarity alone do not. Selecting a branch
retains its own address, telephone, hours, menu and saved record; grouping does not
rewrite one branch with another's fields.

Event actions clean and deduplicate destination URLs, prioritize registration,
and tuck alternative announcements beneath one primary destination. Opening event
details checks up to six unique links with two workers, a 20-second total allowance,
a 10-second per-link limit and at most 1 MiB/four requests per check. These checks
reuse Darwin's protected article/public-copy resolver and the existing bounded
article bank. Successful checks last 30 minutes; unavailable results last five,
and unverified temporary failures one. These are observations, not perpetual
availability promises. Source inspection can deliberately open an unverified URL;
confirmed unavailable actions stay muted. Action-bearing URLs never gain automatic
retrieval permission. Expanding sources permits another bounded batch.

A matching structured Event with an explicit date can supply a short factual native
read when ordinary article extraction reports empty. Login/challenge pages and soft
404s are not rescued by stale structured metadata. Android parsing stays in the
permissionless document worker with read-only input, bounded output and scratch
cleanup. Event-specific article-bank keys include the occurrence and modification
identity, network session and request purpose. A prepared supplemental read can be
reused by the reader; deliberate/private reads cannot be exported backward into
automatic supplemental acquisition. Evidence rendering folds tracking variants,
field names and repeated excerpts without deleting original provenance records.

## Current routes

| Route | Use | Bound and source distinction |
| --- | --- | --- |
| Overture via the existing Fused route | Physical identities, websites, phones and social accounts | City cell plus two precise-match cells; ordinary results expire. Public example availability is source dependent. |
| Photon / OpenStreetMap | Named/category place discovery | One geographic query, 20 results, 512 KiB. Shares OSM identifiers with Overpass. |
| Overpass | Targeted names/categories and local organizer hubs | Selected radius, 350 results, 2 MiB; unavailable routes rest. |
| Selected public index | Discover publisher pages | Two initial regional queries; two additional field-directed queries, at most six hits each. Uses the selected provider and Tor. At most three source/representation attempts per concrete query and three concurrent local-index readers across the repository. |
| AllThePlaces | Existing first-party-derived brand outputs | Published run HTML and statistics select at most two matching files. Only spiders with 1–6,000 reported features qualify; each decoded file is limited to 8 MiB. No global ZIP or spider execution. |
| Publisher/directory readers | Structured branch facts, contacts and explicit source links | Two ordinary readers and one delayed first-deck rescue slot. Eight initial plus ten supplemental reads normally; first-deck discovery may borrow unused capacity within the total source/venue-attempt cap. Each page is limited to 2 MiB and 18 seconds. |
| Public social routing | Verified business profiles and linked public material | Existing Darwin-derived source pool, alternates and allowed original fallback. Eight account checks, two concurrent, 16 seconds each; an empty frontend is not an account. |

The supplemental stage has a 70-second allowance beginning when the first deck
becomes available, overlapping independent primary discovery.
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
