# Builds the project and generates + validates a dataset.
# Usage: .\scripts\windows\generate-data.ps1 -Scale starter     (tiny | starter | full)
param([string]$Scale = "starter", [long]$Seed = 20261022)
$ErrorActionPreference = "Stop"
if (-not (Test-Path target\budget-intelligence.jar)) { mvn -q -DskipTests package }
java -Xmx3g -jar target\budget-intelligence.jar generate --scale $Scale --seed $Seed --out "data\$Scale"
java -Xmx3g -jar target\budget-intelligence.jar evaluate --data "data\$Scale" | Out-Null
Write-Host "Dataset written to data\$Scale (validation_summary.json, evaluation_report.json)" -ForegroundColor Green
