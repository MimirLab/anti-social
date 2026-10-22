# Next-release qualification

Status: implementation and qualification in progress. No physical Android phone
has been qualified for this release yet. The latest official release remains
unchanged until the gates below are satisfied.

## Evidence collected so far

- An independent all-ABI build started with an empty tools/products root and
  completed all 12 dependency-ordered steps, JVM tests, debug/release APKs, both
  lints and corresponding-source packaging. Its candidate source tree was
  `5fb28e104ffc5724a025a571a662f413022cacfb`; later Context/cache changes require
  their own final regression. Elapsed time: 3,290 seconds. This verifies the
  documented build path, not phone performance or the final release artifact.
- On an isolated API 35 x86_64 emulator, 38 local-discovery/menu/announcement
  instrumentation tests passed before the final cache refinements. Native event
  and detail screens were inspected. A generated flyer was recognized by the
  real OCR worker with AI disabled and selected Tor unavailable; neither Context
  media preferences nor AI activation were changed.
- Context trials separate harvested, admitted and delivered facts, noise
  admission, cached reuse and elapsed time. Early trials that improved recall
  while admitting irrelevant material were rejected. Fixtures must include the
  same prepared-English metadata as the app; earlier fixtures lacking it are
  diagnostic experiments, not production-input comparisons.

Private raw artifacts remain under the external `quality-release` evidence
folder. No physical phone has been qualified. These partial receipts do not
satisfy the publication gate.

## Required journeys

| Journey | What must work | Measurements |
|---|---|---|
| Family events near Melbourne, FL, 25 km | Relevant activities, exact dates/locality, visible unknowns, optional uncertain announcements | Cold/cached first useful card, staged completion, missed relevant events and wrong-area results |
| Restaurant branch and menu | Correct branch, useful contact actions, preserved menu type/source, working public social account | First card/detail time, false merges, missing fields and unavailable-route recovery |
| Subscription catch-up | Stable reading position, merged cross-posts/images, incoming banner and truthful caught-up boundary | Scroll behavior, repeated content and pending unavailable subscriptions |
| Post and Context evidence | One press, cached brief, concrete useful details, conditions retained, noise-only updates reused | Fact retention, unsupported statements, noise admission, calls/time, memory and cancellation |

Compare matched current/candidate runs on a stock physical phone. Record model,
quantization, active KV/window, ABI, OS and device. Host/server measurements do not
qualify phone performance. Test CPU fallback and GPU where the phone supports it;
memory admission correctly refusing an oversized model is not an inference pass.

## Automated gates

- JVM tests, Android instrumentation and lint pass; inspect native screens and accessibility.
- AI-off discovery and bundled OCR work; selected Tor unavailable fails closed.
- Uncertain announcements cannot acquire invented dates, venues or eligibility.
- Saved copies survive cache expiry and upgrades; scratch files are cleaned.
- Frozen Context cases score useful-fact retention, noise and grounding separately.
  Review prose and qualifications, not only completion flags or schema validity.
- Initial cards do not await OCR/models. Cancelled searches cannot update the next
  area's deck. Background work ends with the bounded search; no idle polling loop.
- A fresh checkout with an empty external build root completes the documented build.

## Runtime promotion

F16/2K remains the shipping local baseline until a candidate cache profile improves
complete-task quality/time within phone memory limits and passes cancellation,
thermal and retained-information controls. Report each profile separately. Do not
promote compressed KV, a larger context or speculative decoding from acceptance
rate, host speed, valid JSON or source-code availability alone.

## Evidence and publication

Keep raw test artifacts and private backups on the external build volume. Commit
only sanitized fixtures, reviewed screenshots and summarized results. Record
first-card latency, network requests/bytes, peak memory, foreground energy/thermal
observations, and idle behavior. Separate reproducible app defects from dated
external-source outages; both require honest coverage notes.

Back up and update the laptop preview after tested increments. Publish the
official APK and corresponding source through `scripts/publish_release.py` only
after physical-phone qualification. Preserve signing identity, app ID and the
fixed-name download asset. No credentials or personal reader state are release artifacts.
