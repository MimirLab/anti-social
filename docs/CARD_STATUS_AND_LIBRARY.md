# Card activity and saved discoveries — 0.36.0-rc6

Cards show a small, fixed-size status: **Updating…** while an owned job is
running or queued, and **Ready** when none is outstanding. Tapping it explains
the current work and makes clear that the reader can keep browsing. Ready means
no current background update; it does not certify exhaustive source coverage.

Status follows existing work. It starts no probes, downloads, model calls or
polling timers. Its in-memory leases are released on completion, failure and
cancellation. Overlapping jobs, replacement searches and source editions have
independent leases, so an older completion cannot clear a newer update. The
tracker retains only outstanding jobs and does not add another history cache.

The social-card indicator covers image loading and metadata inspection, public
account-name resolution, archive checks, source records, Context and cross-post
matching. A collected card observes all its admitted source editions and media.
Business/event indicators use their associated publisher pages, owned business
reads, social-account validation, and restaurant-menu work. An unassigned search
hit cannot claim to be enriching an unrelated card. Search's overall activity
continues to represent discovery whose owner is not yet known.

A route that failed or was stopped releases its activity. Existing source
availability messages and cached content remain; Ready does not turn that
failure into working content. NetworkGateway, Tor selection/failure behavior,
route budgets and acquisition priority are unchanged.

## Library

Library's All view includes saved businesses and events alongside its existing
post copies/history. Businesses and Events have visible filters and participate
in local Library search. Results use saved time; event occurrences are retained
even after their date passes. Search's former Saved switch and the collection
menu's redirect back to Search are removed.

No storage migration or re-download is required: the existing protected discovery
copies are read directly, independently of live directory records, current area,
route-health caches and ordinary expiry. Opening one never starts discovery,
account validation or an automatic menu refresh. Captured text, contacts, menus,
evidence and source links remain available. External pages and linked images
remain explicit reading actions; this change does not download every linked
attachment. Post collections and retained-history controls keep their existing
behavior; post collections do not filter the business/event categories.

Saved events use their declared timezone. Older records without one can use a
known nearby area; an otherwise unqualified instant is explicitly labeled UTC.
No location service or network geocoding is performed for Library display.

## Qualification

Core tests exercise overlapping ownership, unrelated-card isolation, idempotent
completion, failure, cancellation and replacement jobs. Native journeys use a
blocked public-details fixture, cancellation, merged post editions, and immutable
saved business/event copies opened through Library with a discovery backend that
rejects every request. Actual native screens and feed performance are checked
before preview promotion. External source availability and physical-phone
performance remain separate from these local fixture checks.

Recorded qualification: 1,781 core cases, zero failures, one optional native
embedding dependency skip; 252 selected Android cases, 251 passed and one
explicit live-search/Tor opt-in skipped. The attribution layout passed 77 native
rechecks, and final queued-route ownership passed 47 further native rechecks.
Release lint, debug/release assembly, and actual native screen inspection passed.

The existing 240-post/120-group emulator stress fixture completed successfully:
7.94 s until the first merged card, 4.75 s for the eight-swipe sequence. Its window
frame median was 147 ms. This is a performance sanity check, not phone FPS or a
new speedup claim: the attribution layout changes card heights, so the same
swipes travel through a slightly different visible range. Remaining emulator
frame stalls and physical-phone qualification remain explicitly separate.
