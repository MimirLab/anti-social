# Continuing a paused source, 0.38.20

An issued page cursor preserves where to resume; it does not exempt its source
from a learned outage or rate limit. `SourcePool.continuePage` checks the selected
source's capability/transport pause before request admission and again after any
transport preparation. This applies to ordinary same-reader pagination as well
as the existing cross-reader history handoff and public-index continuation.

The check is local. It adds no request, sleep, background timer, browser or native
transport. Refusing a click happens outside the source-observation catch: it does
not count as another failed read, alter delivery estimates, consume the cursor
or restart the wait. A later explicit retry uses the same issued checkpoint.
The reader's existing cards and navigation remain available after a failure.

`RouteStats.retryAfterUntil` retains an explicit server deadline separately from
adaptive backoff. A successful response that was already in flight can improve
delivery estimates without revoking that deadline. The bounded routing snapshot
preserves it across restart; older snapshots without this field retain their
existing cooldown. The existing maximum server wait is one day. Source,
capability and direct/Tor scope remain separate; a busy reader does not freeze
every other reader or operation. Public-index endpoint-wide pauses keep their
existing cross-platform scope.

An empty head/window is different from an endpoint pause. A validated same-reader
cursor can advance beyond an empty search page without labeling that page healthy.
Account-specific head restrictions still participate in admission when handing
off to a different reader. This distinction preserves Facebook's real empty-page
continuation behavior.

The HTTP background is [Retry-After in RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html#section-10.2.3)
and [429 in RFC 6585](https://www.rfc-editor.org/rfc/rfc6585.html#section-4).
This change covers SourcePool's selected social/search/comment continuations;
independently collected external RSS pagination has a separate path. It does not
establish unlimited history, a quota reset or fingerprint-based login-gate access.

## Qualification

The unchanged 0.38.19 production implementation failed an owned Gateway regression:
after an HTTP 429 with `Retry-After: 120`, a second same-issuer continuation consumed
the queued response during the wait. The corrected checks cover 429 and 503,
zero blocked-retry traffic, decreasing remaining wait, unchanged observations and
the exact original `after` cursor on recovery. Routing tests also cover an
in-flight success, storage/restore and source/capability/transport separation.

The first full regression found that applying account-specific empty-head pauses
to same-reader cursors blocked a valid Facebook continuation. The admission check
now distinguishes that case from endpoint pauses; the original regression is
retained unchanged. Failures and subsequent checks are retained externally under
`evidence/continuation-pause-20261025`.

The Android history fixture exercises the actual Gateway/adapters and JSON
routing snapshot, refuses a repeated continuation without traffic, then resumes
through the cached navigation owner and renders the retained native cards.
Live-platform and physical-phone observations must be interpreted separately
from those owned-fixture results.

Final qualification on October 25, 2026: all 2,142 JVM cases completed with zero
failures/errors and one optional model-weight skip. The API 35 native selection
passed 14 methods with one opt-in live-Tor skip and zero failures. It includes
feed history/coverage, public-index pause handling, connection controls, the
owned persisted-pause fixture and actual public-profile Load more buttons.
Native fixture cards and the live profile grid were visually inspected.
Debug, instrumentation and release assembly, plus release lint, passed.

A separate live SourcePool read still delivered six dated RSS-Bridge posts and
then six and twelve additional public grid posts: 6 → 12 → 24 unique cards.
Observed times were 1,091 ms, 421 ms and 503 ms. These are individual emulator
observations, not a performance distribution or a physical-phone/Tor result.
All 33 existing native-library ZIP members are unchanged from 0.38.19; this fix
adds no native runtime or model. Fingerprint-based preview renewal remains
unqualified and the experimental transport remains excluded by default.
