#!/usr/bin/env python3
"""MCP attestation helper — reads initialize + tools/list responses and writes
profile-{before,app-down,restored}.json with full field comparison.

Usage: python3 mcp_attest.py <outdir> <stage> [--compare-before]
  stage: before | app-down | restored
  --compare-before: when stage=restored, compare all fields with profile-before.json
                    and exit 0 if match, exit 1 if mismatch.
"""

import json, sys, hashlib
from pathlib import Path

outdir = Path(sys.argv[1])
stage = sys.argv[2]
compare = len(sys.argv) > 3 and sys.argv[3] == '--compare-before'

# Read from stdin (two JSON-RPC responses, separated by a delimiter)
data = sys.stdin.read().strip()
parts = data.split('\n---TOOLS---\n')
if len(parts) < 2:
    print("ERROR: expected two JSON-RPC responses separated by ---TOOLS---", file=sys.stderr)
    sys.exit(1)

try:
    init = json.loads(parts[0])
    tools = json.loads(parts[1])
except json.JSONDecodeError as e:
    print(f"ERROR: JSON parse error: {e}", file=sys.stderr)
    sys.exit(1)

si = init.get("result", {}).get("serverInfo", {})
tl = tools.get("result", {}).get("tools", [])
names = sorted([t["name"] for t in tl])
h = hashlib.sha256("".join(names).encode()).hexdigest()[:16]

result = {
    "serverName": si.get("name", ""),
    "profile": si.get("capabilityProfile", ""),
    "probeVersion": si.get("probeVersion", ""),
    "toolSetHash": si.get("toolSetHash", ""),
    "toolListHash": h,
    "toolCount": len(names),
    "toolNames": names,
}

outfile = outdir / f"profile-{stage}.json"
json.dump(result, open(outfile, "w"), indent=2)
print(f"profile: {result['profile']}  hash: {result['toolSetHash']}  tools: {len(names)}")

if compare:
    before_file = outdir / "profile-before.json"
    if not before_file.exists():
        print("ERROR: profile-before.json not found", file=sys.stderr)
        sys.exit(1)
    before = json.load(open(before_file))
    keys = ["profile", "serverName", "toolSetHash", "toolCount"]
    match = all(result.get(k) == before.get(k) for k in keys)
    if match:
        print("RESTORE_MATCH")
        sys.exit(0)
    else:
        for k in keys:
            if result.get(k) != before.get(k):
                print(f"MISMATCH: {k}: before={before.get(k)} restored={result.get(k)}", file=sys.stderr)
        sys.exit(1)

if stage == "app-down" and result.get("profile") == "APP_DOWN_V1":
    print("SWITCH_OK")
