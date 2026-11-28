# Start the complete local stack without printing environment-file contents.
param(
  [switch]$FromSource,
  [ValidateRange(10, 900)][int]$ReadyTimeoutSeconds = 180
)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectDir '.env'
$jarPath = Join-Path $projectDir 'target/citymate-0.0.1-SNAPSHOT.jar'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
  throw 'Docker CLI not found. Install Docker Desktop and reopen the terminal.'
}
& docker info --format '{{.ServerVersion}}' *> $null
if ($LASTEXITCODE -ne 0) {
  throw 'Docker is not running. Open Docker Desktop and wait for Engine running, then retry.'
}
if (-not $FromSource -and -not (Test-Path -LiteralPath $jarPath)) {
  throw 'Built JAR not found. Build the project first, or run this script with -FromSource.'
}
$compose = @('compose', '-p', 'citymate', '-f', (Join-Path $projectDir 'dev/docker-compose.yml'))
if (-not $FromSource) { $compose += @('-f', (Join-Path $projectDir 'dev/docker-compose.prebuilt.yml')) }
$saved = @{}
# Match start-citymate-ai.ps1: the file wins over stale AI values in this terminal.
if (Test-Path -LiteralPath $envPath) {
  $compose += @('--env-file', $envPath)
  foreach ($name in @('DEEPSEEK_API_KEY', 'DEEPSEEK_MODEL', 'CITYMATE_AI_ENABLED')) {
    $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    [Environment]::SetEnvironmentVariable($name, $null, 'Process')
  }
}
Push-Location $projectDir
try {
  Write-Host 'Starting CityMate services...'
  & docker @compose up -d
  if ($LASTEXITCODE -ne 0) { throw 'Service startup failed. Review the Compose error above; data volumes were not deleted.' }
  $binding = @(& docker @compose port web 80)
  if ($LASTEXITCODE -ne 0 -or $binding.Count -eq 0) { throw 'Cannot determine the web port. Check the web container status.' }
  $portMatch = [regex]::Match([string]$binding[0], ':(\d+)\s*$')
  if (-not $portMatch.Success) { throw 'Unexpected web port binding; check Compose status.' }
  $webUrl = 'http://127.0.0.1:' + $portMatch.Groups[1].Value
  $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
  Write-Host 'Waiting for the web proxy and Java health check...'
  while ([DateTime]::UtcNow -lt $deadline) {
    try {
      $health = Invoke-RestMethod -Uri ($webUrl + '/api/actuator/health') -TimeoutSec 4
      if ($health.status -eq 'UP') {
        $catalog = Invoke-RestMethod -Uri ($webUrl + '/api/shop-type/list') -TimeoutSec 4
        if ($catalog.success -eq $true) {
          Write-Host ('CityMate is ready: ' + $webUrl + '/')
          Write-Host ('AI assistant: ' + $webUrl + '/assistant.html (login required)')
          return
        }
      }
    } catch { }
    Start-Sleep -Seconds 2
  }
  & docker @compose ps -a
  throw 'Services are not ready within the timeout. Check Java logs: docker compose -p citymate -f dev/docker-compose.yml logs --tail 80 java'
} finally {
  Pop-Location
  foreach ($name in $saved.Keys) {
    [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
  }
  $saved.Clear()
}
