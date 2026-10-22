# YouTube archive recovery

anti social uses an independent native implementation of the discovery ideas in
[TheTechRobo's YouTube Video Finder](https://findyoutubevideo.thetechrobo.ca/).
The donor browser system remains Darwin. Recovery runs only after a YouTube
load or playback failure; it does not poll archives for every feed item.

`YouTubeRecovery.recover(videoId, rejectedUrls, muxedOnly)` returns the same YouTube ID's
recording, its actual archive source, available public attribution and an
optional capture date. It never searches by title or silently substitutes a
different upload. `asPost(current)` preserves a current post's known caption
and attribution while replacing failed media. A timestamp is shown as an
archive capture only when the source actually supplies one.

The recovery ladder checks these public read paths, stopping on the first
recording that passes a bounded media-prefix check:

1. Wayback's `__wb/videoinfo?vtype=youtube&vid=ID` format index. Timestamped
   `id_` replays keep video and, where necessary, separate audio inside Wayback.
   Known separate tracks require both an audio and video container prefix.
2. Wayback's legacy `web/0id_/http://wayback-fakeurl.archive.org/yt/ID` recording
   endpoint. Redirects, reachable pages and HTML error responses are not proof
   that the recording works.
3. The exact Ghostarchive `varchive/ID` page, reading its native video source,
   caption and public attribution without loading its JavaScript or analytics.
4. PreserveTube's public `video/ID` record. The record must name the exact ID
   and expose a source. Disabled, pending deletion, soft deletion, cold storage
   and deleted records are not treated as playable. anti social does not submit an
   archive job or request a cold-storage restoration.
5. Internet Archive metadata for exact item conventions `youtube-ID`,
   `youtube_ID` and `ID`. Files must contain the exact ID, or be the only video
   in an item that declares the exact original YouTube URL. Metadata, thumbnails,
   dark items and collections alone do not count as a recovered video.
6. Exact Hobune `videos/ID` and `tpa-h/videos/ID` pages with an exposed same-ID
   native video source.

Each attempt has a shared maximum of eight HTTP exchanges, 1 MiB of discovery
data, 256 KiB per document, 4 KiB per media prefix, and a 22 second direct / 40
second Tor wall-clock limit. Redirects and media probes share the gateway's
budget. The ladder may stop before its last providers when those limits are
used. All requests and actual playback go through `NetworkGateway`; Tor fails
closed. A maximum of 64 in-memory results expire. Successes last six hours;
repeated misses back off from five minutes to six hours. A network policy
change partitions the cache. Failed playback URLs are excluded on a subsequent
attempt. Offline callers use `muxedOnly=true` to continue past separate tracks
and the legacy endpoint's unknown audio layout. `clear()` clears this ordinary
lookup cache. Ordinary cache entries do not create saved records or downloads.

The upstream finder includes other useful leads that are not automatically
playable recovery: Filmot's metadata, Distributed YouTube Archive's manual
Discord retrieval, protected RemovedEDM access, and title-based/Odysee searches.
Those must not be advertised as an exact, verified native replacement. Finding
a prefix proves that media is exposed at that instant; it does not guarantee
that an entire file can be decoded, that its later ranges remain available,
or that every removed video exists in an archive.

## Research and provenance

The algorithms were written for anti social. No upstream application code is bundled
or translated. Research references:

- [YouTube Video Finder source](https://github.com/TheTechRobo/youtubevideofinder/blob/3b64b9788f88c4e3d4c06931cb7c658f95d060d4/findyoutubevideo/finder.py),
  copyright 2022–2026 TheTechRobo,
  [Apache-2.0 license](https://github.com/TheTechRobo/youtubevideofinder/blob/3b64b9788f88c4e3d4c06931cb7c658f95d060d4/LICENCE).
- [PreserveTube's public video endpoint](https://github.com/PreserveTube/backend/blob/a7e6a0967633bddf8d2f862a3ec72001f2826f0a/src/router/video.ts)
  and [watch template](https://github.com/PreserveTube/backend/blob/a7e6a0967633bddf8d2f862a3ec72001f2826f0a/src/templates/watch.eta),
  researched as protocol documentation; its application is AGPL-3.0 and is not
  embedded in anti social. The endpoint requests an identifying User-Agent; the
  gateway identifies anti social as a public content reader.

Live research on 2026-10-18 used one public sample ID, `dQw4w9WgXcQ`:
Wayback returned its format index with separate WebM video and Opus audio;
Ghostarchive returned a public page with a direct MP4 and attribution;
the exact Internet Archive item returned metadata and thumbnails but no video,
which anti social correctly must not count as recovery. PreserveTube returned HTTP
403 and Hobune returned HTTP 404 for that sample. These observations are not
universal availability claims. Evidence and small source checkouts are under
`LiberBuild/evidence/youtube-and-post-details-2026-10-18/recovery` on external
storage. Unit fixtures cover missing and misleading content, exact identity,
split audio, cache backoff, cancellation, failed-route exclusions and native
post construction. Full playback and device verification are recorded in the
release validation report.

On the same date, an explicit API 35 Android test also played the Wayback sample
through the real native player. It rendered VP9 video with Opus audio from the
paired archive tracks, advanced past one second, retained the initially verified
recording and observed only `web.archive.org` gateway hosts. This is first-frame
and selected-track verification, not an end-to-end check of the entire recording.
See `native-verified/youtube-live` in the release evidence directory and
[the validation report](../VALIDATION.md).
