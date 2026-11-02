# Run from PowerShell after filling the project-root .env file.
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$envPath = Join-Path $projectDir '.env'
if (-not (Test-Path -LiteralPath $envPath)) {
  throw 'Missing project-root .env file. Copy .env.example and fill your local key.'
}
# Old terminal values otherwise override --env-file. Restore them after Compose finishes.
$names = @('DEEPSEEK_API_KEY', 'DEEPSEEK_MODEL', 'CITYMATE_AI_ENABLED')
$saved = @{}
foreach ($name in $names) {
  $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
  [Environment]::SetEnvironmentVariable($name, $null, 'Process')
}
try {
  & docker compose --env-file $envPath -p hm-dianping `
    -f (Join-Path $projectDir 'dev/docker-compose.yml') `
    -f (Join-Path $projectDir 'dev/docker-compose.prebuilt.yml') `
    up -d --no-deps --force-recreate java
  if ($LASTEXITCODE -ne 0) {
    throw 'Java container recreation failed. Check Docker Desktop and .env syntax.'
  }
} finally {
  foreach ($name in $names) {
    [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
  }
  $saved.Clear()
}
