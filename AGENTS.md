# AutoGamble release practice

When working on a new AutoGamble version, preserve the currently published version before calling the new version released.

- Inspect the current GitHub release, its tag, and the existing `releases/archive/<version>/` layout first.
- Put the exact outgoing release mod JAR in `releases/archive/<old-version>/`. Verify its SHA-256 against the GitHub release asset digest when available; do not substitute a rebuild for the published binary.
- Put a matching `autogamble-<old-version>-sources.jar` in the same folder. If GitHub has no sources asset, build it from the outgoing version's tag in a separate worktree.
- Do not overwrite or delete an older archive or GitHub release. If a destination exists with different bytes, stop and investigate.
- Confirm both archived JARs exist before publishing the new version's GitHub release. The local archive is intentionally ignored by Git; the old GitHub release stays available.
- Build and test the new version, then publish its versioned JAR, tag, and release notes. Keep the project version and visible in-game version synchronized.

See [docs/RELEASING.md](docs/RELEASING.md) for the release checklist.
