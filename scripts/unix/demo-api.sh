#!/usr/bin/env bash
# End-to-end API walkthrough with curl (start the app first).
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"; DATA="$(cd "${1:-data/starter}" && pwd)"
curl -s -X POST "$BASE/api/admin/datasets/load" -H 'Content-Type: application/json' -d "{\"path\":\"$DATA\"}"; echo
RUN=$(curl -s -X POST "$BASE/api/pipeline/runs" -H 'X-Actor: ops-analyst'); echo "$RUN"
RUN_ID=$(echo "$RUN" | sed -E 's/.*"run_id":"([^"]+)".*/\1/')
curl -s "$BASE/api/pipeline/runs/$RUN_ID/evaluation"; echo
curl -s "$BASE/api/users/USR-000001/budget-report?month=2026-03"; echo
curl -s "$BASE/api/users/USR-000001/recurring"; echo
curl -s "$BASE/api/transactions/TXN-2026-00000001/trace"; echo
curl -s "$BASE/api/exceptions?status=OPEN&size=3"; echo
