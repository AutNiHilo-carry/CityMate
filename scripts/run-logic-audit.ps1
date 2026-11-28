param([ValidateSet('reproduce','regression','broker','redis-reproduce')][string]$Mode='regression')
$ErrorActionPreference='Stop'
$projectDir=Split-Path $PSScriptRoot -Parent
$runId=[Guid]::NewGuid().ToString('N')
$exchange=Join-Path $projectDir ('target/logic-audit/'+$runId)
$project='citymate-logic-audit'
$compose=@('compose','-p',$project,'-f',(Join-Path $projectDir 'dev/docker-compose.test.yml'))
$started=$false
Push-Location $projectDir
try {
  & docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) {throw 'Docker unavailable.'}
  $existing=@(& docker ps -aq --filter "label=com.docker.compose.project=$project")
  if ($LASTEXITCODE -ne 0 -or $existing.Count -gt 0) {throw 'Dedicated audit environment already exists.'}
  New-Item -ItemType Directory -Force -Path (Join-Path $exchange 'generated-docs') | Out-Null
  $docsMount=(Join-Path $exchange 'generated-docs').Replace('\','/')
  $override=@"
services:
  test:
    volumes:
      - '${docsMount}:/workspace/docs'
"@
  $overridePath=Join-Path $exchange 'compose.audit.yml'
  [IO.File]::WriteAllText($overridePath,$override,(New-Object Text.UTF8Encoding($false)))
  $compose+=@('-f',$overridePath)
  if ($Mode -eq 'reproduce') {$tests='AccessGuardRoutingTest,OrderExpiryBatchTest,VoucherOrderReliabilityTest#nonexistentOrdinaryVoucherDoesNotCommitItsSerializationSlot,MetricsPathSecurityTest'}
  elseif ($Mode -eq 'redis-reproduce') {$tests='SeckillCompensationIntegrationTest#invalidNumericStockDoesNotDestroyTheOwnerNeededForRetry'}
  elseif ($Mode -eq 'broker') {$tests='RocketMqIntegrationTest,KafkaBehaviorIntegrationTest,CacheCdcIntegrationTest,CanalPauseRecoveryIntegrationTest,ExperienceIntegrationTest'}
  else {$tests='*Test,!RedissonTest,!CacheBenchmarkIntegrationTest,!CacheCdcIntegrationTest,!CanalPauseRecoveryIntegrationTest,!KafkaBehaviorIntegrationTest,!RocketMqIntegrationTest,!ExperienceIntegrationTest,!IndependentTransactionProfileIntegrationTest,!TransactionBaselineIntegrationTest'}
  if ($Mode -ne 'reproduce') {
    $started=$true
    if ($Mode -eq 'broker') {
      & docker @compose up -d --wait mysql redis memcached rocketmq kafka canal
      if ($LASTEXITCODE -ne 0) {throw 'Broker dependencies failed.'}
      & docker @compose run --rm --no-deps mq-init
      if ($LASTEXITCODE -ne 0) {throw 'MQ topics failed.'}
      & docker @compose run --rm --no-deps kafka-init
    } else {& docker @compose up -d --wait mysql redis memcached}
    if ($LASTEXITCODE -ne 0) {throw 'Isolated dependencies failed.'}
  }
  $logPath=Join-Path $exchange 'maven.log'
  $testStarted=[DateTime]::UtcNow
  $prior=$ErrorActionPreference
  try {
    $ErrorActionPreference='Continue'
    $auditCommand = @'
set -eu
mkdir -p /tmp/citymate-audit/docs
cp -a /workspace/pom.xml /workspace/config /workspace/src /tmp/citymate-audit/
cd /tmp/citymate-audit
set +e
mvn -B -Dtest=__TESTS__ -DargLine=-Xmx512m -Dspring.test.context.cache.maxSize=1 -Dcitymate.script-loading-test=true test
result=$?
mkdir -p /workspace/target/logic-audit/__RUN__/reports
cp -a target/surefire-reports/. /workspace/target/logic-audit/__RUN__/reports/ 2>/dev/null
cp -a docs/. /workspace/target/logic-audit/__RUN__/generated-docs/ 2>/dev/null
exit $result
'@
    $auditCommand=$auditCommand.Replace('__TESTS__',$tests).Replace('__RUN__',$runId).Replace("`r",'')
    & docker @compose run --rm --no-deps test sh -c $auditCommand *> $logPath
    $testExit=$LASTEXITCODE
  } finally {$ErrorActionPreference=$prior}
  New-Item -ItemType Directory -Force -Path (Join-Path $exchange 'reports') | Out-Null
  Get-Content -LiteralPath $logPath -Tail 20
  $result=@{runId=$runId;mode=$Mode;exitCode=$testExit;tests=$tests;generatedDocsIsolated=$true;containerSourceSnapshot=$true}
  [IO.File]::WriteAllText((Join-Path $exchange 'result.json'),($result | ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
  Write-Output "AUDIT_RUN=$runId MODE=$Mode EXIT=$testExit"
  if ($testExit -ne 0) {throw 'Audit tests have failures; see fresh run reports.'}
} finally {
  if ($started) {& docker @compose down -v}
  Pop-Location
}
