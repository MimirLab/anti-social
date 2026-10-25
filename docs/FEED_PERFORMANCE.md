# Feed performance qualification

## 0.38.28: faster reading-signature preparation with identical results

The current 0.38.27 observation-stage trace still attributes most sampled work
to text preparation, with another substantial part in per-byte formatted hex.
The replacement scans whitespace directly and writes the SHA-256 hex digits
directly. It retains normalization, trimming, content framing, source references,
media identity and all matching/contradiction rules. It adds no retained text
cache, network request, model, dependency or native runtime.

Compatibility includes the runtime's Unicode behavior. Android's regex character
classes are Unicode-aware by default, unlike desktop Java's defaults. A one-time
NEXT LINE check preserves that distinction; `Character.isWhitespace` alone would
also consume other controls the previous expression preserves. The helper keeps
the old `[\s\p{Z}]+` replacement semantics, including supplementary and malformed
UTF-16 boundaries. See [Android's Pattern contract](https://developer.android.com/reference/java/util/regex/Pattern#UNICODE_CHARACTER_CLASS).

Direct JVM comparison covers all 1,114,112 codepoint values and mixed/control
boundaries. Native API 35 checks compare the complete existing signature recipe
over the same generated Unicode range with the old runtime regex as oracle.
Fixed signature vectors retain leading zero bytes, embedded controls and Unicode
normalization. The Android check confirms NEXT LINE is whitespace there.

Three process-restarted untraced samples per APK used the same private 517-post,
24-source snapshot on the same API 35 emulator, without a concurrent compiler.
All six retain 468 ordered groups, the same group membership/order digest, and
the same digest of all 517 observation keys, signatures and source timestamps.

| Local CPU stage | 0.38.27 | Candidate |
| --- | ---: | ---: |
| Observations, three samples | 1.245 / 1.227 / 1.242 s | 0.737 / 0.742 / 0.746 s |
| Observation median | 1.242 s | 0.742 s |
| Grouping median | 6.140 s | 5.768 s |

Observation preparation takes 40.3% less time in this workload. A separate single
paired native 240-post benchmark retains the same 120 cards, initial card and
cards after eight swipes. Opening changes from 3.980 to 3.572 seconds; the
eight-swipe wall time remains similar at 4.916 / 4.966 seconds. The 24-frame samples
still have stalls, with p95 350 / 367 ms. This run does not demonstrate improved
scrolling or phone FPS. It is a warm authored fixture; neither measurement is a
complete cold-launch or live-source latency claim.

Further exploratory native pairs use 100 authored posts each in English,
Chinese, Arabic, emoji and mixed text. Each pair retains its complete group and
observation digests and takes less observation-stage time with the candidate.
A later original-snapshot control returns to 1.226 seconds on the baseline and
0.710 seconds on the versioned candidate, retaining the same output digests.
These controls support the measured mechanism; they are not broad language or
physical-device benchmarks.

Raw traces, frozen APKs, the private external fixture, exact-output checks and
native screens are retained under `evidence/reading-preparation-20261025`.
No private post material is bundled or published. The opt-in diagnostic now
records an observation digest and can trace one selected stage without tracing
the others. Those diagnostic flags default off.

## 0.38.26: reuse URL normalization during one complete grouping

Repeated source, caption and media URLs now reuse their normalization result
inside the existing operation-local fusion workspace. The exact input string is
the key, including case and parameters; the normalization rules are unchanged.
Null results are reusable too. The new map shares the existing estimated 8 MiB
workspace budget, has a 2,048-entry cap, and is discarded on return, cancellation
or failure. It adds no persistent browsing cache or network request. Independent
threads retain independent workspaces and nested work shares the current one.

Three process-restarted untraced stage measurements per APK used the same private
517-post snapshot, 24 sources and API 35 emulator, with no concurrent compiler.
Every run produced the same 468 ordered groups and membership/order digest.

| Grouping stage | 0.38.25 | Candidate |
| --- | ---: | ---: |
| Three samples | 6.859 / 6.800 / 6.841 s | 6.116 / 6.214 / 6.066 s |
| Median | 6.841 s | 6.116 s |

The measured stage takes 10.6% less time in this workload. No match threshold,
source edition, contradiction guard or image-identity requirement changed.
These measurements do not establish physical-phone, live-provider, actual-paint
or complete cold-launch performance. The remaining reading-preparation and
image-revision work remain separate targets. Raw results and the private fixture
stay external under `evidence/feed-url-work-20261025`.

## 0.38.24: skip waits on missing cache files and reject distant galleries early

The cold image-comparison rebuild previously delayed 40 ms after every unknown
media URL, even when its file was absent. It now delays only after actual
cached-file work and still yields between URLs. Its 2,048-URL bound, local-only
retrieval, two-decoder limit, downsampling, cancellation and matching rules are
unchanged. The existing native case scans 171 URLs with only the last two files
available: both versions recover that late pair. Whole instrumentation-case
time changed from 9.604 to 1.957 seconds on the same API 35 emulator. This is a
single case comparison, including its setup, rather than a phone or full-reader
startup measurement.

Native CPU sampling of a private 517-post snapshot also found repeated original
identity parsing during gallery comparisons, mostly for posts outside the
gallery's time window. The existing time and media exclusions now precede the
existing identity proofs. All proofs and thresholds remain mandatory.

Three process-restarted, untraced diagnostic runs per APK used the same snapshot,
24 sources and memberships on the same API 35 emulator, without a concurrent
compiler or another instrumentation batch:

| Grouping stage | 0.38.23 | 0.38.24 |
| --- | ---: | ---: |
| Three samples | 8.655 / 8.143 / 8.155 s | 6.847 / 6.732 / 6.599 s |
| Median | 8.155 s | 6.732 s |
| Complete ordered groups | 468 | 468 |

The group-membership/order digest matches in all six native runs and both frozen
desktop-core runs. Desktop content-signature digests also match. No private post
text, reading history or captured fixture is bundled or published. The opt-in
`CachedFeedProfileTest` requires an external fixture on an explicitly disposable
installation; it neither imports posts into Room nor makes acquisition calls.
Its initial offline-policy assertion caught a posted Tor-stop race in the test
setup; the corrected setup completes that stop on the main thread before
installing and checking the offline policy.

These measurements isolate local CPU stages. Text projection and content
observations still take about two seconds in the initial stage samples, and
image-revision cancellation can restart work. They do not establish that the
complete cold-reader delay is solved. The earlier hypothesis that inherited
reading-history comparisons dominated this snapshot was not supported: they
took about 22 ms with its captured read keys. Raw traces, intermediate failures,
APK snapshots and comparisons remain external under
`evidence/cached-feed-profile-20261025`.

## 0.38.23: reject conflicts before repeated shared-text analysis

Native method sampling found repeated ICU word scans while comparing nearly
identical captions that were subsequently rejected for a changed bulletin
number. Alignment now checks the existing unmatched-context conflicts before
scanning the shared passage's lexical richness. Both checks remain mandatory.
No matching threshold, source window, candidate limit, original edition or
contradiction guard was removed. It adds no cache, network request or model call.
Readers still receive one completely checked collection.

The same opt-in 240-record X/Facebook fixture ran on the same isolated API 35
x86_64 KVM/SwiftShader emulator, serialized without a concurrent compiler.
Both runs produced the same **120 merged cards**, the same initial card and the
same cards after eight scripted swipes.

| Native measurement | 0.38.22 | 0.38.23 |
| --- | ---: | ---: |
| Core grouping, median of three | 6.830 s | 2.710 s |
| Opening until first merged card | 8.765 s | 4.196 s |
| Eight swipes with test synchronization | 4.830 s | 4.966 s |
| Sampled frame duration, median | 147 ms | 150 ms |

Opening improved in this workload; scrolling did not improve in this comparison.
The window frame sample contains only 24 frames/run and still includes stalls.
These are authored stress-fixture measurements with a warm runtime, not cold
process-start latency, live-provider latency, phone FPS or battery measurements.
The richer real cached-image startup path remains a separate profiling target.

Desktop group/original-URL SHA digests also match at 80, 240 and 800 records.
The 800-record workload remains constrained by the existing broad-candidate
safety bound; it gains no speed in this comparison. Raw evidence is retained
externally under `evidence/feed-startup-20261025`, including the unchanged
baseline APK/core snapshot, native traces, untraced results and actual screens.

The full JVM run completed 2,159 cases with zero failures/errors and one optional
model-weight skip. The Android batch covered 122 named checks: 120 passed, two
opt-in live-Tor/NASA-neighborhood checks skipped, zero failures. It includes
reading anchors, frozen editions, source failures and cancellation, filters,
source-window invalidation, privacy and native public comment/history controls.
The opt-in benchmark passed separately. Release assembly and lint pass.

The existing benchmark now has optional `antisocialTraceFeedPreparation` and
`antisocialTraceFeedOpening` flags for native method sampling. They default off;
traced runs are diagnostic and excluded from the timing comparison above.

## Earlier 0.36.0-rc5 qualification

This change removes repeated local work from feed opening and scrolling. It adds
no network requests, language-model calls, or new reader controls. Darwin routing,
progressive account discovery, late image/source enrichment, privacy protection,
and the existing matching thresholds remain in place.

### Work moved off the reading path

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

### Reproducible workload and limits

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

### Validation

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
