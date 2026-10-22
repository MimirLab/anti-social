# Local opportunities

This extends the existing Businesses and Events search. There is no additional
feed, model requirement, background crawler, national directory sweep or settings
panel. Ordinary cards still arrive independently and enrich in place.

## Where routes belong

The small built-in publisher catalog carries geographic coverage and topic gates.
A coverage box must intersect the selected search circle and agree with its known
country before a seed enters the queue. A shared city name cannot authorize a
route in another country. Brevard family calendars and Extension classes are not
searched from Melbourne, Australia; Helsinki's municipal API is not called from
Florida. Known out-of-region sibling sites are rejected before retrieval too.
Publisher coverage is only permission to check its calendar. It never supplies
the location of an occurrence.
Explicitly distant ordinary publisher records stop follow-up discovery. Known
regional hubs can still offer a county-wide calendar. Login/edit pages and known
family-calendar views outside the requested dates never spend a page read.

There are still two initial web-index queries. The second can target useful local
publishers—libraries and parks for families, community workshops for repair,
science and astronomy organizations, gardening Extension programs or volunteer
organizers—while retaining the selected area's full label. Business queries keep
their current plans. Event searches no longer spend supplemental queries looking
for business contact details. At most two relevant regional seeds share the
existing page queue, readers, cancellation and total request allowance.

## Sources added

| Source | Admission and useful information |
| --- | --- |
| LiveWhale | Recognize a published JSON events link or installed generator. Preserve its department/tag scope, request at most 50 records and the selected dates, and ask for only needed fields. V1 arrays and V2 data/results envelopes supply real starts/ends, venues/coordinates, descriptions, costs, cancellation and modification times. No global instance search or automatic page sweep. |
| SpaceAPI | Follow an explicitly published SpaceAPI link, or recognize a returned SpaceAPI document. Require an actual local location before retaining the space or its offered calendar. Use public organizational contacts, declared facilities and arrival instructions. Access/sensor logs are not public activities; a transient closed flag is not a permanent business closure. No fetching every endpoint in the worldwide directory. |
| Helsinki Linked Events | Only within the configured Helsinki metropolitan coverage. One server-side bounding-box/date-filtered page, 50 records, with locations included; exact distance is checked locally. Prefer the supplied English fields, retaining another supplied language when English is absent. No remote JSON-LD contexts or pagination links execute. |
| Fun 4 Space Coast Kids / compatible public family-calendar fields | Use the actual calendar entries in the selected date range, then their explicit When, Location, Cost and description fields. Registration restrictions and resident/nonresident prices remain readable. Listing text alone is not a dated event. Sister-city navigation is not a collection graph. |
| Brevard UF/IFAS Extension | A geographically gated publisher lead for relevant classes. Follow the county page's actual published class/tenant link. It does not make statewide classes local or turn an office address into an event venue. |
| Offered calendar exports | Recognize webcal HTTPS subscriptions, advertised My Calendar iCal, JEvents exports and configured Timely exports, alongside existing ICS/RSS readers. TidyHQ, Modern Campus, Indico and other publishers can use their offered feeds; this is not a claim that every instance exposes a usable export. Disabled APIs are not guessed or probed. |

## Locality, freshness and storage

An occurrence uses its venue, not its organizer's headquarters. Coordinates must
fall within the selected radius. Explicit conflicting country or US-state evidence
overrides a same-named town. The older text-only fallback remains weaker than a
coordinate: it requires an actual city component/suffix, rather than a street
containing that city's name. Missing location data stays unknown.

For an uncertain nearby-town occurrence, first reuse an unambiguous matching
numbered street and town from the existing place graph. Otherwise allow at most
two venue lookups per search, charged to the shared request cap. Queries carry
the explicit address and geographic bounds; acceptance requires the same numbered
street, stated town, compatible postcode/country and coordinates inside the
circle. A nearby wrong house number or ambiguous address is rejected. Matched
coordinates are ordinary cached observations for seven days; misses rest for
30 minutes and failures for 15. These lookups use Tor without a direct fallback.
They do not run on every event or re-geocode already admitted town-only cards.

New distant or online events do not fill the local graph. An already known
occurrence can still accept an explicit move or cancellation that puts it outside
the selected area; its protected saved copy stays intact. Separate dates,
performances and venues continue to use the existing occurrence reconciler.

Event-source stability starts at 30 minutes and backs off only to six hours.
Changes to venue, address, price, dates, registration, status or useful text reset
that stability. These are rests between requested reads, not periodic pings.
Explicit refresh can retry sooner. Other business/menu rests remain unchanged.
Ordinary data keeps the existing record, byte and expiry limits; saved copies
remain protected. No additional model, native library or bulk dataset is bundled.
Database upgrade 7 requeues formerly successful pages once for the improved
projection; failure rests and protected saved snapshots survive.

All collection uses NetworkGateway, including redirects, filters and fail-closed
Tor. Discovery queries that carry geography use Tor; ordinary publisher reads use
the chosen content connection. HTML is still washed into inert Markdown in
memory, with explicit fields extracted before removing the husk. JSON and ICS are
data. No page scripts, widgets, remote contexts, external images, calendars' TZURL
files or form submissions execute during extraction. No temporary HTML/Markdown
files are introduced.

## Deliberate scope

The supplied research is a source map, not a requirement to collect every item.
Auctions, permit/change monitoring, road disruptions, products and equipment
inventories would need distinct result semantics and refresh rules. Benefit
eligibility, current stock and future opening status cannot be inferred from an
ordinary place listing. They are not new result types in this release.

Specialist organizations, museums, clubs, food producers and volunteer groups
can enter through geographically scoped discovery, their public pages and offered
calendars. Dedicated adapters for IEEE named feeds, Mobilize organizations,
Science Near Me, arts directories and additional countries remain candidates;
they are not represented as implemented API coverage. A source earns further
work when it adds useful local records within the traffic budget.

## Primary references

- [LiveWhale public API and reading controls](https://support.livewhale.com/calendar-onboarding/widgets-and-api/)
- [LiveWhale JSON versions and requested fields](https://support.livewhale.com/live/blurbs/json-api)
- [SpaceAPI schema](https://www.spaceapi.io/docs/)
- [Helsinki Linked Events source and protocol](https://github.com/City-of-Helsinki/linkedevents)
- [Space Coast family calendar](https://fun4spacecoastkids.com/calendar/)
- [UF/IFAS Brevard county publisher](https://sfyl.ifas.ufl.edu/brevard/)
- [My Calendar export contract](https://docs.joedolson.com/my-calendar/my-calendar-api/)
- [Modern Campus offered feeds](https://support.moderncampus.com/cms/technical-reference/calendar/feeds.html)
- [TidyHQ public calendar subscriptions](https://support.tidyhq.com/en/articles/77213-calendar-feed-for-google-calendar-apple-calendar-etc)
- [Indico HTTP exports](https://docs.getindico.io/en/stable/http-api/)
