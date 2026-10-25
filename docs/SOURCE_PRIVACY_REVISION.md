# Release source privacy revision

The 0.38.34 source tag and corresponding source download received a privacy
revision affecting documentation and two test fixtures. Identifying reproduction
details were removed; tests use neutral inputs or privately supplied arguments.

This is not an application rebuild. All application runtime sources, assets,
build scripts, native components and upstream source archives are unchanged.
The signed APKs and their published SHA-256 checksums remain unchanged. The
APK's embedded revision identifies its original build, before these non-runtime
source edits. The revised source tag/archive therefore has a different revision
and archive digest. Original provenance and recovery records are retained
privately. Subsequent builds use their ordinary exact source revision checks.

This revision removes the newly introduced disclosure from the maintained
source refs and download. It does not establish deletion of previously fetched
copies, unreferenced server objects or unrelated historical geographic fixtures.
