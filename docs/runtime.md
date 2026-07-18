# Runtime

clawkit is an interactive Windows CLI using Java 21. Its working directory is the current directory unless `--root` is supplied.

Permission modes:

- `PLAN`: read-only tools; generated plans may be written to `.clawkit/plan.md` by the controlled runtime path.
- `ASK`: write and command tools require approval.
- `AUTO`: tools run without interactive approval but still pass safety policy and auditing.

Sessions, memory, logs, and run traces live under `%USERPROFILE%\.clawkit`. Use `/runs`, `/metrics`, and `/trace` to inspect recorded runs.

The Docker image is a Linux container intended for Docker Desktop on Windows. Interactive use requires `docker run -it`; background IM bot operation is not part of the current Docker contract.
