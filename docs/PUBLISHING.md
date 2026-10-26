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
| Business search | `antisocialBusinessCases`: query objects with optional `uniqueName`, `uniqueAddress` and `excludedAccount` expectations |
| Location menu | `antisocialMenuBusiness`, `antisocialMenuItem`, `antisocialMenuPath` |
| Embedded-text menu PDF | `antisocialMenuPdfBusiness`, `antisocialMenuPdfSuffix` |
| Published brand slice | `antisocialBrandBusiness` |
| Calendar/page reconciliation | `antisocialEventCalendarUrl`, `antisocialEventEditions`: URL/title objects |
| Business social links | `antisocialSocialBusiness`, `antisocialExpectedAccounts`: URL strings |
| Venue resolution | `antisocialVenueName`, `antisocialVenueAddress`, `antisocialVenueLocality` |

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
