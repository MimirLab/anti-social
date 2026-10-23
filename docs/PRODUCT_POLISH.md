# Subscription, search and collection repairs — 0.38.0

## Local subscription actions

The account page retains **Subscribe locally** when a public route omits optional
profile metadata, including after unsubscribing and returning. The known canonical
account target supplies the local action; actual supplied profile metadata still
supplies the presentation. Unsupported targets do not become subscribable.

**Subscribe all** saves the readable accounts currently shown in the review.
Later discoveries are not silently added. **Unsubscribe all** removes the selected
bundle's follows and feed memberships in one transaction. Saved records and other
bundles remain intact. Removing a bundle's final account also closes stale management
state, allowing another subscription immediately.

## Search routing

Instagram's selected public index now queries post and Reel URLs alongside its
broad query. TikTok's index queries cover videos, photo posts and the broad query.
The small concurrent lanes remain with the selected provider; their continuations
are bound to the source and exact query. A source's account-only response remains
available for the people shelf while post-capable routes continue.

TikWM's public keyword and photo-search surfaces have separate route identities,
health and query-bound continuations. Actual returned posts become native cards;
search snippets never become posts. These are source-selected samples, locally
ordered by publication time, not a claim of exhaustive platform search.

The October 23 API35 Gateway checks returned four Instagram posts and fourteen
accounts through the default DuckDuckGo path, and twelve TikTok posts through
TikWM photo search in about two seconds. The TikWM video-search route returned
403 on that run. Bing returned off-platform results despite the requested site
restriction; they were rejected. A Kittygram instance explicitly reported its
previously-seen-account restriction. Reddit returned twenty-five posts in an
earlier run, but a later connection returned an unavailable search. Bluesky
provided nineteen accounts while its post routes were unavailable. These are
observations, not permanent availability guarantees. Selected Tor still fails
closed; no provider switch or reader login was introduced.

## Caption and image collection

The captured NASA “That's our jam” case had three editions. One spelled out
“our Chandra X-ray Observatory,” while another used `@ChandraXray`. A narrow
lexical alignment now recognizes one such name-span substitution within a long,
otherwise identical caption from linked publishers close in time. Surrounding
punctuation and destinations must agree. This is not a general shared-handle
ownership inference. The fullest readable spelling supplies the fused caption;
every original and discussion root remains available.

The White House “Saving American lives” case had four editions. Facebook's numeric
and pfbid URLs were separate records from the same subscribed feed, and Instagram
had a short generated heading repeating the caption. Exact feed membership,
publication timestamp and distinctive complete caption now establish the Facebook
repeat cohort. Short generated excerpt headings and HTML entities align consistently
between matching and fusion. The four records become one collected card.

Already-cached image comparison no longer waits for unrelated feed-network
timeouts. It shares the bounded decoder pool and yields between files. Detailed
dark photographs have a tightly limited admission path: luminance, contrast,
color diversity, dominant-area and spatial-detail checks all apply. This repairs
NASA's mostly-black astronomy picture without admitting blank placeholders and
simple logos. Fine image comparison still preserves changed chart regions, crops
and different video frames. No image-identity threshold was relaxed.

## Event occurrences

The public “Halloween in the District” listings named both Eau Gallie Arts District
and the participating Rossetter House Museum, at different addresses on Highland
Avenue. Exact distinctive title, locality, street corridor, occurrence date, end
time and corroborating activity details now permit that district-wide celebration
to reconcile. A start-time difference is limited to thirty minutes; distant places,
different end times and separately named tours remain separate. Existing freshness
selection and field-level evidence survive reconciliation.

## Qualification and fixture provenance

- Full JVM suite: 1,837 cases, zero failures/errors, one optional weight-dependent skip.
- API35 native regression: 78 passed, two opt-in checks skipped. Includes repeated
  subscription cycles, gallery identity, cross-post feeds, reading state, search
  streaming/scopes and local discovery. Actual native screens were inspected.
- Release APK/test APK assembly and release lint passed. Existing lint warnings
  remain; physical-phone performance and battery behavior are not certified.
- An additional opt-in native feed benchmark passed: 240 supplied posts formed
  120 groups in 2.48–3.08 seconds; first feed content appeared in 3.83 seconds.
  Unchanged database notifications settled in 21.6 ms. Emulator frame p95 was
  133.6 ms, so this is a recorded workload, not a claim of physical-phone smoothness.

The regression fixtures are curated public records, not a reader-state export:
`core/src/test/resources/product-polish-posts.json` and `product-polish-events.json`.
They retain the public source URLs. The NASA test photograph
`app/src/androidTest/assets/nasa-thats-our-jam.jpg` was obtained from the public
[NASA post](https://x.com/NASA/status/2113358139999265160), also published on NASA's
Facebook account. Event fixtures come from
[Visit Space Coast](https://www.visitspacecoast.com/event/halloween-in-the-district/)
and the linked
[Eventbrite listing](https://www.eventbrite.com/e/halloween-in-the-district-2026-tickets-2002184609063).
Original publisher attribution remains in the records. Test images do not ship
inside the reader APK. Private databases, signed-media acquisition URLs, credentials
and preview backups remain outside the source tree and release artifacts.
