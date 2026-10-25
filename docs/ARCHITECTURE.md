# How anti social's systems work together

anti social is a released native Android reader with accountless retrieval,
multiple public source routes, local reader state and optional AI. Its security
and reliability come from cooperating layers. This is the starting map for code
review; [reviewing and evidence](REVIEWING.md) explains how to assess a particular
release. The [reader guide](READER_GUIDE.md) covers normal use.

## Connection modes and app-wide Tor

**Settings → Privacy & connection → Tor for all traffic** routes the app's
content, photos, videos and updates through its embedded Tor service. It works
on stock Android without root or another Tor application. Requests pause while
Tor is unavailable; choosing another content source does not authorize a direct
connection. This control is separate from the always-Tor transport used by
commercial public-index searches and regional discovery.

| App request | All-traffic Tor off | All-traffic Tor on |
| --- | --- | --- |
| Ordinary public reading, media, source probes, archives, downloads and updates | Direct connection | Tor; paused if unavailable |
| Commercial public-index searches, regional discovery and protected image-search routes | Separate Tor connection | Tor |
| Darwin alternative search routes | Selected content connection: direct | Tor |
| Explicit remote AI/service endpoint | Selected content connection: direct | Tor |
| Explicit loopback/LAN model server | Only the selected, tightly scoped local endpoint | Blocked; no hidden direct exception |
| Installed on-device model computation | Local computation | Local computation |

The control governs anti social's networking, not other Android applications.
An explicit external-app/browser handoff uses that other application's connection.
Local computation can still need a gateway download when a model is installed.

[SettingsScreen](../app/src/main/kotlin/app/antisocial/android/SettingsScreen.kt)
contains the control;
[Repository.applyNetworkSettings](../app/src/main/kotlin/app/antisocial/android/Repository.kt)
connects preferences to
[TorController](../app/src/main/kotlin/app/antisocial/android/TorController.kt).
[NetworkGateway](../core/src/main/kotlin/app/antisocial/core/NetworkGateway.kt)
maintains separate default and Tor-required sessions. Updating search readiness
does not switch ordinary direct content to Tor. Enabling all-traffic Tor also
updates the default content session. A mode change invalidates queued requests,
old clients, responses and scoped validators; switching direct → Tor → direct
does not revive the old session. The SOCKS transport is owned by an app-private
Tor process, with readiness checked before a client can issue requests.

Queued SourcePool work also retains the reader's connection-selection identity.
This identity changes when direct/Tor mode changes, including a round trip, and
survives ordinary Tor-readiness updates. After waiting for transport, actual
network sessions are captured and remain subject to the existing strict guards.
This lets startup finish without treating it as a user mode change, while stale
queued work still stops. [Source-check admission](ROUTE_SCHEDULING.md#transport-readiness-and-source-checks-03813)
separates local preparation time/failures from provider health and read permits.

[Continuation admission](CONTINUATION_PAUSES.md) also preserves learned source
pauses while keeping issued cursors retryable. Explicit server deadlines survive
in-flight success and the bounded routing snapshot; an empty page with a valid
next cursor can still advance.

[Public Instagram comments](INSTAGRAM_PUBLIC_COMMENTS.md) use this same path,
then the existing discussion, local-name and Context consumers. Their source
comment identities remain distinct from the parent post.

[Public Threads pages](THREADS_PUBLIC_READER.md) retain complete galleries and
source-bound reply snapshots through the same original fallback and consumers.
Unrelated recommendation records never become discussion replies; a finite
public page does not establish working continuation or a renewed preview quota.

## A source route never bypasses the protection path

```mermaid
flowchart TD
    A[Reader action or bounded background job] --> B[Capability-aware source routing]
    B --> C[NetworkGateway: purpose, URL cleaning, filters, origin and session checks]
    C --> D[Selected direct or Tor transport]
    D --> E{Response}
    E -->|Redirect| C
    E -->|Bounded content| F[Identity and extraction checks]
    F --> G[Native cards and staged enrichment]
    F --> H[Isolated document or image worker where supported]
    H --> G
    G --> I[Expiring ordinary cache or protected explicit save]
```

The gateway is shared by adapters, media, feeds, archive recovery, Context,
model/service calls and updates. It applies Darwin-derived URL cleaning and
compiled network filters before connections, and reapplies destination checks
after rewrites and every redirect. Ordinary public reads reject insecure/private
destinations and credentials; direct DNS resolution rejects private addresses.
Changing a frontend therefore preserves the same protection and Tor policy.

Public acquisition keeps no social login session. Generic cookies and service
credentials are stripped. Narrow exceptions are explicit: a validated
source-scoped pagination bookmark can carry its non-account cursor, and a chosen
service endpoint can receive the user's configured credential. Service requests
are pinned to that endpoint and are not repeated across redirects.

Automatic supplemental reading also screens action-bearing links such as reset
or unsubscribe endpoints. Their source URLs remain available for deliberate
opening. Filters start from a bundled compiled snapshot or a valid saved one;
daily update work preserves working protection during refresh failures. Android
can defer that scheduled work. See [hardening](HARDENING.md) for exact bounds,
filter refresh and decoder behavior.

## How the layers combine on an ordinary read

Consider a post whose first frontend fails and whose replacement contains an
image and an article link. The controls below apply to different parts of that
same journey; passing one check does not remove the others.

| Stage | What the app does | Where to follow it |
| --- | --- | --- |
| Choose another route | Select an eligible method for the requested capability; require usable, matching content before treating it as delivery. | `SourcePool.load`, its adapter factory and `PublicPathways` |
| Fetch the replacement | Apply the current connection policy, URL cleaning, compiled filters, origin/credential restrictions and request budgets. Recheck redirect destinations. | `NetworkGateway` |
| Receive after a mode change | Reject a response or client belonging to the old privacy session. A direct → Tor → direct round trip creates a new session. | `NetworkSession` and Gateway execution guards |
| Decode the picture | Fetch through the gateway, then give the documented decoder worker one read-only input. Validate bounded returned pixels; reuse a decoded cache hit. | `Repository` media path and `ContentSandbox` |
| Read an attached article | Use the shared bounded public-copy resolver. Supplemental reading screens action links; relevant document extraction runs in its isolated worker. | `PublicArticleResolver`, `FetchPurpose` and `DocumentSandboxService` |
| Keep the result | Retain canonical attribution and source evidence. Apply ordinary cache expiry separately from explicit saves and offline downloads. | Repository, cache and Library consumers |

These are complementary controls: Tor governs the connection; filters screen
requests; source checks establish what was retrieved; worker isolation limits
the authority available while decoding; parent validation bounds what comes
back. Automatic retrieval does not launch the site's JavaScript. A deliberate
external-browser handoff is a separate action outside the app's transport.
The worker/format exceptions and their tested scope are documented below and
in [hardening](HARDENING.md#workers-and-authority).

### Contracts across the layers

Security review should follow both the request and the returned data. These
contracts remain in force when the router selects a different source or the
reader reuses previously parsed material:

| Transition | Contract that still applies | Separate question |
| --- | --- | --- |
| Primary source → alternative → permitted original/archive | Every admitted request goes through Gateway destination, purpose, filter, credential and selected-transport checks. A source cannot select a weaker connection policy. | Does the returned material actually match the requested post/account and operation? SourcePool and extraction decide this. |
| Tor starting/disconnected → ready | Selected Tor waits or fails closed. Readiness can finish queued work; a user mode change invalidates it. | Has a particular live exit/source worked on the dated test run? That requires its own observation. |
| Received bytes → native content | Passive extraction avoids running the publisher's scripts. Documented isolated workers receive a read-only input and return parent-validated bounded output. | Which decoder/format has permissionless isolation? The HEIF/AVIF compatibility path has different authority. |
| Network response → parsed-feed reuse | Every refresh still makes a protected request. Direct validators require the exact retained session/representation; Tor sends none. A failed read cannot become a successful refresh from this memory. | Does the source's finite feed window bridge the reader's previous position? Catch-up coverage is checked separately. |
| Parsed RSS → header navigation | A supported Nitter cursor is bounded data for the same source/account/operation and issuing session. Its request is pinned through rewrites and redirects; a mode round trip invalidates the bookmark. Parsed XML reuse does not replay an old 200 response's header. | Does this deployment expose RSS pagination? Some redirect to HTML; [feed qualification](FEED_COVERAGE.md#rss-header-navigation-03817) distinguishes implementation from dated availability. |
| Ordinary cache → explicit save | Cache expiry/cleanup and disposable transfer-file cleanup do not erase protected saved material or explicit offline downloads. | Did the original source supply a complete thread/media copy? Saving cannot manufacture missing content. |
| Evidence → optional AI brief | Deterministic retrieval, bounds and provenance precede model input. The model does not choose arbitrary tools, destinations or network policy. | Did the brief retain useful information accurately? This needs content evaluation, independently of transport and containment. |

For example, the absence of a supported image credential does not undo transport
privacy or parser containment, and successful Tor routing does not authenticate
a publisher's claims. Each layer answers its own question while the other
contracts continue to apply. The linked qualification records describe measured
behavior rather than converting these contracts into an unconditional guarantee.

## Redundancy includes different methods and learned delivery

The pool combines Darwin's donor instances with separately reviewed
[public pathways](../app/src/main/assets/public-pathways.json), source-specific
parsers, selected federated origins and capability-specific alternatives. Post,
profile, search, comments, media and chronological feed operations have distinct
contracts. The wider [source atlas](../app/src/main/assets/source-discovery.json)
also contains research candidates and gated leads; its presence alone does not
activate them.

[SourcePool](../core/src/main/kotlin/app/antisocial/core/Adapters.kt) selects
eligible sources using observed content, latency, cooldown and transport. Fresh
healthy routes keep priority. Unproven methods are interleaved before more
mirrors of the same method. Rolling hedges use available slots without waiting
for every older request; a winner cancels losing work. Accountless originals
remain behind alternatives and require the original-access setting. Matching
archive recovery has its own historical role. A source switch never silently
changes Tor or the selected public search provider.

A reachable homepage, login wall, unrelated profile or empty shell is not working
content. An undated preview cannot certify a feed refresh. Source identity,
ownership of cursors, extraction coverage and canonical original links survive
the route that fetched them. Route failure is not proof that all remaining paths
fail. Conversely, several mirrors can share an upstream: redundancy is measured
by useful delivery, not just a hostname count.

[Adaptive route maintenance](../core/src/main/kotlin/app/antisocial/core/RouteHeartbeat.kt)
uses actual demand, passive reads, shared host/global budgets, cancellation and
increasing delays for stable or repeatedly failed plans. It does not continuously
poll every registered source. Foreground reading takes priority. See
[scheduling](ROUTE_SCHEDULING.md), [measurements](ROUTE_LATENCY.md) and
[local-discovery routing](LOCAL_DISCOVERY_ROUTING.md).

[Public-index continuations](SEARCH_SCHEDULING.md) retain a fast first answer
and give unfinished query shapes their turn. The ordinary search feed can also
receive available pages while that work continues, through a selected-source,
checkpointed progress path. The plan preserves the chosen source/query, privacy
session and existing attempt allowance; stopping cancels its children.
Useful delivery and provider availability are learned separately: a late
refusal keeps the delivered cards while pausing further work at that index.
The pause covers its post search, account directory and probes across platforms,
with endpoint/transport scope. An in-flight success cannot erase it. Other
eligible methods remain subject to the same provider, original-access and
Gateway rules. See the [provider-pause path](SEARCH_SCHEDULING.md#useful-content-and-a-provider-pause-can-coexist).

## Acquisition, decoding, interpretation and retention are separate boundaries

Passive HTML acquisition does not run a site's scripts or embedded widgets.
Bounded structural admission precedes relevant DOM allocations. Ordinary images,
PDF display, article extraction and document preparation use read-only
descriptor-based isolated workers where documented; an independent watchdog can
terminate stalled parsing. Returned pixels/text/fields are checked by the parent.
The HEIF/AVIF compatibility path and model workers have different authority,
explicitly described in [hardening](HARDENING.md). A private process and an Android
isolated process are different mechanisms.

Optional model/runtime installation adds pinned hashes, signed catalogues and
verified runtime packs. Ordinary retrieval and filtering work with AI disabled.
Remote AI is an explicitly configured data destination, reached through the same
gateway; the text model does not control arbitrary networking or source routing.

Ordinary caches have expiry and space limits. Temporary extraction files have
bounded ownership and cleanup. Saved material and explicit offline downloads
are protected separately; clearing ordinary cache is not deletion of a user's
saved copies. Metadata/signature validation stays attached to the actual bytes
checked. These boundaries reinforce one another without requiring a server-side
reader profile or social login.

[Feed revalidation](FEED_REVALIDATION.md) can avoid repeated XML parsing after a
current network response. Direct-only, session-bound validators can avoid the
body transfer too. Tor always fetches the body and sends no retained validator.
This bounded memory belongs to ordinary cache, and never supplies a successful
refresh when the network request failed.

[Feed coverage](FEED_COVERAGE.md) separately checks whether that fresh window
reaches the retained previous head. Finite syndication windows cannot certify a
missing interval. Ordinary RSS/Atom can follow declared history links within the
existing catch-up bounds. A Blogger-specific path qualifies a published provider
link with its exact Atom blog ID, pins the HTTPS read and checks that identifier
before extracting returned posts; it does not grant generic cross-origin access.

For a native social feed that still cannot bridge its retained previous head,
the same catch-up operation can use a bounded native-history shortlist. It keeps
the useful head, source-owned cursors, original-access tiers and initial privacy
policy; extra timeline measurements stay separate from the cheap feed head.
This uses [the existing source pool within the refresh limits](FEED_COVERAGE.md#native-feed-recovery-03816),
without giving a saved Page its own networking authority or adding a background
crawler. Exhausted routes leave the missing interval visibly pending.
