# Public Threads keyword search, 0.38.29

The ordinary original-site reader can return native posts from Threads' public
keyword-search page. It uses one bounded HTML request through NetworkGateway,
then reads literal JSON. No browser, site scripts, account, cookies, key, AI or
additional native runtime is required. Darwin's alternate routes still precede
permitted original access; turning that access off excludes this reader. The
selected Tor policy and source pauses remain in effect.

## What the source actually supplied

The [browser-act reference](https://github.com/browser-act/skills/tree/main/solutions/social-listening/threads-keyword-search)
provided a search-container lead. The [OpenCLI investigation](https://github.com/jackwener/OpenCLI/issues/2564)
provided a second route reference. Neither project's browser runtime or code is
bundled. The implementation uses the existing independent Kotlin decoder.

Three bounded Android API 35 Gateway reads on October 25, 2026 returned public
search connections. The reference's `serp_type=recent` and `type=profiles` URLs
returned the same 18-post window, with the source's own preload variables still
declaring `recent: 0` and no account subtab. The separate `filter=recent` attempt
returned 20 result threads but its final URL omitted that parameter and its
metadata still declared `recent: 0`. Both the actual bundled URL cleaner and
compiled request filter preserve that input parameter. These observations do
not qualify a source-side recent sort or an account-directory operation.

The reader therefore accepts ordinary keyword queries, not those unqualified
selectors. It displays the supplied posts chronologically using their actual
publication dates. This orders the returned window; it does not turn that
window into every matching post on Threads. The compact notice says **Limited
public search results**. No continuation is invented, even when a page contains
an unqualified cursor.

## Selection and bounded extraction

Both the selected canonical address and final response must retain the exact
decoded query and supported search path/parameters. The response must stay on
the selected original origin. Literal `expectedPreloaders` metadata must identify
`BarcelonaSearchResultsQuery`, anonymous actor zero and the same query, without
contradictory login, sort, subtab, place or tag qualifications.

Only one explicit result-data root's
`searchResults.edges[].node.thread.thread_items[].post` connection can supply
records. Nested recommendation and quote trees cannot become extra results.
Source post codes, numeric identities and account IDs retain the existing
consistency checks. Complete supplied galleries, links, captions, proper author
names and actual dates survive. An absent date stays unknown. Thread membership
and parent relationships retain the existing narrower evidence requirements;
an unknown parent is not reconstructed from timestamps.

Authors of matching posts do not become matching directory accounts. The search
page therefore leaves the separate people shelf to its existing independent
discovery path. A shared handle or an appearance in search is not account-linkage
evidence.

The decoder retains the existing four-MiB literal-data allowance, 128-script
limit, strict depth-64 JSON, 50,000-node traversals, bounded records/fields and
32-image galleries. Unknown selectors, scopes and cursors stop before network
work. An empty result cannot certify a healthy source; login/challenge responses
and mismatched selections remain failures. No persistent cursor, session bank,
database field or new model is added.

## Qualification and comparison

Four new search regressions fail on unchanged 0.38.28. The owned native search
also fails there with the original reader's unsupported-search error; the
candidate renders its two real protocol-fixture cards in chronological order
after one Gateway GET, with no account state, invented people or further pages.
The focused 23-case suite passes, including the existing profile/reply and
gallery contracts and two controlled routing-deadline cases.

A separate actual SourcePool plus SearchFeed comparison uses the bundled registry,
compiled protection and protected DuckDuckGo fallback. Each run starts a fresh
pool with the same one-page, 24-post, eight-request, 16-MiB limits and waits for
Tor readiness before timing its read. The baseline delivers one full post in
8,436 ms; the candidate delivers 19 in 4,006 ms, with 19 additional keys in this
pair and no source-sort assumption. These are individual changing public windows,
not a performance distribution, exhaustive search, physical-phone measurement or
preview-quota renewal. Initial direct public-page reads are separate observations.

A second query, `laptop battery`, establishes that both form-space and percent-space
encoding return usable 19-post windows; encoding is unchanged. One valid public
page took 4,630 ms, exceeding the legacy four-second original-search shortcut.
A controlled 4,500-ms delivery fails under that shortcut and passes after a
Threads-keyword-search-only allowance of eight seconds direct or sixteen seconds
through Tor. The unrelated Reddit shortcut retains its existing deadline and
still times out in its control case. A separate routed multiword observation
delivered 19 posts in 3,598 ms. The final frozen candidate returned another
19-post multiword window in 4,935 ms with zero unavailable entries and no error,
through its healthy original route. Changing public latency remains source-dependent.

Raw public responses, frozen APKs, failed and passing checks and native screens
remain outside release assets under `evidence/threads-search-20261025`.
Full release qualification is recorded separately in
[the dated release record](RELEASE_QUALIFICATION.md).
