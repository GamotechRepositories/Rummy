# ==============================================================================
# Phase 43: Graceful Server Draining & Zero-Downtime Migration Script (PowerShell)
# ==============================================================================
param (
    [string]$TargetHost = "http://localhost:8081",
    [int]$MaxWaitSeconds = 120
)

Write-Host "[DRAIN] Initiating graceful draining for game server at $TargetHost..." -ForegroundColor Cyan

# 1. Trigger Drain Mode
try {
    $drainResponse = Invoke-RestMethod -Uri "$TargetHost/api/admin/drain?drain=true" -Method Post
    Write-Host "[DRAIN] Server entered DRAINING mode: $($drainResponse.message)" -ForegroundColor Green
} catch {
    Write-Host "[DRAIN ERROR] Failed to contact admin endpoint at ${TargetHost}: $_" -ForegroundColor Red
    exit 1
}

# 2. Poll active tables
$elapsed = 0
while ($elapsed -lt $MaxWaitSeconds) {
    try {
        $diag = Invoke-RestMethod -Uri "$TargetHost/api/admin/diagnostics" -Method Get
        $activeTables = $diag.activeTables
        Write-Host "[DRAIN] Active tables remaining: $activeTables (Elapsed: ${elapsed}s / ${MaxWaitSeconds}s)" -ForegroundColor Yellow

        if ($activeTables -le 0) {
            Write-Host "[DRAIN SUCCESS] All active tables successfully completed! Server is completely drained and safe for termination/upgrade." -ForegroundColor Green
            exit 0
        }
    } catch {
        Write-Host "[DRAIN WARN] Error querying diagnostics: $_" -ForegroundColor DarkYellow
    }

    Start-Sleep -Seconds 5
    $elapsed += 5
}

Write-Host "[DRAIN WARN] Timeout reached ($MaxWaitSeconds seconds). Ready for force termination." -ForegroundColor Red
exit 0
