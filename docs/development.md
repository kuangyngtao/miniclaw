# Development and release

Requirements: Windows, Java 21, Maven 3.9+, and Git.

## Build and test

```powershell
mvn -B -ntp clean verify
git diff --check
```

Package the CLI:

```powershell
mvn -B -ntp -pl clawkit-cli -am package -DskipTests
.\clawkit.cmd --version
```

## Release process

CI runs the full Maven reactor on `windows-latest`; Docker smoke tests run the Linux image used by Docker Desktop.

Release tags must exactly match the Maven version, for example tag `v0.1.0` for project version `0.1.0`.

### Local pre-flight

Before pushing a release tag:

```powershell
# 1. Verify all tests pass
mvn -B -ntp clean verify

# 2. Package and smoke locally
.\scripts\package-release.ps1 -Version "0.1.0"

# 3. Docker smoke (Windows Docker Desktop)
docker build -t clawkit:0.1.0-rc .
docker run --rm clawkit:0.1.0-rc --help
docker run --rm clawkit:0.1.0-rc --version
```

### Release workflow

1. Push commits → wait for CI and CodeQL to pass.
2. Complete Windows Terminal `docker run --rm -it` manual smoke.
3. Create annotated tag: `git tag -a v0.1.0 -m "clawkit v0.1.0"`
4. Push the single tag: `git push origin v0.1.0`
5. The Release workflow will:
   - Validate tag matches POM version.
   - Run `mvn clean verify`.
   - Call `scripts/package-release.ps1` for unified packaging and smoke.
   - Create a draft release with auto-generated notes.
   - Upload JAR, Windows ZIP, and SHA256SUMS.txt.
   - Publish the release only after all assets are uploaded.
6. Download the public release assets and verify SHA-256.
7. Run `--version` from the downloaded JAR and ZIP.

Published assets are not overwritten. If a fix is needed after `v0.1.0` is public, publish `v0.1.1`.
