# Releasing AutoGamble

1. Identify the current public GitHub release and its tag. Check that the working tree is clean and that the new version number is unused.
2. Before publishing a new release, archive the outgoing version in `releases/archive/<outgoing-version>/`:
   - Download the exact outgoing mod JAR from its existing GitHub release. Compare its SHA-256 with the release asset digest when available.
   - Archive the corresponding sources JAR. Build it from the outgoing tag in a separate worktree if it was not attached to that release.
   - Preserve existing archive contents; investigate any hash mismatch rather than overwriting.
3. Update `gradle.properties`, displayed version strings, documentation, and release notes for the new version.
4. Run `gradlew.bat clean test build` (or the platform's wrapper equivalent), inspect the test totals and generated JAR, and verify its version metadata.
5. Commit, tag, and push the new version. Publish a non-draft GitHub release with the verified `build/libs/autogamble-<new-version>.jar` attached.
6. Verify the public release page and asset, sync the named local checkout, and retain the previous GitHub release and tag unchanged.

`releases/archive/` is deliberately ignored by Git and holds local copies of historical builds. `build.gradle` also archives older artifacts already present in `build/libs/` before a clean/build. That build task does **not** replace the release-time archive check against the published GitHub asset.
