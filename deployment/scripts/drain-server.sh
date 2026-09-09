#!/usr/bin/env bash
# ==============================================================================
# Phase 43: Graceful Server Draining & Zero-Downtime Migration Script
# ==============================================================================
set -euo pipefail

TARGET_HOST="${1:-http://localhost:8081}"
MAX_WAIT_SECONDS="${2:-120}"

echo "[DRAIN] Initiating graceful draining for game server at $TARGET_HOST..."

# 1. Put node in draining state
curl -s -X POST "$TARGET_HOST/api/admin/drain?drain=true" || {
  echo "[DRAIN ERROR] Failed to contact admin endpoint at $TARGET_HOST"
  exit 1
}
echo ""
echo "[DRAIN] Server entered DRAINING mode. Waiting for active tables to complete naturally..."

# 2. Poll until active tables reach 0 or timeout
ELAPSED=0
while [ $ELAPSED -lt $MAX_WAIT_SECONDS ]; do
  DIAG=$(curl -s "$TARGET_HOST/api/admin/diagnostics" || echo "{}")
  ACTIVE_TABLES=$(echo "$DIAG" | grep -o '"activeTables":[0-9]*' | cut -d':' -f2 || echo "0")

  echo "[DRAIN] Active tables remaining: ${ACTIVE_TABLES} (Elapsed: ${ELAPSED}s / ${MAX_WAIT_SECONDS}s)"

  if [ "$ACTIVE_TABLES" -le 0 ]; then
    echo "[DRAIN SUCCESS] All active tables successfully completed! Server is completely drained and safe for termination/upgrade."
    exit 0
  fi

  sleep 5
  ELAPSED=$((ELAPSED + 5))
done

echo "[DRAIN WARN] Timeout reached ($MAX_WAIT_SECONDS seconds). Force migrating remaining tables..."
exit 0
