# Public scientific route leads, October 24, 2026

An explicit NetworkGateway audit queried the exact DOI
`10.1038/s41586-024-07487-w`, without an account, credential or browser.
These are dated observations from one direct connection, not service-wide access
or latency guarantees.

| Route | HTTP result | Delivered text bytes | Elapsed | Observation |
| --- | --- | ---: | ---: | --- |
| OpenAlex exact work | 200 | 4,512 | 1,164 ms | Exact DOI and available public-copy locations |
| Europe PMC exact DOI search | 200 | 18,767 | 1,165 ms | Exact DOI, PMID 38718835 and PMCID PMC11168924; open-access HTML/PDF locations |
| Crossref exact work | 200 | 36,168 | 308 ms | Publisher-deposited HTML/PDF links, content type and version |
| Europe PMC fullTextXML | 200 | 174,359 | 2,012 ms | JATS front matter identifies the same DOI/PMCID; body contains 86 paragraphs and 61,733 text characters |

The XML route was research-only in 0.38.5. The 0.38.6 implementation adds bounded
JATS extraction in the existing isolated document worker, exact front-matter
identity checks, retained sections/tables/citations and comparison against the
captured full paper; see [scientific full text](SCIENTIFIC_FULL_TEXT.md). An abstract
or a metadata record cannot stand in for the complete paper. The same paper
syndicated by several services is one underlying source, not independent evidence.

Crossref link declarations do not establish that a destination is currently
readable. The [official schema](https://github.com/CrossRef/rest-api-doc/blob/master/api_format.md)
describes the full-text links and edition fields. Europe PMC documents the
[open-access fullTextXML endpoint](https://europepmc.org/RestfulWebService).
The existing resolver already consumes OpenAlex and Europe PMC metadata;
Crossref full-text links and JATS bodies are further candidate routes.

Raw research responses and structure checks remain on the external build volume.
The probes themselves did not add an automatic background request. The qualified
route is used during an existing article lookup, with no new polling schedule.

## Further Gateway checks, October 24, 2026

These direct checks use the same exact AlphaFold 3 DOI/PMC identifier above,
without credentials or a browser. They do not establish provider-wide uptime,
phone latency or independent corroboration of the paper.

| Route | HTTP result | Delivered bytes | Elapsed | Observation |
| --- | --- | ---: | ---: | --- |
| NCBI PMC HTML | 200 | 291,051 | 1,520 ms | Native extraction supplies the exact title/DOI and 77,392 readable characters |
| Europe PMC HTML | 200 | 28,844 | 1,478 ms | Redirects to `/article/PMC/11168924`; application shell, rejected as lacking substantial article text |
| NCBI BioC JSON | 200 | 125,543 | 1,626 ms | Exact PMC ID and DOI; 192 typed passages, including paragraphs, figures and references |
| PMC OAI-PMH exact record | 200 | 171,391 | 1,656 ms | Exact record identifier, primary PMC/PMID/DOI and versioned PMC ID inside a JATS record |

The last two are research leads, not activated routes in 0.38.7. The
[official BioC documentation](https://www.ncbi.nlm.nih.gov/research/bionlp/APIs/BioC-PMC/)
describes full-text XML/JSON for the supported collections; the
[PMC developer guide](https://pmc.ncbi.nlm.nih.gov/tools/developers/)
lists BioC and OAI-PMH. A separate BioC PubMed endpoint supplies abstracts and
must not be confused with PMC full text.

The OAI record retains all 79 substantial reference-body paragraphs when comparing
across the whole article. A first body-only comparison counted 57 because figures,
references and other material can live in different containers. Its adapter must
validate the record header and current article, handle errors/deletions and read
body, back matter and floating material. BioC changes text/markup representation;
strict contiguous paragraph matching finds 52 of 79 source paragraphs. That is not
proof that 27 sets of facts are absent. Inspect equations, citations, figures and
supplements before choosing its role or making a completeness claim. Its smaller,
already segmented payload could reduce extraction work if retention qualifies.

Both formats still require bounded parsing, exact current-record identity,
source/edition/rights attribution, Android qualification and ordinary privacy/
health rules before activation. Raw papers remain on the external evidence volume.
