# Signal discovery

anti social uses source discovery to improve the material a reader receives. Darwin
remains the routing donor. These additions do not require another model, paid
account, browser extension, page scripts, or a new background service.

## Delivery

The existing Context pipeline reads the merged post, linked pages, all available
discussion streams, and available media text. It now also preserves useful paths
found inside those pages:

- An article citing a study can lead to that study's readable public copy.
- A report, dataset, filing, or full recording explicitly linked from the article
  can become another source. Linked YouTube recordings enter the existing caption
  reader before any speech transcription is considered.
- An exact DOI can lead to a recorded correction, retraction, expression of
  concern, withdrawal, or reinstatement, and then the actual notice text.
- A Community Note supplied with an X post can lead to its cited material.

These are relationships in retrieved public material. They do not establish that
a linked claim is true, or that several copies are independent confirmations.
The existing relevance and information-contribution review still applies. A new
source only changes the brief when it supplies an accepted finding.

Source selection and acquisition use code, not model-generated tool calls. The
article extractor keeps candidate labels, their surrounding passages, exact
destinations, and the relationship type. Navigation, promotions, generic links,
login paths and self-links do not become recursive tasks. Identical paper aliases,
video IDs and fragment variants share an identity. There are at most two selected
references per article, two levels, and six follow-up reads per Context item.
Up to three exact papers receive publication-record checks; at most two notice
destinations per record enter the shared six-read allowance.

Declared citations work independently of the language of their labels. Practical
documentation, service manuals, specifications and release notes are eligible too.
Repeated links from one passage do not fill the selection. Protein Data Bank
accession DOIs are structural records, not papers: they remain inspectable links
but do not consume automatic reading slots or receive paper-status checks. Long
articles are inspected beyond their early figure links before selection.

Primary post links retain precedence. A first brief can appear before supplemental
sources finish; those sources can then enrich it. A failed follow-up cannot erase
a usable publication. The existing persistent Context streams retain the parent
document, canonical destination, depth, and completed work across interruption
and refresh. Cycles and already queued destinations are ignored. A source that
subsequently appears as a direct post link is promoted to primary work.

Previously extracted HTML articles can be reused by the native reader and other
Context items for 15 minutes. This process-local cache is bounded to eight bodies
and one million text characters, separated by transport and original-fallback
policy. Durable source paths live with their Context evidence, under its existing
expiry, size limits and saved-copy protection. A 304 response reuses the stored
body and its discovered paths; it does not reset the discovery bounds.
Legacy washed bodies receive one new extraction before conditional refresh resumes,
so a successful 304 cannot indefinitely conceal paths the older extractor omitted.
Explicit validators always take precedence over process-local body reuse.

## Public notes and paper notices

[FxEmbed's published v2 schema](https://github.com/FxEmbed/FxEmbed/blob/main/docs/specs/fxtwitter-openapi.json)
and [processor](https://github.com/FxEmbed/FxEmbed/blob/main/packages/atmosphere/src/providers/twitter/processor.ts)
describe a `community_note` containing text and URL facets. anti social preserves this
field on an identity-validated X post without issuing another request just for
notes. It keeps the annotation separate from the post, makes its sources readable,
and supplies its citations to Context. It does not invent a rating status, note
identifier, consensus, or claim to have collected all competing notes. Missing
fields are unknown. A snapshot preserves annotations available when it was saved;
an older snapshot does not inherit a later live annotation.
The optional annotation fields survive native reader exports; old exports omitting
them remain valid. Imports validate their types, size, HTTPS destinations and
binding to the same original post before any write. Imported status remains a
recorded source claim. See [the format](PORTABILITY.md).

The [official Community Notes downloads](https://github.com/twitter/communitynotes/blob/main/documentation/under-the-hood/download-data.md)
contain cumulative notes and rating/status tables, released daily on a best-effort
basis with an additional 48-hour cutoff. This release does not download those bulk
tables to the phone or claim that an embed is an equivalent complete index.

[Crossref](https://www.crossref.org/documentation/retrieve-metadata/retraction-watch/)
includes publisher and Retraction Watch updates. anti social checks the exact work's
`updated-by` relationships, validates the returned DOI and combines repeated
publisher/Retraction Watch entries for the same notice. `update-to` is the reverse
relationship on a notice; it does not retract the notice itself. Notice types,
dates and destinations remain distinct. An empty API record means no update was
listed, not a clean bill of health. Correction/concern coverage is incomplete.
The existing OpenAlex/Europe PMC public-copy routing is reused; a separate
Unpaywall account/contact-email setup is unnecessary for this release.

## Reader interface and secondary source records

No permanent source-analysis toolbar is added. An available Community Note stays
folded until selected. An exact cited paper with a recorded update can display a
small actionable notice. The native article reader exposes selected underlying
sources and retains its existing complete link list.

The post's options and the article's source disclosure can open **Source details**.
This secondary view can inspect a domain's authoritative registration date and
registrar, and explicit `OWNERDOMAIN` / `MANAGERDOMAIN` declarations from ads.txt.
These records do not enter the signal brief or produce a trust score. Registration
describes a domain, not a publisher's founding date. An advertising seller ID or
`DIRECT` account does not establish ownership. Failed/filtered declarations stay
unavailable. Neither sellers.json crawling nor recursive owner searches run.

Registration endpoints come from [IANA's HTTPS RDAP bootstrap](https://data.iana.org/rdap/dns.json),
with exact domain matching under [RFC 9083](https://www.rfc-editor.org/rfc/rfc9083.html).
Owner/manager fields follow [IAB Tech Lab's specification](https://iabtechlab.com/ads-txt/).
The panel fetches only the selected subject and streams available fields as they
arrive. It has no effect on feed order or content admission.
Social-platform infrastructure and DOI resolvers do not become publisher profiles,
and posts without a supported subject do not gain an empty inspection action.

Matching Bluesky public profiles retain their persistent PLC identifier. Optional
Account details show changes declared in the [PLC directory audit log](https://github.com/did-method-plc/did-method-plc/blob/main/website/spec/v0.1/did-plc.md),
excluding nullified branches and key rotations without visible changes. This is
directory history, not an independent cryptographic audit or proof of ownership
of a historical handle today. Mastodon's [verified website fields](https://docs.joinmastodon.org/entities/Account/#field)
are preserved as the account server's verification, with dates. Ambiguous fields
containing multiple URLs do not mark each destination verified.

Supplemental record reads yield to foreground content and coalesce repeated
requests. Explicit reader requests have a separate admission lane, so a quiet
lookup waiting for feed work cannot hold up a requested read. Ordinary evidence is
limited to 256 records / 2 MiB: successful results
expire after 24 hours and incomplete/unavailable checks after 15 minutes. Reading
a record does not renew its expiry. Clear-cache invalidates in-flight writes.
All product requests use NetworkGateway, privacy filters, size limits, deadlines,
and the selected transport. Tor fails closed. No URL, image or page is submitted
to a scan service, archive, reverse-image index, or another public dataset.

## Further source families

The supplied research is a discovery map, not a claim that every service is an
implemented adapter. These are useful next paths when there is an actual matching
task and an inspectable relationship:

| Family | Useful signal | Current treatment |
| --- | --- | --- |
| Filmot, transcript indexes | Find the longer recording behind a quotation/clip | Existing Filmot recovery metadata and caption routing remain; automatic quote-to-recording matches need passage/timestamp validation before attribution. |
| FindYouTubeVideo and archives | Recover unavailable linked video/text | Existing bounded recovery remains; no expansion into a video-platform feed. |
| IRS 990, GLEIF, regulatory records | A documented grant, parent, filing or operational notice for a resolved organization | Requires exact entity resolution and a relevant question; no name-only funding/ownership allegations. |
| Advertising disclosures | Concrete sponsorship relevant to a source | Existing SponsorBlock remains; account analytics or an ad seller match is not added as a blanket credibility signal. |
| Reverse-image pools, cultural reference sources | Fuller images, earlier located appearances, origins with supporting references | Existing explicit image-search actions remain; no automatic uploads or invented earliest-origin claims. |
| 4CAT / circulation indexes | Distinguish repeated source material from independent additions | Current merged-post/source identities remain; a copied URL does not establish causation or coordinated behavior. |
| urlscan, paid lookup products, audience analytics | An explicit investigation of a destination or media history | No automatic submissions, paid dependency, or routine telemetry-style account probing. |

The next adapter should supply missing substantive material through this bounded
pipeline, rather than create another panel of facts unrelated to the reader's task.
