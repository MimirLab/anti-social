# Feed performance qualification — 0.36.0-rc5

This change removes repeated local work from feed opening and scrolling. It adds
no network requests, language-model calls, or new reader controls. Darwin routing,
progressive account discovery, late image/source enrichment, privacy protection,
and the existing matching thresholds remain in place.

## Work moved off the reading path

- One combined-feed Room snapshot supplies both the reader and its cross-platform
  counterparts. Scoped feeds still retain their own window and source memberships.
- Access-time updates are compared before JSON decoding. Reading dwell no longer
  reparses and republishes an unchanged thousand-record window. Payload changes
  and membership-only changes still reach the reader.
- Cross-post grouping reuses pure caption preparation, comparison fragments and
  presentation results within one operation. The workspace has an estimated
  shared 8 MiB budget and a 2,048-entry limit per map; it is discarded in a
  `finally` block. It does not retain another browsing-history cache.
- Original-post identity is computed once per immutable record. Its transient
  field does not change saved/exported JSON, equality, or copied-post identity.
  URL patterns are compiled once rather than repeatedly during card rendering.
- A newer source/image snapshot cancels obsolete grouping at cooperative
  checkpoints. Readers receive a completely checked collection, never partial
  merge results.
- Source and fused-caption change detection runs in the background when source
  material changes. Viewport movement only controls when those changes may be
  adopted; the visible card and reading anchor stay frozen.
- Saved-copy verification starts after the reader chooses Save, on a background
  dispatcher. The open sheet retains that captured post, replies and completed
  proof even when another source arrives. Stable link lists,
  filter decisions and read-key sets are reused across scroll recompositions.

## Reproducible workload and limits

`FeedPerformanceTest` is opt-in and requires both
`antisocialIsolatedFixtureDevice=true` and `antisocialFeedBenchmark=true`. Use a
throwaway test installation, never a personal reader. It supplies 240 authored,
long-caption X/Facebook posts with no media requests. Each pair is one bulletin;
changed bulletin numbers must remain separate. Both versions must produce 120
cards with the same originals. The test opens the native combined feed, performs
eight identical touch swipes, records Android window frame metrics, and captures
the actual screen. It restores preferences and removes its own fixture rows.

After installing the debug app and Android test APK on an isolated emulator:

```sh
adb shell am instrument -w -r \
  -e antisocialIsolatedFixtureDevice true \
  -e antisocialFeedBenchmark true \
  -e antisocialBenchmarkLabel measurement \
  -e class app.antisocial.android.FeedPerformanceTest \
  app.antisocial.android.test/androidx.test.runner.AndroidJUnitRunner
```

JSON and PNG evidence is written under the app's external `files/validation/`
directory, in `feed-performance`. This test is excluded from ordinary runs by
its two opt-in flags.

The native comparison uses the original `1776e30` debug APK and the optimized
debug APK on the same API 35 x86_64 emulator (KVM, four cores, 3 GiB,
SwiftShader). Timed runs are serialized without a concurrent build. These are
stress-fixture wall times, not expected ordinary-feed times or phone FPS. The
workload deliberately puts many nearly identical long captions through the
changed-number safeguards. Cold emulator/JIT and host scheduling affect results.

| Native stress-fixture measurement | Original | Optimized |
| --- | ---: | ---: |
| Core grouping, median of three | 73.33 s | 7.10 s |
| Open until first merged card is visible | 78.17 s | 8.52 s |
| Eight-swipe sequence, including gesture/test synchronization | 29.26 s | 5.10 s |
| Sampled window frame duration, median | 994 ms | 151 ms |
| Sampled window frame duration, 95th percentile | 2,483 ms | 367 ms |

Both complete benchmark runs passed, including fixture cleanup. The remaining
frame times still show stalls on this software-rendered emulator; these gains
are not a claim of a consistently smooth frame rate. The access-notification
wall-time probe is not an end-to-end decode measurement and is not used as a
speed claim. `FeedWindowTest` directly verifies its emission/decoding behavior.

The complete native and desktop measurements, profiling traces, screenshots,
build logs and test receipts live outside the repository under
`DarwinBuild/LiberBuild/evidence/feed-performance` on the development storage.
The desktop workload also compares exact group/original-URL SHA-256 digests for
80, 240 and 800 posts. All three match the original output. The 800-post case
already hits an existing candidate safety bound and shows no speed improvement;
this change does not alter that bound to manufacture a better benchmark.

## Validation

The full core suite, Android feed/reading/filter/save/source/privacy regressions,
release lint and debug/release assembly are required before preview promotion.
New focused tests cover bounded operation-local analysis, exception cleanup,
parallel-reader isolation, canceled grouping, unchanged export serialization,
access-only updates, changed payloads and membership-only updates.

Recorded qualification: 1,778 core tests, zero failures, one optional native
embedding-dependency skip; 186 selected Android cases, 184 passed and two
explicit live-search opt-ins skipped. After the final save-capture adjustment,
all 45 cross-post, fusion and saved-library cases passed again, as did the final
native performance test. Release lint and debug/release assemblies passed.
Both native comparison screens were inspected. These fixture results do not
substitute for the skipped live-network checks.

Physical-phone speed, sustained battery/thermal behavior, and live-source latency
are separate qualifications. This pass does not claim them. The signed laptop
preview is updated only after backing up and comparing its protected local state;
the official Latest release retains its existing physical-phone gate.
