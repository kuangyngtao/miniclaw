# Benchmarks

## Directory structure

```
benchmarks/
  README.md
  baselines/
    runtime-v1.json              # CI regression gate (committed)
  evidence/
    ops-0b-pipeline-20260722.json # Historical OPS evidence (committed)
```

## Types of artifacts

### Runtime Regression Baseline (`baselines/runtime-v1.json`)

- 16 deterministic `ScriptedProvider` cases.
- No API key, no network, no Docker.
- Used by CI to detect regressions.
- Duration remains in reports for observation but is not a CI gate because it is machine-dependent.
- Generated explicitly with the `baseline` command shown below.
- **Never** overwritten by `run` or `compare` commands.
- Promotion from candidate requires manual review.

### OPS Historical Pipeline Evidence (`evidence/`)

- Frozen historical results from specific pipeline runs.
- Does not participate in CI.
- Does not represent current version accuracy.

## CLI commands

```powershell
# Run all cases — writes report to target/ only
mvn -pl clawkit-evaluation -am -Pbenchmark verify "-Dbenchmark.mode=run"

# Compare against committed baseline (CI regression gate)
mvn -pl clawkit-evaluation -am -Pbenchmark verify "-Dbenchmark.mode=compare"

# Generate candidate baseline (never overwrites formal baseline)
mvn -pl clawkit-evaluation -am exec:java -Dexec.mainClass=com.clawkit.evaluation.BenchmarkMain -Dexec.args="baseline"

# List cases
mvn -pl clawkit-evaluation -am exec:java -Dexec.mainClass=com.clawkit.evaluation.BenchmarkMain -Dexec.args="list"
```

## Baseline promotion

1. Run `baseline` to generate `runtime-v1.candidate.json`.
2. Review the candidate diff against the current `runtime-v1.json`.
3. Copy to `runtime-v1.json` only after manual review.
4. Commit the new baseline.
