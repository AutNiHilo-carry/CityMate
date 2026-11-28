param([switch]$KeepEnvironment)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$composeArgs = @('compose', '-p', 'citymate-script-loading-p6', '-f', (Join-Path $projectDir 'dev/docker-compose.test.yml'))
$classes = 'RedisScriptsTest,RedisScriptLoadingIntegrationTest,SeckillMessageTest,VoucherOrderReliabilityTest,MockPaymentTest,LoginCodeTest,AccessGuardTest,CacheLayersIntegrationTest'
$log = Join-Path $projectDir 'target/phase6-script-loading-tests.log'
$started = $false
Push-Location $projectDir
try {
  & docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker engine unavailable.' }
  $existing = @(& docker ps -aq --filter 'label=com.docker.compose.project=citymate-script-loading-p6')
  if ($LASTEXITCODE -ne 0 -or $existing.Count -gt 0) { throw 'Inspect existing dedicated test environment first.' }
  $started = $true
  & docker @composeArgs up -d --wait mysql redis memcached
  if ($LASTEXITCODE -ne 0) { throw 'Script-loading dependencies failed.' }
  $previous = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    & docker @composeArgs run --rm --no-deps test mvn -B "-Dtest=$classes" '-Dcitymate.script-loading-test=true' '-DargLine=-Xmx512m' '-Dspring.test.context.cache.maxSize=1' test *> $log
    $result = $LASTEXITCODE
  } finally { $ErrorActionPreference = $previous }
  Get-Content -LiteralPath $log -Tail 22
  if ($result -ne 0) { throw 'Script-loading checks failed; inspect log.' }
  $suites = @()
  foreach ($class in $classes.Split(',')) {
    $matches = @(Get-ChildItem (Join-Path $projectDir 'target/surefire-reports') -Filter "TEST-*.$class.xml")
    if ($matches.Count -ne 1) { throw "Missing unique JUnit report: $class" }
    [xml]$report = Get-Content -LiteralPath $matches[0].FullName -Raw
    $suite = $report.testsuite
    if ([int]$suite.tests -eq 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) { throw "Skipped or failed suite: $class" }
    $suites += @{ name = $suite.name; tests = [int]$suite.tests; failures = [int]$suite.failures; errors = [int]$suite.errors; skipped = [int]$suite.skipped }
  }
  $value = @{ generatedAt = [DateTimeOffset]::Now.ToString('o'); suites = $suites; status = 'SCRIPT_LOADING_CHECKS_PASSED' }
  [IO.File]::WriteAllText((Join-Path $projectDir 'target/phase6-script-loading-tests.json'), ($value | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
} finally {
  if ($started -and -not $KeepEnvironment) {
    & docker @composeArgs down -v
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated script-loading environment cleanup failed.' }
  }
  Pop-Location
}
