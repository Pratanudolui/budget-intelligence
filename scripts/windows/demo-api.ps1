# End-to-end API walkthrough. Start the app first (run-app.ps1) in another terminal.
param([string]$Dataset = "data/starter", [string]$Base = "http://localhost:8080")
$ErrorActionPreference = "Stop"
$path = (Resolve-Path $Dataset).Path
Write-Host "1) Load dataset $path" -ForegroundColor Cyan
Invoke-RestMethod -Method Post "$Base/api/admin/datasets/load" -ContentType "application/json" `
    -Body (@{ path = $path } | ConvertTo-Json) -Headers @{ "X-Actor" = "admin" } | Format-List
Write-Host "2) Run pipeline" -ForegroundColor Cyan
$run = Invoke-RestMethod -Method Post "$Base/api/pipeline/runs" -Headers @{ "X-Actor" = "ops-analyst" }
$run | Format-List
Write-Host "3) Evaluation metrics (edge cases)" -ForegroundColor Cyan
$eval = Invoke-RestMethod "$Base/api/pipeline/runs/$($run.run_id)/evaluation"
$eval.metrics.edge_cases | ConvertTo-Json -Depth 5
Write-Host "4) Budget report USR-000001 / 2026-03" -ForegroundColor Cyan
Invoke-RestMethod "$Base/api/users/USR-000001/budget-report?month=2026-03" | ConvertTo-Json -Depth 5
Write-Host "5) Recurring payments USR-000001" -ForegroundColor Cyan
Invoke-RestMethod "$Base/api/users/USR-000001/recurring" | Format-Table series_id, merchant_name, kind, cadence, typical_amount, status, missed_dates
Write-Host "6) Trace TXN-2026-00000001" -ForegroundColor Cyan
Invoke-RestMethod "$Base/api/transactions/TXN-2026-00000001/trace" | ConvertTo-Json -Depth 5
Write-Host "7) Open exceptions (first 5)" -ForegroundColor Cyan
$exc = Invoke-RestMethod "$Base/api/exceptions?status=OPEN&size=5"
$exc.items | Format-Table exceptionId, exceptionType, severity, entityId
$id = $exc.items[0].exceptionId
Write-Host "8) Move $id OPEN -> IN_REVIEW -> RESOLVED and show its audit trail" -ForegroundColor Cyan
Invoke-RestMethod -Method Post "$Base/api/exceptions/$id/transitions" -ContentType "application/json" `
    -Body '{"toStatus":"IN_REVIEW","note":"investigating"}' -Headers @{ "X-Actor" = "reviewer-1" } | Out-Null
Invoke-RestMethod -Method Post "$Base/api/exceptions/$id/transitions" -ContentType "application/json" `
    -Body '{"toStatus":"RESOLVED","note":"confirmed"}' -Headers @{ "X-Actor" = "reviewer-1" } | Out-Null
Invoke-RestMethod "$Base/api/exceptions/$id/events" | Format-Table fromStatus, toStatus, actor, note, createdAt
Write-Host "9) Illegal transition RESOLVED -> OPEN is rejected with HTTP 409:" -ForegroundColor Cyan
try {
    Invoke-RestMethod -Method Post "$Base/api/exceptions/$id/transitions" -ContentType "application/json" -Body '{"toStatus":"OPEN"}'
} catch { Write-Host $_.ErrorDetails.Message -ForegroundColor Yellow }
