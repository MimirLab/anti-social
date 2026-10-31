# Publishing anti social

The permanent Android download is:

https://github.com/MimirLab/anti-social/releases/latest/download/anti-social.apk

For Obsidian:

```markdown
[Download / update anti social](https://github.com/MimirLab/anti-social/releases/latest/download/anti-social.apk)
```

GitHub redirects this URL to `anti-social.apk` on the release designated Latest.
It does not install updates automatically. Opening the bookmark downloads the
current published APK; Android performs the installation after user approval.
The fixed asset name must be present on every release. Drafts and prereleases
are excluded from GitHub's Latest mechanism.

## Release procedure

Personal reproduction details stay private. Release notes, documentation, test
fixtures, screenshots and source archives must not disclose a reader's location,
routine, saved material or other details learned in conversation. Use synthetic
or unrelated examples, accept real test locations privately at execution time,
and keep identifying validation evidence in the private external build area.
Review the rendered release body and corresponding-source changes for these
details before uploading; a public business address can still reveal a reader's
location when used as their reproduction example.

1. Build, test and package the app using the existing external build root. Keep
   the application ID and signing key compatible with previous installed builds.
2. Commit and tag the matching source. Upload the versioned APK and full
   corresponding source ZIP to its GitHub release. Use a draft until ready.
3. Run `python3 scripts/publish_release.py vVERSION` with GitHub CLI authenticated
   and available on PATH (or set `GH_CLI`). The helper verifies local APK bytes
   against the versioned release asset, requires matching corresponding source,
   uploads the fixed-name alias, verifies its digest, updates `SHA256SUMS`, then
   publishes and marks the release Latest. `--dry-run` only inspects the plan.
4. Verify the permanent URL anonymously before reporting publication complete.

Before packaging, inspect `META-INF/version-control-info.textproto` inside the
release APK and require its revision to match the clean source commit being
tagged. A successful assembly alone does not establish this correspondence.
AGP 9.1.1's revision task reads loose branch refs but does not resolve packed
branch refs: a healthy checkout can therefore produce `NO_VALID_GIT_FOUND`.
In that case, save the branch name, detach at the exact same clean commit,
assemble with `scripts/gradle.sh`, and restore the saved branch even if the
build fails. Verify the embedded revision again. Do not patch the APK metadata
or move the branch to obtain a stamp.

For checks involving private reader reproduction details, run
`scripts/audit_public_fixtures.py` against the working tree and the final source
ZIP, with `--denylist-file` and `--report` paths outside the checkout. The private
JSON denylist is never a source fixture. Output contains counts; the detailed
report contains file/line/rule indexes, without matching values or source lines.
For a ZIP, pass its exact top-level directory through `--zip-prefix`. A reviewed
public upstream notice can use `--reviewed-hashes` with its explicit SHA-256;
changed bytes automatically lose that exemption. Preserve upstream notices and
verify unchanged compressed corresponding sources separately. The guard checks
known reproduction strings and supplements the manual source review.

Opt-in regional live checks use the complete `LocalArea` JSON argument
`antisocialLiveArea`, alongside their existing opt-in/isolation flags. Specific
checks also require private inputs:

| Check | Arguments |
| --- | --- |
| Business search | `antisocialBusinessCases`: query objects with optional `uniqueName`, `uniqueAddress`, `excludedAccount` and `groupedName` expectations |
| Location menu | `antisocialMenuBusiness`, `antisocialMenuItem`, `antisocialMenuPath` |
| Embedded-text menu PDF | `antisocialMenuPdfBusiness`, `antisocialMenuPdfSuffix` |
| Published brand slice | `antisocialBrandBusiness` |
| Calendar/page reconciliation | `antisocialEventCalendarUrl`, `antisocialEventEditions`: URL/title objects |
| Business social links | `antisocialSocialBusiness`, `antisocialExpectedAccounts`: URL strings |
| Venue resolution | `antisocialVenueName`, `antisocialVenueAddress`, `antisocialVenueLocality` |

`groupedName` requires multiple retrieved locations of that chain and exactly
one reading card. Live reports record visible cards and physical locations
separately. The optional JVM `LocalBusinessPacketTest` accepts a recorded business
JSON array through `ANTISOCIAL_PRIVATE_BUSINESS_PACKET` and the expected name
through `ANTISOCIAL_PRIVATE_CHAIN_NAME`. Keep both inputs outside the checkout;
normal unit runs skip this case. The replay preserves all location facts and
checks that unrelated records are not absorbed. Never commit the recorded packet.
`ANTISOCIAL_PRIVATE_BUSINESS_AREA` optionally supplies the complete `LocalArea`
JSON for country and branch-geography checks in the same replay.

The optional JVM `CrossPostPacketTest` accepts a captured input through
`ANTISOCIAL_PRIVATE_CROSSPOST_PACKET`: `posts`, `follows`, `memberships` and
`expectedGroups`, optionally `selectedOriginals` and `oncePassages`. It can replay
a selected case within a larger captured corpus and check that shared passages
are rendered once. The packet remains outside the checkout; normal runs skip it.
Opt-in `ReaderRoutingLiveTest` and `ReaderSearchLiveNativeTest` likewise require an
explicit private `files/reader-routing-live.json` on an isolated test device, plus
their named live/isolation flags. They record dated production-route and actual
Search-tab behavior without embedding an owner's queries or reader state in source.
For a source-specific routing observation, optional `sourceFrontend` disambiguates
multiple installed methods sharing `sourceRoot`; both must match the existing
source inventory. Omitting `sourceRoot` exercises the normal production pool.
For a deeper private feed capture, `feedWindowPages` (one through four) uses
the existing `FeedCatchUp` and source-owned `continuePage` path, with at most
100 posts and a 90-second window inside the target deadline. It records the
page count and pending reason. This diagnostic scope does not change ordinary
refresh allowances or turn a limited public snapshot into exhausted history.

`CollectedMediaPacketTest` additionally accepts an explicit private
`files/collected-media-packet/input.json` with up to eight cases, each containing
`posts`, optional `expectedAttachments` and an optional URL-to-local-filename
`files` map. Both `antisocialCollectedMediaPacket=true` and
`antisocialIsolatedFixtureDevice=true` are required. It decodes supplied files or
reads through the production Gateway, then records the observed attachment
groups in private external device evidence. These are opt-in engineering checks;
normal regression runs skip the packet and no captured reader data ships.
Optional per-case `imageEdge` (64–1600, default 1600) compares the actual decoder
resolutions. `refreshedPosts` can supply exactly the same original identities
after those reads, allowing a signed-URL refresh to be replayed against earlier
pixel observations. Read receipts retain the URLs actually inspected; this
diagnostic does not authenticate replacement bytes or download them implicitly.

The optional native `FusionFeedTest.suppliedPrivateEditionsRenderOneCardAndOneCommonCaptionPerAnnouncement`
accepts `files/fusion-private-packet/input.json` only when both
`antisocialCollectedFeedPacket=true` and
`antisocialIsolatedFixtureDevice=true` are supplied. It requires an empty,
separate fixture device. The private packet contains `follows`, `memberships`,
up to eight `cases` with `posts` and a shared `once` passage, and an optional
URL-to-local-filename `files` map. Cases can specify `expectedAttachments`.
It uses the real native feed, actual local-byte decoding, continuous scrolling,
individual card screenshots and Library saves. Reports check one reading row,
one shared passage, original retention and saved-root retention. Temporary cache
rows and fixture records are restored or removed after the check. Captured reader
inputs, images and reports stay in the private external evidence area.
Optional `backgroundPosts` supplies up to 512 additional image-only, dated posts
with their explicit memberships. The focused cases retain their 64-original
total allowance. Background posts participate in the actual full feed and its
grouping but do not become extra screenshot targets or saved case originals.
The check additionally requires the complete context to conserve every original
exactly once. This supports crowded-feed counterexamples without altering the
captured target's text, pictures or ownership bindings. It remains an opt-in
engineering input on a separate fixture device, not a shipped reader dataset.

Business arguments contain the initial name, address, locality, optional point,
websites, socials, phones and source IDs needed by that check. Missing required
inputs fail before the corresponding lookup; there is no personal-location or
business fallback. Retain the original per-case expectations when migrating a
previous private benchmark. These are engineering test arguments, not user-facing
installation or runtime requirements.


The helper defaults to `MimirLab/anti-social` and the existing external artifact
directory. `--apk` can select another locally packaged APK. `GH_CONFIG_DIR` is
inherited; credentials are never embedded in the source or release assets.
The ordinary package script retains its versioned artifact names for recovery
and source correspondence. Published versioned assets and source tags stay intact.

The initial official release, 0.35.45, uses the existing development signing key
and is non-debuggable. Publication does not change that key or retroactively
qualify physical-phone GPU behavior. Those limits remain documented in the
release notes and validation record.


`FullFeedVideoLiveTest` additionally accepts a private captured followed window
through external `validation/private-captured-video-plan.json`, with `posts`,
`follows`, exact `memberships` and `focusKeys`. It requires an empty disposable
installation, `antisocialIsolatedFixtureDevice=true` and
`antisocialFullFeedVideoLive=true`. It mounts `AntiSocialApp`, imports only those
explicitly supplied records, restores the target anchor and observes the shipping
visible-card dwell and Gateway sampler. It never fabricates scene evidence.
First-card, actual-sample and visible-collection times are separate events.
Supplying arbitrary cached records without follow memberships is rejected;
source refusal and resource-policy denial are not successful collection results.
The input, screenshots and detailed events remain private outside the checkout.

`FullFeedGalleryLiveTest` requires both `antisocialIsolatedFixtureDevice=true`
and `antisocialFullFeedGalleryLive=true`, an empty disposable installation and
private `files/full-gallery-private-packet/input.json`. It imports a captured
followed window with exact memberships and initial `focusKeys`; `rootKey`,
`expectedChildUrl`, `expectedOriginals` and `expectedPictures` declare expectations.
The expected child must be absent from the input. Optional `files` restore
genuine initial-edition image bytes; ambiguous CDN aliases are rejected. The
shipping decoder derives fingerprints, while the real feed dwell, Gateway and
routing acquire the missing child. Assertions cover parent provenance, exclusive
author membership, one complete visible family and distinct-picture retention.
No child metadata or comparison evidence is injected. Inputs and native evidence
remain private; the test does not run without both opt-in flags.


The optional full-video flag `antisocialFeedVerifyAdoptedBody=true` additionally
requires private `expectedBodyPassages` in the captured plan. It opens the real
captured-details disclosure, adopts its body and checks each required literal
variant exactly once while retaining the card's pixel position. Source-count
adoption alone does not pass this stronger check. A caption screenshot is retained
separately from the top of the card.

The optional `antisocialFeedObserveSampleTail=true` flag keeps the same card
under observation for three seconds after body adoption. Its final event records
started recordings and candidates outside the packet's declared focus. Such a
candidate can still reveal another useful edition: this is a timing observation,
not permission to prune that discovery path or reduce the existing allowance.

Older retained APK/tester pairs support the engineering-only
`antisocialFeedExperimentEarlyVideo=true` experiment. The current tester rejects
that flag: it observes production prefix preparation and viewport-gated frame
sampling without scheduling an extra comparison itself. Private inputs may set
`minimumObservedRecordings` (one through four, default three) to match the actual
number of recording representatives. This changes the observation milestone,
not the required complete family, literal-body or pixel-anchor assertions.
Actual acquired image/video observations are retained privately on failure too.
`VideoRetryLiveTest` separately requires `antisocialVideoRetryLive=true`, the
isolation flag, an empty disposable installation and the same private captured
plan. It uses an explicitly supplied focused public recording, a one-byte
foreground Gateway read and the actual isolated decoder to exercise preemption
and validated-block reuse. No source input, observation or identity is bundled.

The cached-file gallery restart uses `antisocialFullFeedGalleryCold=true` and
private `files/full-gallery-cold-packet/input.json`; the child must already be
present and every focused picture must have unambiguous retained bytes. It must
perform zero publisher-continuation reads. With
`antisocialFeedVerifyWarmReentry=true`, the same check waits for relevant work to
settle, leaves through Subscriptions, returns through Following and records the
new complete-card time and any matching jobs. Inputs and screenshots remain in
the private external evidence area. These flags qualify engineering journeys;
they do not enable a user-facing import or change ordinary acquisition policy.

`FullFeedGalleryLiveTest.capturedCompleteAlbumsRebuildTheirEvidenceFromRetainedBytesWithoutRefetching`
uses `antisocialFullFeedCompleteAlbums=true`, the isolation flag and private
`files/full-album-cold-packet/input.json`. This separate mode contains already
acquired root originals, rather than treating an album as an invented reply.
It retains the window's `posts`, `follows`, actual `memberships`, `focusKeys`,
`rootKey`, optional `anchorKey`, `files`, `expectedOriginals` and
`expectedPictures`. The legacy-named `expectedChildUrl` selects an existing
focused edition in this mode. `sharedCaption` is required; `expectedFamilyLabel`
can describe a platform-count header instead of a repeated-original-count header.
Optional `stageAllCachedPictures=true` stages matching supplied historical bytes
as well. A missing background file stays missing; staging never downloads it.

The loader starts without injected image fingerprints, with selected-unready Tor
blocking network fallback. The native check requires a complete visible family,
one shared heading, the expected distinct pictures and conservation of every
retained original. The sibling
`capturedCompleteAlbumsKeepTheirProjectionWhileDisplayNamesChange` method also
requires `antisocialFeedVerifyDisplayRename=true`. It edits only temporary local
display labels, requires zero new matching jobs and unchanged picture evidence,
then changes a real account-group binding and requires analysis to restart.
Subscriptions must display the new labels, and returning to Following must retain
All and the complete card. These methods require an empty disposable fixture;
their actual source records, files and screenshots remain private.

When engineering inputs are staged through privileged ADB on an owned emulator,
restore the staged app-private directory's SELinux labels before returning ADB
to the ordinary shell. A matching Linux owner alone does not establish app
access on older Android. Keep SELinux enforcing and verify the file through the
native input guard; never bypass that guard or change app permissions to repair
a diagnostic staging error. This is test setup, not a user-facing requirement.


`NitterLinkCardsNativeTest.suppliedPrivatePreviewSurvivesTheRealCapturedRepresentation`
requires `antisocialPrivateNitterCard=true` and
`antisocialIsolatedFixtureDevice=true`. Its private `files/nitter-card-private`
folder contains an `input.json` identifying the installed source, selected
original, exact authored fields, image, destination and excerpt, plus a bounded
captured HTML file. An optional `before` Page JSON checks every pre-existing
record field, link and attachment against the corrected extraction. Parser CPU
and wall timings are recorded separately from fetch/decoder/display work.
Captured source material and outputs stay outside the public checkout. The
ordinary native batch skips this opt-in source-capture check.

`VideoPostRefreshNativeTest.suppliedStaleRecordingRenewsThroughTheExistingRouterAndJoinsItsMeasuredPeer`
requires `antisocialVideoPostRefreshLive=true`, the isolation flag and an empty
disposable installation. Private `files/video-post-refresh/input.json` supplies
the actual retained `original` and its `peer`. The check uses the existing router
to acquire a whole current edition, measures its own video bytes, verifies the
old file remains unobserved, and exercises protected collection saving. It does
not transplant a replacement URL or inject scene evidence. Inputs, source
records, sample receipts and reports remain private outside the checkout.

For a separate visible-feed journey, `antisocialFeedObserveSourceRefresh=true`
lets `FullFeedVideoLiveTest` observe a qualified whole source edition actually
renewed in ordinary Room cache. The original frozen-packet path remains the
default. The opt-in journey additionally requires an actual source change and
checks that the former file receives no new observation. Existing body-adoption
and pixel-anchor assertions can be combined with it. Screenshot and source
outputs remain private. These are engineering controls, not user requirements.
