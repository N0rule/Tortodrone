# Automated releases

GitHub Actions builds Tortodrone with Java 25 and runs `./gradlew --no-daemon build` for pull requests targeting `main`, pushes to `main`, and release tags. The full build includes the project's standalone unit and integration regressions. No game installation, account database or private runtime data is required.

The release workflow packages the output of `buildServer` and `buildAdmin` from the full build:

- `tortodrone.jar` (server)
- `tortodrone-admin.jar` (local administration)
- `content/packs/vanilla/` (versioned stock definitions)
- `config/content.properties` and an empty `custom/packs/` directory
- `VERSION`, `README.md`, `docs/` and `START-HERE.txt`

The downloads are a platform-independent ZIP and TAR.GZ, plus `SHA256SUMS.txt`. **Java 25 must be installed separately.** Neither release contains a JRE, credentials, an existing `data/` directory, account databases or private game assets. Running either JAR from the unpacked directory creates/uses its separate persistent `data/` directory; back it up before upgrading. Preserve `config/`, `custom/packs/`, and any legacy resources; see [content migration](content.md).

## Publish a release

1. Ensure the `VERSION` file on `main` contains the intended version, such as `0.26.3`, and the corresponding commit has passed CI.
2. Create and push a tag pointing to the release commit, using the exact version prefixed by `v`:

   ```sh
   git switch main
   git pull --ff-only
   git tag v0.26.3
   git push origin v0.26.3
   ```

3. The tag push triggers the workflow. After the Gradle build and tests pass, GitHub creates the matching Release and attaches both archives and their checksums. Ordinary `main` pushes and pull requests do **not** publish Releases.

To retry publishing an **existing** version tag without creating another tag, open **Actions → Build and release Tortodrone → Run workflow**, select `main`, and enter the existing tag (for example `v0.26.3`). The build checks out that tag and refuses to publish if it does not match `VERSION`. A release that already exists will not be overwritten automatically.

The workflow checks that both runnable JARs and validated versioned content exist before packaging, checks the ZIP/TAR.GZ integrity, and verifies SHA-256 checksums again before publishing. It uses the repository-provided `GITHUB_TOKEN` (Release job only); no personal access token is needed if Actions have permission to write repository contents.

GitHub-hosted runners do not include the owner's installed stock client. CI validates build, packaging, and the repository's automated tests; native stock-client graphical acceptance remains a separate manual check as described in [development](development.md).
