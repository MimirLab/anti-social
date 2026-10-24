# Scientific full text, 0.38.6

The native article reader and Context share Darwin's public-copy resolver. It can
now read Europe PMC's structured JATS article body, alongside its existing HTML
and public PDF candidates. This requires no language model or extra download.

An exact PMC article link goes straight to the corresponding `fullTextXML`
endpoint. A DOI or PubMed lookup can also discover a PMC copy; XML is tried before
that copy's HTML. Already working non-PMC HTML still avoids another metadata
lookup. Failed XML falls through to existing HTML/PDF/publisher/archive routes,
subject to the original-access setting, request allowances and selected Tor
policy. The full-text capability has its own health lane, so its failure does
not put the same host's metadata service into backoff.

Only the recognized HTTPS Europe PMC endpoint is accepted as structured text.
Its article-level PMC identifier must match the request. An original DOI or
PubMed identifier must match the front matter too. When a DOI came from a
reviewed publisher's metadata, the resolver checks it after extraction. A cited
paper's identifier cannot substitute for the current article's identifier.
Abstract-only, malformed, conflicting and wrong-identity copies are rejected.

The trusted app fetches through NetworkGateway and passes only a read-only
descriptor to the existing permissionless document worker. SAX events build a
bounded inert tree: at most 4 Mi characters, 30,000 elements, 96 nested elements,
64 attributes per element and 8,192 characters per attribute. Entity declarations
are refused before parsing; the mandatory entity resolver returns no external
content. Optional platform parser features are supplementary defenses, rather
than compatibility requirements. Stylesheets, scripts, DTD resources and media
are never fetched or executed by extraction. Existing worker deadlines, validated
output, scratch cleanup and the 240,000-character reading bound still apply.

Sections, paragraphs, ordered lists, code, captions, simple labelled tables,
footnotes, supplementary-file descriptions and bibliographic passages survive
as native reading blocks. References may occur in the body or back matter;
typed bare DOI/PMC/PubMed identifiers become authentic destinations. Up to 12
selected citations join the existing bounded source-following pipeline. A query
string supplied as a Google Scholar citation is not manufactured into a URL.

TeX alternatives and common MathML constructs get readable text notation. This
is not universal equation rendering. Figure and image-only table captions retain
their source anchors; the app does not claim to read those pixels. Supplementary
documents are described and linked back to their source section, without an
automatic download. Nested sub-articles/responses are omitted and the result is
marked incomplete. Incomplete publication dates stay unknown. An XML copy does
not inherit another repository location's edition or license; its own published
license link is retained when present.

The [JATS article model](https://jats.nlm.nih.gov/publishing/tag-library/1.4/element/article.html)
allows body/back/floating material and nested articles. Europe PMC documents the
[open-access full-text endpoint](https://europepmc.org/RestfulWebService).
Availability and schema details remain source dependent. The same syndicated
paper is one underlying work, not independent corroboration.

The external retention audit uses the previously captured public AlphaFold 3
paper, DOI `10.1038/s41586-024-07487-w`. It preserves all 79 body paragraphs over
60 characters under punctuation/whitespace normalization, produces 200 bounded
blocks with 76,654 text characters, and exposes 12 selected citations. That is a
retention measure on one paper, not a semantic-quality or all-JATS guarantee.
The complete published fixture stays outside the repository and APK. Synthetic
fixtures cover adversarial parsing, identity and native presentation. See
[release qualification](RELEASE_QUALIFICATION.md) for final checks and limits.

## PMC paths and canonical addresses, 0.38.7

Both Europe PMC's older `/articles/PMC…` address and its current
`/article/PMC/…` address provide the same stable PMC identifier to routing. The
structured endpoint is tried first. If it fails, known NCBI and Europe PMC HTML
paths can be tried without another search. NCBI's readable full text precedes
Europe PMC's HTML by default; an explicitly discovered location keeps its own
metadata when that provider is reached. Edition and license claims are not
transferred to a different provider.

Recognized aliases on the same HTML provider remain the original source for
access-policy purposes. They do not become a workaround for disabling original
access. Cross-provider copies stay separate routes, and exact HTTP retrieval
URIs still control conditional validators. Archive matching retains its existing
URL rules. All candidates must still deliver a substantial readable body.

In the dated Gateway check, NCBI delivered the full paper while Europe PMC's HTML
redirected to a JavaScript application shell. The shell was rejected; HTTP 200
was not recorded as useful content. Recognizing that shell's canonical URL still
helps: an incoming link can go directly to the structured API instead of needing
the page's scripts. See [route research](ARTICLE_ROUTE_RESEARCH.md).
