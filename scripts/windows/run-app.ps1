# Starts the REST API. Override DB settings with environment variables if yours differ.
if (-not $env:DB_URL) { $env:DB_URL = "jdbc:postgresql://localhost:5432/budget_intel" }
if (-not $env:DB_USERNAME) { $env:DB_USERNAME = "budget_app" }
if (-not $env:DB_PASSWORD) { $env:DB_PASSWORD = "budget_app" }
java -Xmx3g -jar target\budget-intelligence.jar
