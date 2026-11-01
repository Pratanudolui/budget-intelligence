# Generates the dataset twice with the same seed and compares row counts + SHA-256 checksums.
param([string]$Scale = "starter")
$ErrorActionPreference = "Stop"
java -Xmx3g -jar target\budget-intelligence.jar repro --scale $Scale
