# Shared video scenes

Version 0.38.56 adds a bounded, visible-card comparison route to the
existing caption and image routes. Recorded
shared scenes can connect different captions without rewriting their originals.
This is neither continuous video crawling nor complete-file authentication.

## Content and preservation

Caption matching cannot connect unrelated captions on the same footage. Preview
pictures can also show different moments or portrait/landscape crops. The new
route compares four requested interior positions at odd eighths of each measured
duration. Missing positions remain missing. Admission requires three distinct,
position-aligned scenes, compatible durations, existing local publisher linkage,
dated roots within six hours, and one declared clip per original. Complete-link
collection prevents a shared intermediate recording from connecting unrelated
families. Existing caption and still-image comparison routes retain their rules.

The independent Kotlin comparer searches bounded crops of existing 64×64 detail
samples using normalized correlation. It loads no model, computer-vision runtime
or new native library. The scene correlation threshold is 0.92; qualification
includes crop/zoom, unrelated and reversed scenes, static covers, missing
positions, duration changes, source association and cancellation controls.

Shared scenes establish neither complete-file equality nor equal audio. The
reader retains every caption, original, discussion root and distinct playable
recording. Existing **Media editions** shares display space while keeping the
recordings selectable. Optional `Media.videoFootage` observations can accompany
an explicit saved copy; import checks their bounded schema and source association.
They are local observations, not cryptographic authentication of a source.

## Scheduling and ordinary cache

The request planner uses existing local publisher/account membership. It offers
at most twelve pairs from the newest 128 eligible groups, with compatible declared
runtimes or an advertised smaller rendition. Since 0.38.57, an extensionless video
can use that same nomination rule; its already-fetched first four KiB must identify
an MP4 or WebM container before decoding. An unknown URL alone still cannot
initiate work. Adaptive playlists, split audio and multi-clip originals remain
outside this sampler. No fresh account
lookup or guessed rendition URL is needed. Playback keeps its existing URL;
sampling can use a smaller rendition explicitly advertised for the same clip.

A readable card must remain still for 1.2 seconds before requesting the single
sampling lane. Scrolling, leaving the foreground, overlays and active playback
cancel the visible-card request. Automatic work respects the existing Wi-Fi-only
video setting and skips power-saving, severe-thermal and low-memory conditions.
Foreground Gateway work takes precedence. Card status shows **Comparing video
editions** while that request is active. One pair has an enclosing eight-MiB,
32-exchange, 45-second ceiling; per-recording limits remain tighter. Retries spend
the same parent allowance. Unsupported acquisition leaves editions separate.

Derived observations expire after seven days, with 128-entry/eight-MiB ordinary
cache limits. Matching reads the in-memory projection; disk restoration runs on
an IO worker. Reopening a cached recording does not refetch its samples. A manual
ordinary-cache purge cancels work and advances its admission generation before
deleting observations. An obsolete callback cannot repopulate the cleared cache.
Protected saves retain their own original records and bounded observations.
Transient failures have a short cooldown and can be retried by explicit refresh.

## Acquisition boundary

The parent uses `NetworkGateway` to supply a read-only virtual recording through
Android's stock proxy-descriptor API. A view permits at most 4 MiB and sixteen
HTTP exchanges, including redirects, within one selected privacy session.
Existing enclosing budgets still apply. Range positions, lengths, total size,
encoding and final destination are checked. A changed connection selection cannot
revive an old view. Unsupported ranges never trigger a full-file fallback.

Strong ETags or appropriately separated Last-Modified/Date values support
conditional ranges. A separately selected observation-only route for a qualified
clip can work without those validators: it pins the final URL and length and
reserves an exchange to re-read the initial block. This detects changed initial
bytes; it does **not** establish that every unsampled byte stayed unchanged.
No reconstructed recording is published as playable or offline content.

Android's retriever could not be obtained by an isolated UID on the tested API 35
image. `PlatformVideoService` therefore uses a dedicated ordinary app process,
accepts only the video-sampling operation and receives a read-only descriptor.
It has the same independent watchdog/termination protocol as the other content
workers, but is **not** a permissionless sandbox. The parent bounds returned
samples and JSON. Its watchdog terminates the app worker; it cannot guarantee
termination of every operation inside Android's separate media services. Ordinary
isolated image/document workers retain their authority.

## Dated observations, 2026-10-28

A private four-platform family reproduced three initial collections. One dotted
caption was being mistaken for a bare domain and removed from prose comparison.
Post presentation, fusion and reference folding now share root-zone-aware link
recognition. Explicit schemes remain explicit links. The bundled IANA vocabulary
requires no runtime request; synthetic controls preserve bare real domains,
reserved fixture domains and punycode domains while retaining ordinary dotted
words as prose.

Both the supplied-sample replay and an automatic replay starting without scene
observations show one card, each distinct caption once, all four originals and
three selectable recordings. Formatting-only dotted/spaced versions share one
verbatim displayed caption; exact originals remain available. The protected save
retains every source edition. Initial and settled native screens were inspected.

In one API 35 emulator replay, cached posts/previews became visible in 679 ms and
the collection formed after live bounded video reads in 6,897 ms. These are single
observations of this mixed cached/live fixture, not cold network-feed timings,
phone measurements, percentiles or a promise for other sources. An initial replay
passed collection/save assertions but failed its screenshot assumption: the feed
correctly retained its visible row anchor rather than adopting the earliest
source's key. The harness now accepts the actual retained original alias.

Separate native range probes acquired four scenes from a smaller advertised
rendition and three from a larger recording within each fixed allowance. Their
scene comparison passes and a reversed-sequence control fails. Nearest-keyframe
shortcuts failed this case and were not adopted. These are API 35 emulator
observations, not physical-phone performance or general provider availability.
Another reported platform edition has not yet been acquired and is not counted.
A separately created API 26 emulator also returned four scenes through the real
proxy-descriptor/Gateway path, using 1,835,008 bytes and seven range requests in
6,330 ms. Its six ordinary-cache controls passed. An initial fixture-staging permission error was corrected before that run.
No product root permission is required.

The earlier foundation checkpoint passed 2,511 JVM checks with four optional
skips and 131 native feed/Library checks with one opt-in skip. Automatic-cache
controls separately passed six native cases covering reuse, restart, expiry,
corruption, purge races, clip association, transient recovery and playback.
The final selection is recorded in [release qualification](RELEASE_QUALIFICATION.md).
Initial failing probes and corrected runs remain in the private external evidence
area. This does not claim that all video duplicates are solved.

### Follow-up qualification, 0.38.57

The previously unacquired fifth edition was recovered through an existing
TikTok RSS route. Its supplied video URL had neither a file extension nor a
declared container/runtime. Bounded header recognition now lets that recording
participate when the existing pair has sufficient runtime/rendition evidence.
The sampler reads the prefix from its existing range block, adding no exchange.
It retains the same per-recording and enclosing pair allowances. Header hints
select a decoder; they do not authenticate the recording or establish a video
track. The worker still validates the actual media.

Two public TikTok JSON representations also supplied clip duration in seconds,
which the parser previously omitted. Those fields now survive normalization;
music duration and photo slides remain separate. The existing creator embed and
RSS request builders canonicalize TikTok handle casing without changing stored
targets, profile ownership checks, source inventory or retry/refusal policy.
One observed lowercase RSS request returned ten dated posts, while a creator
embed still returned HTTP 429. Challenged feed-API responses remain failures;
no new provider or quota-cycling mechanism was activated.

An automatic five-edition replay starts without video observations, uses real
Gateway range reads, and yields one native card with all five originals, three
distinct source captions and four selectable playable editions. Saving retains
all originals. Initial and caption screens were inspected. One API 35 emulator
observation was 660 ms to the first cached card and 14,867 ms to the settled
collection, including a background request yielding to foreground traffic.
Those are single mixed cached/live measurements, not physical-phone or cold-feed
performance claims.

Early replay attempts exposed two harness errors: requiring observations for an
already caption-matched copy, and counting the nested unread-status label as
another card. They are retained as failed instrumentation attempts. The corrected
test checks nominated comparison representatives and actual original-key rows;
its existing content, attachment and protected-save assertions remain required.

## Primary references

- [Normalized template correlation](https://docs.opencv.org/4.13.0/de/da9/tutorial_template_matching.html)
  and [Lewis, Fast Normalized Cross-Correlation](https://scribblethink.org/Work/nvisionInterface/nip.html): mathematical references; no implementation dependency imported.
- [Android proxy descriptors](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler))
  and [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever).
- [HTTP validator semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.2.2).
- [IANA root-zone vocabulary](https://data.iana.org/TLD/tlds-alpha-by-domain.txt),
  version 2026102800, compiled into `core/src/main/resources/iana-tlds.txt`.
- [WebM container structure](https://www.webmproject.org/docs/container/) and
  [ISO BMFF file-type boxes](https://www.loc.gov/preservation/digital/formats/fdd/fdd000155.shtml):
  references for the independent bounded Kotlin header reader.
- [TikWM response fields](https://github.com/mehanon/tikwm/blob/master/tt/types.go)
  and [TikTok representation parsing](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/tiktok.py):
  references checked against actual responses; no runtime dependency imported.
