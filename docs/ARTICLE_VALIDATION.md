# Article validation and privacy sessions

The native reader and Context continue to share Darwin’s public-copy resolver.
A stored article can retain its ETag or modification date as metadata, but that
alone no longer authorizes sending it back to a website.

For direct connections, the resolver remembers validators only after a complete
readable body was obtained in the current resolver and Gateway session. A later
conditional read must name the exact HTTP retrieval URI and present matching
validator fields. Records are memory-only, bounded to 128 entries and 256 KiB,
and expire after 30 minutes from the full read. Repeated 304 responses do not
extend that absolute lifetime. A newly created resolver or a cleared article
cache performs an unconditional read before resuming conditional validation.

Tor reads neither retain nor replay these validators. Tor circuits can change
without changing the local SOCKS port or application policy; a policy-bound ETag
would still be an identifier across those circuits. Existing bounded article
memoization can reuse already acquired bodies without another request. This
choice does not add polling or change the reader’s update schedule.

NetworkGateway gives each effective network configuration a memory-only session
identity. Direct → Tor → direct creates a new session despite returning to equal
policy values. Reapplying the same configuration is a no-op. Preparing a separate
Tor search connection does not invalidate direct content. Requests waiting for
admission retain their original session, and stale clients/responses cannot
become valid again after a configuration round trip. A stale Gateway operation
reports coroutine cancellation, so it cannot train an unavailable-source result.
Switching content mode also retains an already warm, unchanged forced-Tor search
client; only the lanes whose effective configuration changed are replaced.

Cache validators are stripped when cleaning/filtering changes the request URI
and on every HTTP redirect, including redirects within one origin. A 304 can
certify a retained article only when the final request actually carried approved
validators and returned from the same retrieval URI. Redirecting away and back
to the initial URI cannot manufacture an unchanged result.

Clearing ordinary article memory also clears validator bindings. Extraction that
started before the clear can finish for its caller, but cannot repopulate the
cleared article memo or validator record. Saved articles, offline copies, source
attribution and original URLs are unchanged. No AI is required.

The underlying risk and redirect behavior are documented in
[RFC 9110 §17.14](https://www.rfc-editor.org/rfc/rfc9110.html#section-17.14) and
[§15.4](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.4). Entity tags can
serve as persistent re-identifiers; validators for one representation should
not be forwarded as cache state for a redirect target.

Qualification details and device/source limits are recorded in
[release qualification](RELEASE_QUALIFICATION.md). This is not a claim of Tor
circuit monitoring or protection against every form of website fingerprinting.
