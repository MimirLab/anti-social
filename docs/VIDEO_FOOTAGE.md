# Shared video scenes: development checkpoint

This work is under qualification after 0.38.55. The live release does not yet
automatically sample videos. The candidate can group already-sampled editions;
automatic candidate scheduling, ordinary-cache reuse and their cancellation
controls remain to be connected and qualified before publication.

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
or new native library. The experimental scene threshold is 0.92; qualification
includes crop/zoom, unrelated and reversed scenes, static covers, missing
positions, duration changes, source association and cancellation controls.

Shared scenes establish neither complete-file equality nor equal audio. The
reader retains every caption, original, discussion root and distinct playable
recording. Existing **Media editions** shares display space while keeping the
recordings selectable. Optional `Media.videoFootage` observations can accompany
an explicit saved copy; import checks their bounded schema and source association.
They are local observations, not cryptographic authentication of a source.

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
samples and JSON. Ordinary isolated image/document workers retain their authority.

## Dated observations, 2026-10-28

A private four-platform family reproduced three initial collections. One dotted
caption was being mistaken for a bare domain and removed from prose comparison.
Post presentation, fusion and reference folding now share root-zone-aware link
recognition. Explicit schemes remain explicit links. The bundled IANA vocabulary
requires no runtime request; synthetic controls preserve bare real domains,
reserved fixture domains and punycode domains while retaining ordinary dotted
words as prose.

The native supplied-sample replay now shows one card, the common caption once,
all four originals and three selectable recordings. The protected save retains
every source edition. Screens and caption presentation were inspected.

Separate native range probes acquired four scenes from a smaller advertised
rendition and three from a larger recording within each fixed allowance. Their
scene comparison passes and a reversed-sequence control fails. Nearest-keyframe
shortcuts failed this case and were not adopted. These are API 35 emulator
observations, not physical-phone performance or general provider availability.
Another reported platform edition has not yet been acquired and is not counted.

The foundation checkpoint passes 2,511 JVM checks with four optional skips and
131 native feed/Library checks with one opt-in skip. Initial failing probes and
their corrected runs remain in the private external evidence area. This is not
release qualification of an automatic sampler or a claim that all video
duplicates are solved.

## Primary references

- [Normalized template correlation](https://docs.opencv.org/4.13.0/de/da9/tutorial_template_matching.html)
  and [Lewis, Fast Normalized Cross-Correlation](https://scribblethink.org/Work/nvisionInterface/nip.html): mathematical references; no implementation dependency imported.
- [Android proxy descriptors](https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,%20android.os.ProxyFileDescriptorCallback,%20android.os.Handler))
  and [MediaMetadataRetriever](https://developer.android.com/reference/android/media/MediaMetadataRetriever).
- [HTTP validator semantics](https://www.rfc-editor.org/rfc/rfc9110.html#section-8.8.2.2).
- [IANA root-zone vocabulary](https://data.iana.org/TLD/tlds-alpha-by-domain.txt),
  version 2026102800, compiled into `core/src/main/resources/iana-tlds.txt`.
