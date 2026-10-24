# Routing and refresh maintenance, 0.38.1

[0.38.8 scheduling](ROUTE_SCHEDULING.md) extends the per-attempt hedging below
across batch boundaries and diversifies unproven frontend choices.

This pass fixes avoidable work and lost results before expanding the route pool.
Production retrieval continues through NetworkGateway and Darwin's protection
and routing machinery. No AI or social login is required.

## One failure advances one backup

The former first-failure signal woke both waiting alternatives in a three-route
batch. A failed primary followed by a quick successful second route still
contacted a third host. Each attempt now releases its successor on failure; a
stalled successor gets its own learned hedge interval. Explicit failures advance
immediately, losers are cancelled, and source eligibility is rechecked just
before delayed launch. Alternate/original privacy tiers remain in force.

Two MockWebServer regressions failed before the change by observing the unwanted
third request. A separate regression checks that two immediate failures do not
wait out learned stall intervals. The design follows the failure/stall distinction
in [gRPC's hedging guide](https://grpc.io/docs/guides/request-hedging/).

## A refresh enriches the discovery already on screen

“Check again” requests fresh collection while retaining unexpired positive cards.
Partial and empty snapshots merge with the previous neighborhood. Edge identity
includes relationship, owner, intermediate account, source URL and method.
Original observation times survive; empty refreshes cannot renew old evidence
indefinitely. Neighborhoods retain the six-hour lifetime and count/storage bounds.

The open deck also retains recent bridge methods and resolved readable aliases
under the same seed/network policy. Rechecking still runs acquisition. An empty
successful final page is pagination evidence and does not cool down a valid
first page. Genuine failures retain adaptive backoff.

## Shared budgets survive nested enrichment

A child bridge previously installed a new coroutine budget, hiding the caller's
counters. Independent child lookups could exceed a search's overall allowance.
Nested budgets now charge the child and every enclosing budget. The stricter
allowance wins, including redirects and chunked document/media bodies. Ordinary
reads outside that scope remain unaffected.

Before the fix, two of three new gateway tests failed: child requests escaped a
two-request parent and mixed document/media reads escaped a 1 KiB parent. The
third test protects a stricter child allowance.

## NASA's “We are not slowing down”

The captured X, Instagram and Facebook editions share a complete Artemis caption.
Instagram adds a hashtag. Nitter RSS supplied a pinned-title prefix, terminal
video label and frontend self-link. Facebook and X use different campaign short
links for the same two NASA articles.

Presentation removes only proven title/body echoes and the enclosed-media label.
A frontend self-link counts as attribution only when acquisition source,
platform and original post ID match. Conflicting opaque campaign codes can be
compatible only for a complete, distinctive, exactly matching caption, media on
both editions, explicitly linked publishers and timestamps within an hour.
Changed numbers, negation, symbols, concrete destinations and existing pairwise
checks remain decisive. Grouping makes no network request.

The caption appears once; all originals, links, distinct media and comment roots
remain. This does not declare different media files identical. Regression captures
retain public text; ephemeral media URLs become distinct type-preserving test URLs.

## Independent fallback to qualify next

[XProfiles for FreshRSS](https://github.com/shcheglovnd/FreshRSS-xExtension-XProfiles)
is a small MIT project reading logged-out X pages through a restricted Seroval
data decoder, without executing scripts. Anonymous research reads on October 24
returned one timeline payload and five `full_text` fields for each of NASA and
SpaceX, in approximately 191/182 kB pages. This establishes a promising original
fallback lead, not a qualified native adapter, complete timeline or search route.

Before activation: bound the literal decoder; validate authors, IDs and dates;
test hostile grammar/references; preserve media/quotes; qualify Android gateway
behavior. It must remain behind alternatives and obey original-site/Tor policy.
Five records cannot replace existing catch-up/history routes.

Substack's owned per-route deadline is distinguished from cancellation of its
caller. A slow proxy can fall through to an allowed original, or return an
ordinary unavailable-source result without cancelling other account-discovery
lanes. An expired parent context still cancels children and does not train an
outage. The actual alternate-only probe exposed this distinction; regression
tests cover both boundaries with injected short deadlines.

See [Substack integration](SUBSTACK_SOURCES.md#0381-identity-and-discovery-integration).
Raw bodies, private backups and test logs stay on the external build volume.
