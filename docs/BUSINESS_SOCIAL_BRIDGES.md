# Business and social information

Business cards arrive from the local index, regional place data and public
publisher pages. Supplemental account and menu work runs after those first cards
are available. It shares the existing per-search traffic limits and worker slots,
yields to foreground reading and stops when the search is cancelled. Card activity
indicates outstanding work; an idle card is not an invitation to wait indefinitely.

## Retained connections

Overture's `socials` fields already enter the local record. OpenStreetMap and
AllThePlaces now share explicit platform-key normalization: contact-prefixed and
bare keys, numeric Facebook page IDs, supported handles and offered profile URLs.
A social URL stored in a generic website field is retained as an account. Share
buttons, individual posts and generic site roots cannot become account links.
This is deterministic parsing, not a username search or ownership model.

The GrowthZone/MemberZone `gz-search-details` LocalBusiness contact block supplies
member social links, published hours and visiting directions. The typed name and
street must agree with the business projection. Surrounding chamber navigation,
footer accounts, maps, sharing controls and other members are outside that scope.
Ordinary JSON-LD/microdata `sameAs` and first-party website extraction remain in
place. These declarations are read before inert Markdown washing; downloaded page
scripts and embedded frames are never executed.

## Returning useful data

Business searches now schedule bounded social-detail reads, rather than only
checking link availability. The production source pool and Darwin routes still
select the usable public representation. An empty/login response is a failure.
Successful reads also satisfy account availability so a second validation need
not fetch the same profile again during that search. The remaining account-check
slots retain the useful profile data from their existing read too; they do not
start another account-discovery crawl.

An attached profile may contribute published contact details, explicit hours,
offered menu links and cross-platform account candidates. Branch details require
identity and location/contact corroboration. A shared chain account cannot supply
branch-specific hours, phone numbers or menus. Menu routes enter the existing
branch-aware menu reader, including its PDF and ordering-provider handling.
Outgoing social links remain explicitly unmatched candidates until their own
profile agrees on business identity and branch contact/location evidence. A
matching username or a linked person is insufficient. Source URLs and observation
times remain on the resulting fields. Missing facts
remain missing; this does not extract arbitrary claims from social prose.

Successful cached source projections are requeued once after the upgrade. Availability and business-profile projections have separate cache receipts. Old
positive checks keep their visible links while the new profile facts load once;
failed checks retain their backoffs. Failure backoffs and saved snapshots are preserved. Identical repeated postal addresses are reconciled before typed extraction;
different addresses are left unresolved. Ordinary records and lookup caches
continue to expire under the existing storage limits.

## Source qualification

The primary specifications confirm these are place associations, not independent
votes of ownership. In particular, Overture place confidence describes existence.
Its source lineage remains attached; duplicates from overlapping upstream data
are not promoted into independent corroboration.

- [Overture place schema](https://docs.overturemaps.org/schema/reference/places/place/)
- [AllThePlaces](https://alltheplaces.xyz/)
- [OpenStreetMap contact tags](https://wiki.openstreetmap.org/wiki/Key:contact:*)
- [Observed MemberZone contact page](https://business.indianriverchamber.com/list/member/coffee-house-1420-5222)

Foursquare's current OS Places delivery requires a provider portal/token. This
release does not add an unconfigured global download, pretend its legacy S3 route
works, or require readers to register with Foursquare. Its upstream data can still
arrive through existing Overture associations. No paid enrichment provider, new
backend, local model, browser runtime or AI call is required for these bridges.

Validation and live-source limits are recorded in RELEASE_QUALIFICATION.md.
