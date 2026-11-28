param([switch]$KeepEnvironment)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $projectDir 'dev/docker-compose.test.yml'
$composeArgs = @('compose', '-p', 'citymate-transaction-p6', '-f', $composeFile)
$testClasses = 'TransactionBaselineIntegrationTest'
$logPath = Join-Path $projectDir 'target/phase6-transaction-baseline.log'
$started = $false
$runStarted = [DateTimeOffset]::UtcNow
$rawReport = Join-Path $projectDir 'docs/phase6-transaction-baseline.json'
if (Test-Path -LiteralPath $rawReport) {
  $previous = Get-Content -LiteralPath $rawReport -Raw | ConvertFrom-Json
  $archiveId = [DateTimeOffset]::Parse($previous.generatedAt).UtcDateTime.ToString('yyyyMMddTHHmmssffffffZ')
  $archiveDir = Join-Path $projectDir ('docs/transaction-baselines/' + $archiveId)
  New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null
  $archiveFile = Join-Path $archiveDir 'phase6-transaction-baseline.json'
  if (-not (Test-Path -LiteralPath $archiveFile)) {
    Copy-Item -LiteralPath $rawReport -Destination $archiveFile
  } elseif ((Get-FileHash -LiteralPath $archiveFile).Hash -ne (Get-FileHash -LiteralPath $rawReport).Hash) {
    throw 'Archive collision: refusing to overwrite a different baseline.'
  }
}

Push-Location $projectDir
try {
  & docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Restore it before running integration tests.' }
  New-Item -ItemType Directory -Force -Path (Join-Path $projectDir 'target') | Out-Null
  $started = $true
  & docker @composeArgs up -d --wait mysql redis memcached rocketmq
  if ($LASTEXITCODE -ne 0) { throw 'Isolated dependencies failed to start.' }
  & docker @composeArgs run --rm --no-deps mq-init
  if ($LASTEXITCODE -ne 0) { throw 'RocketMQ topic initialization failed.' }
  # Windows PowerShell treats redirected native stderr as error records.
  # Docker writes normal progress there; judge the result by its exit code.
  $previousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    & docker @composeArgs run --rm --no-deps test mvn -B `
      "-Dtest=$testClasses" '-DargLine=-Xmx512m' '-Dcitymate.baseline=true' '-Dspring.test.context.cache.maxSize=1' `
      test *> $logPath
    $testExit = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousErrorAction
  }
  Get-Content -LiteralPath $logPath -Tail 30
  if ($testExit -ne 0) { throw 'Transaction baseline failed. See target/phase6-transaction-baseline.log.' }
  [xml]$junit = Get-Content -LiteralPath (Join-Path $projectDir 'target/surefire-reports/TEST-com.hmdp.reliability.TransactionBaselineIntegrationTest.xml') -Raw
  $suite = $junit.testsuite
  if ([int]$suite.tests -ne 1 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
    throw 'Baseline test did not execute successfully.'
  }
  $report = Get-Content -LiteralPath $rawReport -Raw | ConvertFrom-Json
  if (-not $report.passed -or $report.rounds.Count -ne 3 -or [DateTimeOffset]::Parse($report.generatedAt) -lt $runStarted) {
    throw 'Missing, failed or stale baseline report.'
  }
  Write-Output 'Finite HTTP baseline and three reconciliation rounds passed. This is not a maximum capacity measurement.'
} finally {
  if ($started -and -not $KeepEnvironment) {
    # Only this dedicated test project; external Maven cache and business volumes remain intact.
    & docker @composeArgs down -v
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Test environment cleanup did not complete.' }
  }
  Pop-Location
}
