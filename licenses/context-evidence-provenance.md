# Context evidence acquisition

anti social’s acquisition/controller code is GPL-3.0-or-later; Darwin remains the
rebuilt and expanded routing donor. Context requests reuse `NetworkGateway` and
the existing source pool. The following independent parsers retain upstream
licenses and attribution.

## Local article isolation

The shared0.35.27 text/Markdown preparation and additional design references are
documented in [Protected web reading](../docs/WEB_READING.md). These additions
use the existing JVM parser; no additional browser, Python or conversion-service
dependency is bundled. Native HTML output remains inert text blocks.

Readability4J, by Christian Dankl / dankito, is a Kotlin port of Mozilla
Readability. anti social uses its local parser after gateway retrieval and explicit
login/challenge/content checks. There is no page JavaScript, browser session,
renderer, resource loader or external entity resolver in this path. The source
URL is used only as a base for resolving links; body links are retained as
attribution and are not followed by the article parser.

- Maven coordinate: `net.dankito.readability4j:readability4j:1.0.8`.
- Source: [Readability4J](https://github.com/dankito/Readability4J), tag `v1.0.8`,
  commit `c4e1f5ba59a29c50847a17a01e7551d7d8c7b9a7`.
- License: Apache-2.0; exact upstream text is preserved in
  [readability4j-APACHE-2.0.txt](readability4j-APACHE-2.0.txt), SHA256
  `2d5a668bf49d257553d5ddc9ff7b8c119d143aadc0263657e5c47c9b2188dd7d`.
- Published binary JAR: 80,492 bytes, SHA256
  `515bca66af21b7aca2c451bd75d7fa8c05df25ad74e0243047fa11e0ce2d9223`.
- Published source JAR: 28,275 bytes, SHA256
  `f4bb9b0c0b54f16a4f5c16952d0a13e4e0e06c8b4e4c0bc0d666c280029c728d`.
- Published POM: 2,842 bytes, SHA256
  `194999cd7257cf7a4356db792adb03455f134b662349d74b44383d69d99d5b80`.

Artifacts were acquired from Maven Central under the external build root's
`context-foundation/readability4j/` directory. A host Java fixture confirmed the
published parser works with anti social’s declared Jsoup 1.21.2 constraint and retains
article headings/body while removing navigation/footer/script text. That is
binary/parser qualification, not evidence of live publisher availability or
Android native execution. The external `qualification.json` records the exact
dependency bytes and scope. Readability4J's own Firefox-parity descriptions are
upstream claims, not anti social’s independently measured equivalence assertion.

Jsoup, Kotlin and SLF4J retain their upstream MIT/Apache notices and existing
dependency attribution. The article DTO preserves the requested and final URL,
title, author/site assertions, available publication time, ordered text/heading
blocks, source links, acquisition time and explicit truncation. Conditional
requests retain ETag/Last-Modified validators and return typed unchanged results;
validators are pinned to the requested origin across redirects. No model is
given authority to fetch, browse, run scripts or decide routing.

## Public YouTube captions and audio descriptors

The existing independent Invidious/Piped JSON readers and GPL-3.0-or-later
NewPipeExtractor v0.26.5 remain the transport/parser sources. Protocol references:

- [Invidious public API documentation](https://docs.invidious.io/api/).
- [Piped API documentation](https://docs.piped.video/docs/api-documentation/).
- [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor).

anti social’s new timed-text decoder is independent code for WebVTT/SRT, YouTube
timedtext XML/SRV3, TTML and JSON3. It retains timestamps, real gaps, language,
automatic-caption status and source provenance. It bounds cue/text counts and
marks budget-truncated output. There is no claim that missing captions imply a
video was watched or transcribed. A verified empty public track list is distinct
from missing/malformed capability, login, a blocked route or failed transport.
Those failures must not silently authorize ASR.

Caption/audio metadata uses the source pool's candidates and existing backoff;
alternates precede permitted accountless originals. No new health heartbeat is
started and caption metadata is not mislabeled as a working video stream. A
per-request original opt-out pins alternate metadata and caption requests before
an external redirect can open a socket. Tor and filtering remain gateway policy.

Audio metadata is only a short-lived descriptor; no playback proof, audio
request or media download occurs during caption acquisition. Explicit audio
requests can reuse the same bounded metadata cache. Actual later media reads and
local decoding belong to their separately bounded gateway/decoder stages.

`ContextAcquisitionTest` contains controlled HTML/API/caption fixtures for source
identity, attribution, conditional requests, non-executed resources, malformed and
empty content, caption timing/formats/budgets, no speculative audio IO,
alternate/original order, privacy filters, Tor unavailability and redirect pins.
Fixture coverage is not a claim that a currently public frontend or publisher
will always be available. Root integration owns final unit/instrumentation
results and native-screen evidence.

## Darwin public article routes (0.35.23)

`DarwinArticleRoutes` and `PublicArticleResolver` adapt Darwin's rebuilt routing
component, specifically `news-archive-routes.js`, `news-archive-content.js`,
`publisher-lite-routes.js`, `scientific-routes.js`, `scientific-discovery.js` and
its Medium route catalog. The GPL donor remains Darwin. Exact source hashes ship
in `core/src/main/resources/darwin-article-routes.json`; the read-only import is
`scripts/import_article_routes.py`. It imports only public HTTPS instance roots,
not credentials, runtime/profile state or private provider settings. The existing
GPL notices and license remain distributed with AntiSocial.

Four Medium families (Scribe, LibMedium, Small, Freedium) contribute 14 distinct
public entries. Freedium's original-URL prefix corrects an outdated donor path:
[the project's usage documentation](https://github.com/Freedium-cfd). This is a
protocol adaptation; availability is separately qualified. The scientific path
keeps exact DOI/PMID identity, public-access assertions, version and retraction
fields from [OpenAlex](https://help.openalex.org/api/) and
[Europe PMC](https://europepmc.org/RestfulWebService). It performs free, keyless
public lookups and never signs up for a service or purchases access. Service
policies can change; failures remain bounded and other paths can continue.

Wayback uses its read-only exact-URL CDX index and timestamped HTTPS replay.
Archive.today uses existing-capture searches only, with capture metadata checked
against the requested article. No archive submission, cookie jar, logged-in
browser profile, paid credential or page JavaScript is used. Readability4J and
Jsoup remain the local HTML parsers with their existing notices. Android's
PdfRenderer supplies native PDF viewing; Context reuses the existing public PDF
page pipeline. On Android below API35, embedded-text availability may require the
optional local image-text tool already documented here.

The ordinary native-reader cache is separate from saved/offline ownership but is
included in the user's ordinary storage accounting. Archived date, original
publication date, public-copy version and retrieval time remain distinct. Source
metadata is a declaration, not independent proof of a paper's status or the
correctness of its contents.
