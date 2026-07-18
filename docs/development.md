# Development and release

Requirements: Windows, Java 21, Maven 3.9+, and Git.

```powershell
mvn -B -ntp clean verify
git diff --check
```

Package the CLI:

```powershell
mvn -B -ntp -pl clawkit-cli -am package -DskipTests
.\clawkit.cmd --version
```

CI runs the full Maven reactor on `windows-latest`; Docker smoke tests run the Linux image used by Docker Desktop. Release tags must exactly match the Maven version, for example tag `v0.1.0` for project version `0.1.0`. A release publishes the runnable JAR, a Windows ZIP containing `clawkit.jar` and `clawkit.cmd`, and `SHA256SUMS.txt`. Published assets are not overwritten; rollback means using the previous release and publishing a new patch version.
