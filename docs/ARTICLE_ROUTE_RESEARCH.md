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

The XML route is a promising additional body source when HTML delivery fails.
It is research-only in 0.38.5. Activation needs bounded JATS extraction in the
existing isolated document worker, exact front-matter identity checks, retained
sections/tables/citations and comparison against current extraction. An abstract
or a metadata record must not be accepted as the complete paper. The same paper
syndicated by several services is one underlying source, not independent evidence.

Crossref link declarations do not establish that a destination is currently
readable. The [official schema](https://github.com/CrossRef/rest-api-doc/blob/master/api_format.md)
describes the full-text links and edition fields. Europe PMC documents the
[open-access fullTextXML endpoint](https://europepmc.org/RestfulWebService).
The existing resolver already consumes OpenAlex and Europe PMC metadata;
Crossref full-text links and JATS bodies are further candidate routes.

Raw research responses and structure checks remain on the external build volume.
No route or automatic background request was added from these probes.
