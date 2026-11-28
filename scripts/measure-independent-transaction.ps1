param(
  [switch]$KeepEnvironment,
  [ValidateSet('always', 'everysec')][string]$RedisFsync = 'always',
  [switch]$NoUpdateLatest
)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $projectDir 'dev/docker-compose.test.yml'
$composeArgs = @('compose', '-p', 'citymate-profile-p6', '-f', $composeFile)
$runId = [Guid]::NewGuid().ToString('N')
$exchange = Join-Path $projectDir ('target/transaction-profile/' + $runId)
$logPath = Join-Path $projectDir 'target/phase6-independent-profile.log'
$appContainer = 'citymate-profile-p6-app'
$started = $false
$appStarted = $false

Push-Location $projectDir
try {
  & docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker engine unavailable.' }
  $existing = @(& docker ps -aq --filter ('name=^/' + $appContainer + '$'))
  if ($LASTEXITCODE -ne 0 -or $existing.Count -gt 0) {
    throw 'Dedicated profile application already exists. Inspect it before starting another run.'
  }
  $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 18086)
  try { $listener.Start() } finally { $listener.Stop() }
  New-Item -ItemType Directory -Force -Path $exchange | Out-Null
  $overrideFile = Join-Path $exchange 'redis-fsync.override.yml'
  $override = "services:`n  redis:`n    command: [redis-server, --appendonly, 'yes', --appendfsync, $RedisFsync]`n"
  [IO.File]::WriteAllText($overrideFile, $override, (New-Object Text.UTF8Encoding($false)))
  $composeArgs += @('-f', $overrideFile)
  $started = $true
  & docker @composeArgs up -d --wait mysql redis memcached rocketmq
  if ($LASTEXITCODE -ne 0) { throw 'Profile dependencies failed to start.' }
  & docker @composeArgs run --rm --no-deps mq-init
  if ($LASTEXITCODE -ne 0) { throw 'Topic initialization failed.' }
  & docker @composeArgs run -d --no-deps --name $appContainer -p '127.0.0.1:18086:8081' test `
    mvn -B '-Dtest=IndependentTransactionProfileIntegrationTest' '-Dcitymate.profile=true' `
    "-Dcitymate.profile.run-id=$runId" "-Dcitymate.profile.redis-fsync=$RedisFsync" '-DargLine=-Xmx512m' test
  if ($LASTEXITCODE -ne 0) { throw 'Profile application failed to start.' }
  $appStarted = $true
  & python (Join-Path $PSScriptRoot 'profile-transaction-load.py') `
    --work-dir $exchange --run-id $runId --port 18086
  if ($LASTEXITCODE -ne 0) { throw 'Independent load generator failed.' }
  $exitText = & docker wait $appContainer
  if ($LASTEXITCODE -ne 0 -or [int]$exitText -ne 0) { throw 'Profile application test failed.' }
  [xml]$junit = Get-Content -LiteralPath (Join-Path $projectDir 'target/surefire-reports/TEST-com.hmdp.reliability.IndependentTransactionProfileIntegrationTest.xml') -Raw
  $suite = $junit.testsuite
  if ([int]$suite.tests -ne 1 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
    throw 'Profile test skipped or failed.'
  }
  $rawReport = Join-Path $exchange 'application.json'
  $report = Get-Content -LiteralPath $rawReport -Raw | ConvertFrom-Json
  if (-not $report.passed -or $report.runId -ne $runId -or $report.rounds.Count -ne 3 -or $report.redisFsync -ne $RedisFsync) {
    throw 'Missing or stale application report.'
  }
  $archiveDir = Join-Path $projectDir ('docs/transaction-profiles/' + $runId)
  New-Item -ItemType Directory -Path $archiveDir | Out-Null
  Copy-Item -LiteralPath $rawReport -Destination (Join-Path $archiveDir 'application.json')
  Copy-Item -LiteralPath (Join-Path $exchange 'observations.json') -Destination (Join-Path $archiveDir 'observations.json')
  if (-not $NoUpdateLatest) {
    Copy-Item -LiteralPath $rawReport -Destination (Join-Path $projectDir 'docs/phase6-independent-profile.json')
    Copy-Item -LiteralPath (Join-Path $exchange 'observations.json') -Destination (Join-Path $projectDir 'docs/phase6-independent-observations.json')
  }
  $manifest = @{ runId = $runId; redisFsync = $report.redisFsync; archive = $archiveDir }
  [IO.File]::WriteAllText((Join-Path $projectDir 'target/transaction-profile/last-run.json'),
    ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding($false)))
  Write-Output "Independent profile and reconciliation passed. Run id: $runId"
} finally {
  if ($appStarted) {
    $previousErrorAction = $ErrorActionPreference
    try {
      $ErrorActionPreference = 'Continue'
      & docker logs $appContainer *> $logPath
      Get-Content -LiteralPath $logPath -Tail 12
    } finally { $ErrorActionPreference = $previousErrorAction }
    if (-not $KeepEnvironment) {
      & docker rm -f $appContainer
    }
  }
  if ($started -and -not $KeepEnvironment) {
    & docker @composeArgs down -v
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Dedicated profile cleanup failed.' }
  }
  Pop-Location
}
