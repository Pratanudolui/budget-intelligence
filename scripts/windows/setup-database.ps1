# Creates the local PostgreSQL role and database. Prompts for the 'postgres' superuser password.
# Usage (from the project root):  .\scripts\windows\setup-database.ps1
$ErrorActionPreference = "Stop"
psql -U postgres -h localhost -f scripts\sql\create_database.sql
Write-Host "Database budget_intel and role budget_app created." -ForegroundColor Green
