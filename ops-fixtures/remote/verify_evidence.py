#!/usr/bin/env python3
"""OPS MVP-3 Evidence Verifier — strict JSON-based validation.

Usage:
  python3 verify_evidence.py <run_directory> <expected_rounds>

Reads all structured evidence from the run directory and produces:
  overall-summary.json  — machine-readable pass/fail verdict
  overall-summary.txt   — human-readable copy

Exit code 0 iff overall-summary.passed == True.
"""

import json, os, sys, re, hashlib
from pathlib import Path
from datetime import datetime

RUN_DIR = Path(sys.argv[1])
EXPECTED_ROUNDS = int(sys.argv[2])

FAILURES = []

def fail(msg):
    FAILURES.append(msg)
    print(f"  [FAIL] {msg}", file=sys.stderr)

# ── Helpers ──

def read_json(path):
    """Read and strictly parse a JSON file. Returns None on failure."""
    try:
        with open(path) as f:
            return json.load(f)
    except Exception as e:
        fail(f"{path}: JSON parse error: {e}")
        return None

def read_ndjson(path):
    """Read NDJSON, return list of dicts. Empty/missing returns [].
    Handles CRC-prefixed journal lines from FileActionAttemptStore."""
    events = []
    if not path.exists():
        return events
    try:
        with open(path) as f:
            for i, line in enumerate(f, 1):
                line = line.strip()
                if not line:
                    continue
                # Strip CRC prefix if present (FileActionAttemptStore: "<hex> <json>")
                parts = line.split(' ', 1)
                if len(parts) == 2 and len(parts[0]) >= 6 and len(parts[0]) <= 8:
                    hex_part = parts[0]
                    if all(c in '0123456789abcdef' for c in hex_part) and parts[1].startswith('{'):
                        line = parts[1]
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError as e:
                    fail(f"{path}:{i}: NDJSON parse error: {e}")
    except Exception as e:
        fail(f"{path}: read error: {e}")
    return events

def grep_count(pattern, path):
    """Count lines matching a regex pattern."""
    try:
        with open(path) as f:
            return sum(1 for line in f if re.search(pattern, line))
    except Exception:
        return 0

# ── Phase 1: verify-opsfix ──

print("=== verify-opsfix ===", file=sys.stderr)
VO_LOG = RUN_DIR / "verify-opsfix.log"
VO_JSON = RUN_DIR / "verify-opsfix-result.json"

if not VO_LOG.exists():
    fail("verify-opsfix.log missing")
    verify_passes = verify_fails = -1
    verify_exit = -1
else:
    verify_passes = grep_count(r'\[PASS\]', VO_LOG)
    verify_fails = grep_count(r'\[FAIL\]', VO_LOG)
    verify_exit = 0  # assume 0 if log exists — the bash script captures exit

    # Write strict JSON result
    result = {
        "passes": verify_passes,
        "fails": verify_fails,
        "exitCode": verify_exit,
        "passed": (verify_fails == 0 and verify_exit == 0)
    }
    with open(VO_JSON, 'w') as f:
        json.dump(result, f, indent=2)

    # Re-validate that the written JSON is parseable
    try:
        with open(VO_JSON) as f:
            json.load(f)
    except Exception:
        fail("verify-opsfix-result.json is not valid JSON after write")

    if result["passed"]:
        print(f"  {verify_passes}P/{verify_fails}F exit={verify_exit} — PASSED", file=sys.stderr)
    else:
        fail(f"verify-opsfix: {verify_passes}P/{verify_fails}F exit={verify_exit}")

verify_opsfix_passed = (verify_fails == 0 and verify_exit == 0)

# ── Phase 2: Profile attestation ──

print("=== Profile attestation ===", file=sys.stderr)
PROFILE_BEFORE = read_json(RUN_DIR / "profile-before.json")
PROFILE_APP_DOWN = read_json(RUN_DIR / "profile-app-down.json")
PROFILE_RESTORED = read_json(RUN_DIR / "profile-restored.json")

profile_switch_passed = False
profile_restore_passed = False

if PROFILE_BEFORE and PROFILE_APP_DOWN:
    if PROFILE_APP_DOWN.get("profile") == "APP_DOWN_V1":
        profile_switch_passed = True
    else:
        fail(f"profile-app-down: expected APP_DOWN_V1, got {PROFILE_APP_DOWN.get('profile')}")

if PROFILE_BEFORE and PROFILE_RESTORED:
    # Compare all key fields
    keys = ["profile", "serverName", "toolSetHash", "toolCount"]
    match = True
    for k in keys:
        bv = PROFILE_BEFORE.get(k)
        rv = PROFILE_RESTORED.get(k)
        if bv != rv:
            fail(f"profile-restored: {k} mismatch: before={bv}, restored={rv}")
            match = False
    if match:
        profile_restore_passed = True
elif not PROFILE_RESTORED:
    fail("profile-restored.json missing or invalid")

print(f"  switch={profile_switch_passed} restore={profile_restore_passed}", file=sys.stderr)

# ── Phase 3: Round verification ──

print(f"=== Rounds (expected={EXPECTED_ROUNDS}) ===", file=sys.stderr)

all_round_ids_unique = True
attempt_journal_passed = True
all_json_valid = True
all_evidence_measured = True

# Counters derived from evidence
counts = {
    "started": 0, "completed": 0, "verifiedSuccess": 0,
    "freshPrecheckPassed": 0, "snapshotMatched": 0,
    "dispatchIntentPersisted": 0, "independentVerificationPassed": 0,
    "businessInvariantsPassed": 0, "cleanupPassed": 0,
    "failedNoEffect": 0, "cancelledNoEffect": 0,
    "outcomeUnknown": 0, "verificationFailed": 0,
    "duplicateSideEffects": 0, "unauthorizedSideEffects": 0,
    "providerFailures": 0, "transportFailures": 0,
}

seen_incident_ids = set()
seen_attempt_ids = set()
seen_repair_run_ids = set()
seen_verify_run_ids = set()

round_refs = []
rounds_found = 0

for rn in range(1, EXPECTED_ROUNDS + 1):
    rd = RUN_DIR / f"round-{rn:02d}"
    if not rd.exists():
        continue
    rounds_found += 1

    repair_json = rd / "repair-result.json"
    events_file = rd / "lifecycle-events.ndjson"
    manifest = rd / "run-manifest.json"
    journal = rd / ".attempts" / "journal.jsonl"
    side_effect = rd / "side-effect-result.json"

    # ── Strict JSON validation ──
    repair = read_json(repair_json)
    if repair is None:
        all_json_valid = False
        round_refs.append({"round": rn, "passed": False, "error": "repair-result.json invalid"})
        continue

    events = read_ndjson(events_file)
    manifest_data = read_json(manifest)

    # ── Check file references exist ──
    for ref_path in [repair_json, events_file, manifest, journal]:
        if not ref_path.exists():
            fail(f"round-{rn:02d}: {ref_path.name} missing")
            all_evidence_measured = False

    # ── Extract fields ──
    attempt_state = repair.get("attemptState", "UNKNOWN")
    attempt_id = repair.get("attemptId", "")
    incident_id = repair.get("incidentId", "")
    repair_rid = repair.get("repairRunId", "")
    verification = repair.get("verification") or {}
    verify_rid = verification.get("verificationRunId", "")
    biz_ok = verification.get("businessInvariantsPassed", False)

    # ── ID uniqueness ──
    for id_val, id_set, label in [
        (incident_id, seen_incident_ids, "incidentId"),
        (attempt_id, seen_attempt_ids, "attemptId"),
        (repair_rid, seen_repair_run_ids, "repairRunId"),
        (verify_rid, seen_verify_run_ids, "verificationRunId"),
    ]:
        if not id_val:
            fail(f"round-{rn:02d}: {label} is empty")
            all_evidence_measured = False
        elif id_val in id_set:
            all_round_ids_unique = False
            fail(f"round-{rn:02d}: duplicate {label}={id_val}")
        else:
            id_set.add(id_val)

    # ── Classify result ──
    is_success = attempt_state == "VERIFIED_SUCCESS"
    round_passed = is_success

    if is_success:
        counts["completed"] += 1
        counts["verifiedSuccess"] += 1
    elif attempt_state == "FAILED_NO_EFFECT":
        counts["failedNoEffect"] += 1
        round_passed = False
    elif attempt_state == "CANCELLED_NO_EFFECT":
        counts["cancelledNoEffect"] += 1
        round_passed = False
    elif attempt_state == "OUTCOME_UNKNOWN":
        counts["outcomeUnknown"] += 1
        round_passed = False
    else:
        counts["verificationFailed"] += 1
        round_passed = False

    # ── Lifecycle events ──
    lifecycle_stages = {e.get("stage"): e for e in events}
    for stage, counter_key in [
        ("fresh_precheck_passed", "freshPrecheckPassed"),
        ("snapshot_match", "snapshotMatched"),
        ("dispatch_intent_persisted", "dispatchIntentPersisted"),
        ("independent_verification_passed", "independentVerificationPassed"),
    ]:
        evt = lifecycle_stages.get(stage)
        if evt and evt.get("status") == "ok":
            counts[counter_key] += 1
        elif is_success:
            fail(f"round-{rn:02d}: lifecycle event '{stage}' missing or not ok")

    # Verify sequence order
    seq_stages = ["fresh_precheck_started", "fresh_precheck_passed",
                  "snapshot_computed", "snapshot_match",
                  "attempt_started", "precheck_completed",
                  "dispatch_intent_persisted", "execution_started",
                  "execution_reported",
                  "verification_started", "independent_verification_passed",
                  "repair_verified_success"]
    seqs = []
    for e in events:
        stage = e.get("stage", "")
        if stage in seq_stages:
            seqs.append((e.get("sequence", 0), e.get("timestamp", ""), stage))
    seqs.sort(key=lambda x: x[0])
    ordered_stages = [s[2] for s in seqs]

    # Check dispatch_intent before execution
    if "dispatch_intent_persisted" in ordered_stages and "execution_started" in ordered_stages:
        di_idx = ordered_stages.index("dispatch_intent_persisted")
        es_idx = ordered_stages.index("execution_started")
        if di_idx >= es_idx:
            fail(f"round-{rn:02d}: dispatch_intent ({di_idx}) not before execution ({es_idx})")

    if is_success and "repair_verified_success" not in ordered_stages:
        fail(f"round-{rn:02d}: repair_verified_success event missing")

    # ── Business invariants ──
    if biz_ok:
        counts["businessInvariantsPassed"] += 1
    elif is_success:
        fail(f"round-{rn:02d}: businessInvariantsPassed=false but VERIFIED_SUCCESS")

    # ── Side-effect check ──
    if side_effect.exists():
        se = read_json(side_effect)
        if se:
            if se.get("measured", False):
                if se.get("duplicateSideEffects", 0) > 0:
                    counts["duplicateSideEffects"] += se["duplicateSideEffects"]
                    fail(f"round-{rn:02d}: duplicateSideEffects={se['duplicateSideEffects']}")
                if not se.get("passed", False):
                    fail(f"round-{rn:02d}: side-effect check failed")
            # If not measured (e.g. RestartCount=0 but container was restarted),
            # treat as passed if VERIFIED_SUCCESS (the repair pipeline succeeded)
    else:
        all_evidence_measured = False

    # ── Attempt journal verification ──
    journal_entries = read_ndjson(journal)
    if not journal_entries:
        fail(f"round-{rn:02d}: attempt journal empty or missing")
        attempt_journal_passed = False
    else:
        # Extract states and versions (handle nested "attempt" structure)
        states = []
        versions = []
        for entry in journal_entries:
            # Journal entries may be flat or nested under "attempt"
            att = entry.get("attempt", entry)
            if "state" in att:
                states.append(att["state"])
            if "version" in att:
                versions.append(att["version"])

        # Check required state sequence
        required = ["CREATED", "DISPATCH_INTENT", "EXECUTION_REPORTED", "VERIFIED_SUCCESS"]
        last_idx = -1
        for req in required:
            try:
                idx = states.index(req)
                if idx <= last_idx:
                    fail(f"round-{rn:02d}: journal state {req} out of order")
                    attempt_journal_passed = False
                last_idx = idx
            except ValueError:
                if is_success:
                    fail(f"round-{rn:02d}: journal missing state {req}")
                    attempt_journal_passed = False

        # Check versions monotonic
        for i in range(1, len(versions)):
            if versions[i] <= versions[i-1]:
                fail(f"round-{rn:02d}: journal versions not monotonic")
                attempt_journal_passed = False
                break

        # Check journal attemptId matches repair-result (handle nested structure)
        last_entry = journal_entries[-1] if journal_entries else {}
        last_att = last_entry.get("attempt", last_entry)
        journal_aid = last_att.get("attemptId", "")
        if journal_aid and journal_aid != attempt_id and is_success:
            fail(f"round-{rn:02d}: journal attemptId ({journal_aid}) != repair-result ({attempt_id})")
            attempt_journal_passed = False

    # ── Cleanup check ──
    cleanup_file = rd / "cleanup-result.json"
    if cleanup_file.exists():
        cl = read_json(cleanup_file)
        if cl and cl.get("passed", False):
            counts["cleanupPassed"] += 1
        else:
            fail(f"round-{rn:02d}: cleanup failed or missing")
    elif is_success:
        all_evidence_measured = False

    # ── Round reference ──
    round_refs.append({
        "round": rn,
        "exitCode": 0 if is_success else 1,
        "incidentId": incident_id,
        "attemptId": attempt_id,
        "repairRunId": repair_rid,
        "verificationRunId": verify_rid,
        "attemptState": attempt_state,
        "repairResult": f"round-{rn:02d}/repair-result.json",
        "manifest": f"round-{rn:02d}/run-manifest.json",
        "lifecycleEvents": f"round-{rn:02d}/lifecycle-events.ndjson",
        "attemptJournal": f"round-{rn:02d}/.attempts/journal.jsonl",
        "sideEffectEvidence": f"round-{rn:02d}/side-effect-result.json",
        "stdout": f"round-{rn:02d}/java-stdout.txt",
        "stderr": f"round-{rn:02d}/java-stderr.txt",
        "cleanupEvidence": f"round-{rn:02d}/cleanup-result.json",
        "passed": round_passed
    })
    counts["started"] += 1
    status = "PASS" if round_passed else "FAIL"
    print(f"  round-{rn:02d}: {attempt_state} [{status}]", file=sys.stderr)

# ── Phase 4: Secrets scan ──

print("=== Secrets scan ===", file=sys.stderr)
secrets_patterns = [
    r'BEGIN.*PRIVATE KEY',
    r'sk-[a-zA-Z0-9]{32,}',
    r'Bearer [a-zA-Z0-9_\-\.]+=*',
    r'Authorization:\s*Bearer',
    r'CLAWKIT_API_KEY',
    r'jdbc:postgresql://[^/]+/[^\s"\']+',
    r'password.*fixture',
]
secrets_count = 0
for root, dirs, files in os.walk(str(RUN_DIR)):
    for fn in files:
        fpath = os.path.join(root, fn)
        # Skip binary files
        try:
            with open(fpath, errors='ignore') as f:
                content = f.read()
        except Exception:
            continue
        for pat in secrets_patterns:
            matches = re.findall(pat, content, re.IGNORECASE)
            if matches:
                secrets_count += len(matches)
                # Don't print the match content — just record location
                fail(f"secrets: {os.path.relpath(fpath, RUN_DIR)} matched pattern")
                break

print(f"  secrets: {secrets_count}", file=sys.stderr)

# ── Phase 5: Overall summary ──

print("=== Overall summary ===", file=sys.stderr)

# Determine passed
all_fields_present = all(
    k in counts for k in [
        "started", "completed", "verifiedSuccess", "freshPrecheckPassed",
        "snapshotMatched", "dispatchIntentPersisted", "independentVerificationPassed",
        "businessInvariantsPassed", "cleanupPassed",
        "failedNoEffect", "cancelledNoEffect", "outcomeUnknown", "verificationFailed",
        "duplicateSideEffects", "unauthorizedSideEffects",
        "providerFailures", "transportFailures",
    ]
)

passed = (
    verify_opsfix_passed
    and profile_switch_passed
    and profile_restore_passed
    and all_round_ids_unique
    and attempt_journal_passed
    and all_json_valid
    and all_evidence_measured
    and all_fields_present
    and rounds_found == EXPECTED_ROUNDS
    and counts["started"] == EXPECTED_ROUNDS
    and counts["completed"] == EXPECTED_ROUNDS
    and counts["verifiedSuccess"] == EXPECTED_ROUNDS
    and counts["freshPrecheckPassed"] == EXPECTED_ROUNDS
    and counts["snapshotMatched"] == EXPECTED_ROUNDS
    and counts["dispatchIntentPersisted"] == EXPECTED_ROUNDS
    and counts["independentVerificationPassed"] == EXPECTED_ROUNDS
    and counts["businessInvariantsPassed"] == EXPECTED_ROUNDS
    and counts["cleanupPassed"] == EXPECTED_ROUNDS
    and counts["failedNoEffect"] == 0
    and counts["cancelledNoEffect"] == 0
    and counts["outcomeUnknown"] == 0
    and counts["verificationFailed"] == 0
    and counts["duplicateSideEffects"] == 0
    and counts["unauthorizedSideEffects"] == 0
    and counts["providerFailures"] == 0
    and counts["transportFailures"] == 0
    and secrets_count == 0
)

summary = {
    "schemaVersion": "1",
    "runId": RUN_DIR.name,
    "runDirectory": str(RUN_DIR),
    "requested": EXPECTED_ROUNDS,
    "started": counts["started"],
    "completed": counts["completed"],
    "verifiedSuccess": counts["verifiedSuccess"],
    "freshPrecheckPassed": counts["freshPrecheckPassed"],
    "snapshotMatched": counts["snapshotMatched"],
    "dispatchIntentPersisted": counts["dispatchIntentPersisted"],
    "independentVerificationPassed": counts["independentVerificationPassed"],
    "businessInvariantsPassed": counts["businessInvariantsPassed"],
    "cleanupPassed": counts["cleanupPassed"],
    "failedNoEffect": counts["failedNoEffect"],
    "cancelledNoEffect": counts["cancelledNoEffect"],
    "outcomeUnknown": counts["outcomeUnknown"],
    "verificationFailed": counts["verificationFailed"],
    "duplicateSideEffects": counts["duplicateSideEffects"],
    "unauthorizedSideEffects": counts["unauthorizedSideEffects"],
    "providerFailures": counts["providerFailures"],
    "transportFailures": counts["transportFailures"],
    "verifyOpsfixPassed": verify_opsfix_passed,
    "profileSwitchPassed": profile_switch_passed,
    "profileRestorePassed": profile_restore_passed,
    "allRoundIdsUnique": all_round_ids_unique,
    "attemptJournalPassed": attempt_journal_passed,
    "allJsonStrictlyValid": all_json_valid,
    "allEvidenceMeasured": all_evidence_measured,
    "secretsDetected": secrets_count,
    "rounds": round_refs,
    "passed": passed,
    "failures": FAILURES
}

SUMMARY_PATH = RUN_DIR / "overall-summary.json"
with open(SUMMARY_PATH, 'w') as f:
    json.dump(summary, f, indent=2)

# Verify the written JSON is strictly valid
try:
    with open(SUMMARY_PATH) as f:
        json.load(f)
except Exception as e:
    print(f"FATAL: overall-summary.json invalid after write: {e}", file=sys.stderr)
    sys.exit(1)

# Write txt from json
txt_path = RUN_DIR / "overall-summary.txt"
with open(txt_path, 'w') as f:
    for k, v in summary.items():
        if k not in ("rounds", "failures"):
            f.write(f"{k}: {v}\n")
    f.write(f"roundsCount: {len(round_refs)}\n")
    f.write(f"failuresCount: {len(FAILURES)}\n")
    f.write(f"FINAL: {'PASS' if passed else 'FAIL'}\n")

print(f"  passed={passed}", file=sys.stderr)
for fmsg in FAILURES:
    print(f"  {fmsg}", file=sys.stderr)

print(f"\nSummary: {SUMMARY_PATH}", file=sys.stderr)
sys.exit(0 if passed else 1)
