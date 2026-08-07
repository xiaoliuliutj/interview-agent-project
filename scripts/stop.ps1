$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location (Join-Path $projectRoot 'infrastructure')
try {
    docker compose --env-file .env down
} finally {
    Pop-Location
}
