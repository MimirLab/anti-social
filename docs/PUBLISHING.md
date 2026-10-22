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

The helper defaults to `MimirLab/anti-social` and the existing external artifact
directory. `--apk` can select another locally packaged APK. `GH_CONFIG_DIR` is
inherited; credentials are never embedded in the source or release assets.
The ordinary package script retains its versioned artifact names for recovery
and source correspondence. Published versioned assets and source tags stay intact.

The initial official release, 0.35.45, uses the existing development signing key
and is non-debuggable. Publication does not change that key or retroactively
qualify physical-phone GPU behavior. Those limits remain documented in the
release notes and validation record.
