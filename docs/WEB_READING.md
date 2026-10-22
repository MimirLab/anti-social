# Protected web reading

anti social fetches bytes through `NetworkGateway` and uses local, deterministic parsers.
Ordinary article extraction runs no page JavaScript, frames, plugins, advertisements
or subresource loader. It needs no AI, browser runtime, external conversion service
or MCP server on the phone. Darwin remains the routing donor.

The shared article path serves native reading and background Context acquisition.
Readability4J/Jsoup isolate the readable body and produce typed blocks. A compact
Markdown subset preserves headings, ordered and nested lists, quoted passages and
code indentation for Context. Simple tables retain explicit column/value pairs;
spanning tables retain visible rows without guessing column assignments. Native
screens render these blocks as text, rather than rendering downloaded HTML.
Literal source links and attribution remain separate, inspectable data.

Site-specific social parsers already perform the equivalent extraction for posts,
media and comments. RSS/Atom and JSON retain their structured parsers so post IDs,
dates, authors and reply relationships survive. Media, captions and PDFs keep
their format-specific handlers. Converting those responses indiscriminately to
Markdown would discard useful structure.

HTML remains bounded to 4 MiB, 30,000 DOM elements and 240,000 extracted characters.
Login walls, challenges and empty results remain unavailable; a script-dependent
page uses the existing alternate-source policy rather than starting a browser.
Posting text, images, captions and audio retain separate coverage. YouTube's
optional signature decoder is a separate, restricted extractor capability; the
ordinary article parser does not execute website code.

Every network hop uses the current filter/connection policy. Filter rewrites are
also checked before any socket. Explicit browser handoffs receive a fresh local
URL/filter check, including destinations with cached availability evidence. This
adds no availability request or DNS lookup. An external browser controls its own
subsequent networking, redirects and page execution; the local handoff check is
not a guarantee of that browser's protection or Tor routing.

Filter updates have an independent daily WorkManager task. It needs unmetered
connectivity and healthy battery/storage, with no charging/idle requirement and
no dependency on route probes or subscription refresh. Initial work waits at
least 15 minutes to avoid competing with startup. Android may defer execution;
this is not an exact-time alarm. Recent downloaded lists are reused during
automatic retries, and foreground traffic preempts deferred downloads. Three
attempts per cycle use exponential backoff beginning at 30 minutes. Existing
compiled protection remains active until a replacement is ready. Cancellation
clears the update's busy state and preserves working protection. Manual updates
remain available in Settings.

Cleanup follows ownership. Extracted HTML text lives in the bounded reader/Context
cache; no browser profile or downloaded website resource tree is created. Ordinary
posts, media and lookup caches expire under their retention/storage settings.
Unsaved Context is removed when its owning cached posts leave; inactive temporary
Context work is removed when complete, orphaned or older than a day. Ordinary PDF
viewing copies are bounded to three recent files and one day, within their byte
allowance; an open reader holds its file until release. Saved material and explicit
offline/model downloads keep their separate owners and storage controls.

Filter storage keeps one current compiled snapshot and one last-good text copy for
each enabled source. Disabled/removed source copies and abandoned partial writes
are pruned under the filter writer lock at startup/update and opportunistic cache
cleanup. Cleanup skips an active filter writer. Atomic writes remove temporary
copies after success or a handled failure; a later sweep removes filter parts left
by process termination. Existing protection and saved files are not aged away as
disposable download files. Development APKs, source archives and preview backups
are retained separately on RedtopStorage for recovery.

Static extraction removes the active webpage execution surface. It is not a full
operating-system sandbox or a claim that every parser/media codec is immune to
malicious input. The current PDF/media worker has a separate process but shares
the application's UID/permissions; it is not an Android isolated-process boundary.

Design references inspected on 20 October 2026:

- [Mozilla Readability](https://github.com/mozilla/readability) explains local DOM
  extraction and warns against enabling scripts/resource loading or rendering
  unsanitized results. The existing JVM port and notices remain documented in
  [Context evidence provenance](../licenses/context-evidence-provenance.md).
- [Defuddle](https://github.com/kepano/defuddle) demonstrates content isolation and
  document normalization before Markdown conversion. anti social uses the local
  techniques, with no Defuddle async service fallback or JavaScript dependency.
- [MarkItDown](https://github.com/microsoft/markitdown) demonstrates preserving
  document structure for language-model input. No Python runtime or MarkItDown
  package is bundled.
- [Trafilatura](https://trafilatura.readthedocs.io/en/latest/extraction-overview.html)
  discusses the tradeoff between excluding boilerplate and retaining useful text.
- [Android background work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
  documents scheduling constraints and inexact periodic execution.

No source code from these additional reference projects is copied into this
increment. Existing parser dependencies retain their upstream licenses/notices.
