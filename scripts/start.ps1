[CmdletBinding()]
param(
    [switch]$NoBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$infrastructureDir = Join-Path $projectRoot 'infrastructure'
$envFile = Join-Path $infrastructureDir '.env'
$exampleFile = Join-Path $infrastructureDir '.env.example'

function Assert-DockerCompose {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker command was not found. Install Docker Desktop or Docker Engine with the Compose plugin.'
    }
    & docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose is unavailable. Confirm that the Docker service is running.'
    }
}

function Get-EnvironmentValue([string]$Name) {
    $line = Get-Content $envFile | Where-Object { $_ -match "^$([regex]::Escape($Name))=" } | Select-Object -First 1
    if ($null -eq $line) { return '' }
    return ($line -replace "^$([regex]::Escape($Name))=", '').Trim()
}

Assert-DockerCompose

if (-not (Test-Path $envFile)) {
    Copy-Item $exampleFile $envFile
    Write-Host "Created $envFile" -ForegroundColor Yellow
    Write-Host 'Set MODEL_NAME, MODEL_API_KEY, POSTGRES_PASSWORD, and RABBITMQ_PASSWORD, then run this script again.' -ForegroundColor Yellow
    exit 1
}

$required = @('POSTGRES_PASSWORD', 'RABBITMQ_PASSWORD', 'MODEL_NAME', 'MODEL_API_KEY')
$invalid = foreach ($name in $required) {
    $value = Get-EnvironmentValue $name
    if ([string]::IsNullOrWhiteSpace($value) -or $value -like 'replace-with-*') { $name }
}
if ($invalid) {
    throw "Missing required values in infrastructure/.env: $($invalid -join ', ')"
}

$fontPath = Join-Path $infrastructureDir 'fonts\NotoSansCJKsc-Regular.otf'
if (-not (Test-Path $fontPath)) {
    Write-Warning 'CJK font not found. The services can start, but PDF export needs infrastructure/fonts/NotoSansCJKsc-Regular.otf.'
}

Push-Location $infrastructureDir
try {
    & docker compose --env-file .env config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose configuration validation failed.' }

    if ($NoBuild) {
        & docker compose --env-file .env up -d --remove-orphans
    } else {
        & docker compose --env-file .env up -d --build --remove-orphans
    }
    if ($LASTEXITCODE -ne 0) { throw 'Service startup failed. Run docker compose logs to inspect the failure.' }

    & docker compose --env-file .env ps
    Write-Host 'Startup command completed. Open http://<VM-IP>/' -ForegroundColor Green
} finally {
    Pop-Location
}
