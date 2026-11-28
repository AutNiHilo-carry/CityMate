param([switch]$KeepEnvironment)

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $projectDir 'dev/docker-compose.test.yml'
$composeArgs = @('compose', '-p', 'citymate-compensation-p4', '-f', $composeFile)
$testClasses = 'SeckillCompensationIntegrationTest,MessagingIntegrationTest,SeckillMessageTest,VoucherOrderReliabilityTest,ReliabilityIntegrationTest#poisonMessageIsQuarantinedAndCompensationIsIdempotent+databaseInsertFailureRollsBackStockAndPendingMessageCanRetry+sameUserConcurrentRequestsShareOneReservation'
$logPath = Join-Path $projectDir 'target/phase4-compensation-integration.log'
$started = $false

Push-Location $projectDir
try {
  & docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker engine is unavailable. Restore it before running integration tests.' }
  New-Item -ItemType Directory -Force -Path (Join-Path $projectDir 'target') | Out-Null
  $started = $true
  & docker @composeArgs up -d --wait mysql redis memcached
  if ($LASTEXITCODE -ne 0) { throw 'Isolated dependencies failed to start.' }
  # Windows PowerShell treats redirected native stderr as error records.
  # Docker writes normal progress there; judge the result by its exit code.
  $previousErrorAction = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    & docker @composeArgs run --rm --no-deps test mvn -B `
      "-Dtest=$testClasses" '-DargLine=-Xmx512m' '-Dspring.test.context.cache.maxSize=1' `
      test *> $logPath
    $testExit = $LASTEXITCODE
  } finally {
    $ErrorActionPreference = $previousErrorAction
  }
  Get-Content -LiteralPath $logPath -Tail 30
  if ($testExit -ne 0) { throw 'Compensation recovery tests failed. See target/phase4-compensation-integration.log.' }
  Write-Output 'Selected integration and unit suites passed. Review the JUnit reports and generated recovery evidence.'
} finally {
  if ($started -and -not $KeepEnvironment) {
    # Only this dedicated test project; external Maven cache and business volumes remain intact.
    & docker @composeArgs down -v
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Test environment cleanup did not complete.' }
  }
  Pop-Location
}
