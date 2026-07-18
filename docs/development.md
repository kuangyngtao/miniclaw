# Development and release

Requirements: Windows, Java 21, Maven 3.9+, and Git.

```powershell
mvn -B -ntp clean verify
git diff --check
```

Package the CLI:

```powershell
mvn -B -ntp -pl clawkit-cli -am package -DskipTests
java -jar clawkit-cli/target/clawkit-cli-0.1.0-SNAPSHOT.jar --version
```

CI runs the full Maven reactor on `windows-latest`; Docker smoke tests run the Linux image used by Docker Desktop. Release tags must exactly match a non-SNAPSHOT Maven version, for example tag `v0.1.0` for project version `0.1.0`. Published assets are not overwritten; rollback means using the previous release and publishing a new patch version.
