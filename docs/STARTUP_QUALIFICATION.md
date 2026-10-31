# Whole-process startup qualification

## What this measures

The existing captured-feed replays initialize the repository before their timed
deck opening. They qualify that journey; process creation and application
initialization need a separate observation. Android distinguishes
[initial display from usable content](https://developer.android.com/topic/performance/issues/launch-time).
The first drawn app frame can still contain a loading or pending state.

On October 31, 2026, two ordinary launches use the exact published 0.38.90 APK
on an owned API 35 emulator with two cores and 2,048 MiB RAM. Retained state is
restored and every selected file's hash checked before each launch. OS/ART state
is not reset. Live source connections are rejected for the app UID in both IP
families; the emulator's network-capability state stays available. Resolver
service traffic is outside that socket-level observation. These are rejected-
connection/cache-reading observations, not live-provider performance results.

| Observation | First launch | Later launch |
| --- | ---: | ---: |
| System-reported initial display | 1,077 ms | 635 ms |
| Reading card visible in the 8-second capture | No | No |
| Restored reading card visible in the 12-second capture | Yes | Yes |

Actual native frames are inspected. Screenshot requests themselves take roughly
0.2–0.8 seconds and perturb the emulator. The frame labels describe capture
targets, not exact time-to-content measurements. These two observations do not
establish a latency distribution, cold-storage behavior, phone p95 or energy use.
The live reader installation and its retained state are unchanged by this pass.

## Test-only application hook

`StartupTraceRunner` samples immediately before the real `Application.onCreate`
and records its return. It calls the ordinary application implementation, then
`WholeProcessStartupTest` observes an actual visible reading card in the shipping
`MainActivity`. It does not replace content, seed records, change preferences or
substitute network readers. Android's
[framework callback order](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-15.0.0_r1/core/java/android/app/ActivityThread.java)
places provider/bootstrap work before this hook; that earlier work is outside
the method trace. The normal system launch observation above covers the first
frame independently.

Both runtime flags below are required. Use an expendable installation on an
owned test device with suitable retained records, and keep captures private.
The build property changes the **test APK's** instrumentation entry. The ordinary
test runner remains the default, and the reader APK gains no profiling entry,
permission or runtime component.

```sh
scripts/gradle.sh -PantisocialTestBuildType=release \
  -PantisocialStartupTraceRunner=true :app:assembleReleaseAndroidTest

# Install matching app/test APKs on the isolated fixture first.
adb -s ISOLATED_SERIAL shell am instrument -w -r \
  -e antisocialIsolatedFixtureDevice true \
  -e antisocialPrivateStartupTrace true \
  -e class app.antisocial.android.WholeProcessStartupTest#retainedStateReachesAnActualCardAfterApplicationCreation \
  app.antisocial.android.test/app.antisocial.android.StartupTraceRunner
```

Private output is under the target app's external
`files/validation/whole-process-startup/`: a method trace, milestone JSON and
the actual first-card screenshot. Each run needs a fresh output location or
explicitly removed old diagnostic output. A successful activity launch alone
does not establish a usable trace. The check requires nonempty method records.
Ordinary builds omit the Gradle property and use `AndroidJUnitRunner`.

## Observations and qualification

The API 35 hook run passes. Its dual-clock trace contains **95,006 records**,
with no overflow or reconstruction anomalies. Application entry/return markers
are 55 ms apart; the first card is observed about 13.2 seconds after sampler
setup. This instrumented interval includes test-framework and semantic-observer
work and is not a production launch measurement.

Approximate inclusive sampled attribution places about **5,212 ms** in source
grouping. Caption comparison accounts for about **602 ms**, including about
478 ms under HTML parsing and 76 ms under selector work. Nested attribution is
not additive. This directs the next experiment toward plain-caption processing;
it does not establish an end-to-end gain. A separate desktop differential
preflight finds no mismatch among over 137,000 eligible plain-text cases using
the pinned parser's public text-node path. Native equivalence and whole-feed
comparison remain prerequisites for retaining such a production change.

The minimum API 26 hook also passes and produces a nonempty dual-clock trace
with **173,202 records**, no overflow and no reconstruction anomalies. Host build
work overlaps that compatibility run, so its timings are not used for a speed
comparison. Actual restored-card frames are inspected on both API levels.

Normal-build qualification passes **2,771 JVM methods with four optional skips**
across 2,775 cases, with zero failures/errors, plus release/test assembly and
release lint. A separate API 26 default-runner selection has **six passes, one
explicit startup opt-in skip and zero failures**. It creates no method trace
without the explicit flags. Every one of the rebuilt app's **1,058 non-signature
members**, including its source stamp, matches the published reader. All device
runs install the exact published APK. No new reader release results from this
diagnostic addition.

Three setup counterexamples remain in the private evidence: the shell profiler
launches the activity but produces a zero-byte file; an extra-manifest approach
does not install the selected runner and executes no tests; a first API 26 setup
stops at log-buffer clearing before invoking instrumentation. None is counted
as a successful trace or Android test. The explicit test-runner configuration
produces the accepted captures. Temporary cloned state, diagnostic outputs and
egress rules are removed from the test devices. API 35 returns to its hash-checked
empty baseline; API 26 returns to its hash-matching pre-probe file contents. Detailed inputs,
screenshots, traces and receipts remain in the
private external build area.

### Subsequent API 26 fixture-metadata correction

The 0.38.91 qualification found that the restored API 26 `files` directory
had lost its per-app SELinux categories. Its owner and file hashes matched, but
the app could not write its health snapshot. The earlier restoration receipt
therefore establishes file contents, not complete Android filesystem metadata.
The recorded startup traces and observed cards remain their stated observations;
that receipt does not qualify subsequent writes.

The disposable fixture is backed up, relabeled using the installed tool's
explicit app-data option (`restorecon -DFR`), and checked for its expected owner
and categories. All 122 retained file hashes remain identical. Staging of later
private packets uses that option too. The unchanged frozen APK is then qualified
again; [the release record](RELEASE_QUALIFICATION.md) retains both the failed and
corrected selections. These are engineering operations on an owned emulator,
separate from the stock Android reader's features.
