AntiSocial local provenance test fixtures (GPL-3.0-or-later, generated for this project).
No public media or personal information is used. Artwork is a 32x24 mathematical
RGB gradient. The test signer is a local, intentionally untrusted certificate
named AntiSocial Fixture Studio, valid October 2026 to October 2036. No private key is
shipped. Fixture Studio is a fictional software agent, not an AI service.

Built with the official Content Authenticity c2patool v0.28.2 (Rust SDK 0.91.2),
https://github.com/contentauth/c2patool/releases/tag/v0.28.2
using local OpenSSL EC P-256 test CA and leaf certificates. No timestamp server,
remote manifest, revocation responder, or external signing service was contacted.
The build had remote_manifest_fetch=false, ocsp_fetch=false and allowed hosts [].

plain.png: no credential or AI declaration.
generated.png: real signed c2pa.created action, IPTC trainedAlgorithmicMedia,
  softwareAgent Fixture Studio 4.2. Valid asset/signature, untrusted signer.
tampered.png: one RGB byte changed and PNG recompressed with a correct CRC after
  signing generated.png. Credential still parses, but the asset hash is invalid.
remote.png: valid PNG with a remote-only reference to a nonexistent loopback
  endpoint. No embedded manifest. The test rewrites its URL to a local canary
  socket and verifies that offline inspection never reaches the host.
redacted.png: generated credential used as a parent; its c2pa.metadata assertion
  (fictional GPS coordinates) is removed using a genuine signed update manifest,
  a redactions URI and a c2pa.redacted action with reason c2pa.PII.present. Original
  AI history remains reachable through the parent ingredient. This is evidence
  of declared metadata redaction, not evidence of an undisclosed AI transformation.

SHA-256:
plain.png 426e615a64ec09fb26bfe6b0593e504859c98aafd6ea88a559cfca50f029cb5d
generated.png f2f240ed83aa089a648b6b8bedaa7ad164195d45e07c8c1a00d358971b9ea3e0
tampered.png 78a151e247ac7e3af6f35157a9c9c042887610aa62ac5598404ccbca125b7e9b
remote.png 634a6c4a06ab1b7e96ef107bcca2670eeea18ea8e439d63e0d8d4014778b27e5
redacted.png 5a5317685ce406eb23835e65b771c7bfbcb19e0e6a71b51789e1ea926c5c8ee3
