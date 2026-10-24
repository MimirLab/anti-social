# Instagram public preview route, 0.38.9

The routing pool now includes an independently implemented Anonimogram JSON
reader. It adds public profile details and on-demand caption/image previews;
it does not provide global Instagram post search or subscription freshness.
The existing Darwin-derived alternatives retain their roles.

## What was observed

On October 24, 2026, anonymous reads through NetworkGateway returned a NASA
profile and twelve public captions. Profile fields included an explicit display
name, biography, avatar and follower/following counts. The stable account ID was
absent and remains unknown. Every returned post lacked a publication timestamp.
Five entries described as videos supplied JPEG posters, and galleries supplied
only one preview image. The reader preserves those limitations rather than
inventing dates, playable videos or complete galleries.

The source's [public browser client](https://anonimogram.com/) identifies separate
`/api/profile/{username}` and `/api/posts/{username}` routes. The app issues only
the requested operation, through its gateway, without executing the website's
JavaScript. [Anonimogram's description](https://anonimogram.com/about) states
that anonymous lookups have a limited free allowance. The app supplies no login
token, cookie or rotating request identity; normal gateway failure/backoff applies.

| Operation | Integration |
| --- | --- |
| Profile details and display names | Exact selected username checked; explicit names can seed the existing local name bank. Missing IDs and fields remain unknown. |
| Manual profile browsing | Captions and partial image previews, with authentic Instagram post/reel links and acquisition provenance. |
| Pagination | Source/account-bound opaque continuation when actually supplied; repeated or malformed cursors stop. The live sample supplied no continuation, so live pagination remains unverified. |
| Feed refresh | Unsupported. Undated previews cannot move a subscription's successful-refresh boundary. |
| Global search, exact-post reads, full media, stories, highlights and comments | No new capability claimed or registered. |

Responses are capped at 1 MiB with strict, depth-bounded JSON parsing, bounded
row/media counts, HTTPS media URLs and exact requested-profile response checks.
The shared API hostname is not rewritten into an outward Instagram link.
Cryptographic ownership and independent corroboration are not inferred from
profile-path attribution.

## Other leads checked

The [socialfetch protocol reference](https://github.com/EdgeAIG/socialfetch)
suggested Instagram's mobile username-feed route with a public application ID.
Our protected anonymous request remained login-gated; it was not promoted as a
working route. [InstaSearchEngine](https://www.instasearchengine.com/) describes
a Google public-index interface, rather than an independent Instagram corpus;
no new production search adapter was established from it.

TikWM's existing photo-search adapter returned three dated records in one
approximately 2.1-second protected Gateway request. Its video-search endpoint
returned HTTP 403 on the same connection. The adapters already maintain distinct
route health and query-bound continuations; this audit does not add a duplicate
adapter or claim complete TikTok search coverage. These are dated observations,
not provider-wide uptime or phone-performance measurements.

Validation and the separate NASA duplicate requalification are recorded in
[release qualification](RELEASE_QUALIFICATION.md). Raw responses and private
device fixtures stay on the external build volume, outside the public source
and APK. No new model, browser runtime or background scanning loop is added.
