param([switch]$KeepEnvironment)
$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$runId = [Guid]::NewGuid().ToString('N')
$exchange = Join-Path $projectDir ('target/expiry-profile/' + $runId)
$composeArgs = @('compose', '-p', 'citymate-expiry-p6', '-f', (Join-Path $projectDir 'dev/docker-compose.test.yml'))
$app = 'citymate-expiry-p6-app'
$imageTag = 'citymate-expiry-profile:' + $runId
$started = $false
$imageBuilt = $false
$appStarted = $false
Push-Location $projectDir
try {
  & docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) { throw 'Docker engine unavailable.' }
  $existing = @(& docker ps -aq --filter 'label=com.docker.compose.project=citymate-expiry-p6')
  if ($LASTEXITCODE -ne 0 -or $existing.Count -gt 0) { throw 'Inspect existing dedicated JAR profile environment first.' }
  $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,18091)
  try { $listener.Start() } finally { $listener.Stop() }
  New-Item -ItemType Directory -Path $exchange -Force | Out-Null
  $started = $true
  $buildLog = Join-Path $exchange 'build.log'
  $previous = $ErrorActionPreference
  try {
    $ErrorActionPreference = 'Continue'
    & docker @composeArgs run --rm --no-deps test mvn -B '-DskipTests' package *> $buildLog
    $buildExit = $LASTEXITCODE
  } finally { $ErrorActionPreference = $previous }
  Get-Content -LiteralPath $buildLog -Tail 12
  if ($buildExit -ne 0) { throw 'Executable JAR build failed.' }
  & python (Join-Path $PSScriptRoot 'prepare-packaged-profile.py') $exchange $runId
  if ($LASTEXITCODE -ne 0) { throw 'Artifact verification failed.' }
  & docker build -t $imageTag (Join-Path $exchange 'image')
  if ($LASTEXITCODE -ne 0) { throw 'Runtime image build failed.' }
  $imageBuilt = $true
  $override = @"
services:
  mq-nameserver:
    networks: [mq]
  rocketmq:
    networks: [mq]
  mq-init:
    networks: [mq]
  app:
    image: $imageTag
    container_name: $app
    ports: ['127.0.0.1:18091:8081']
    networks: [default, mq]
    environment:
      SPRING_DATASOURCE_URL: 'jdbc:mysql://mysql:3306/hmdp_optimization_test?useSSL=false&serverTimezone=UTC&characterEncoding=utf8'
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: isolation_test_only
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: '6379'
      SPRING_REDIS_PASSWORD: ''
      HMDP_MEMCACHE_ENABLED: 'true'
      HMDP_MEMCACHE_HOST: memcached
      HMDP_MEMCACHE_TIMEOUT_MS: '1000'
      HMDP_MQ_ENABLED: 'true'
      HMDP_MQ_SECKILL_ENABLED: 'true'
      HMDP_MQ_NAMESERVER: 'mq-nameserver:9876'
      HMDP_MQ_GROUP_PREFIX: citymate-expiry-p6
      HMDP_CDC_ENABLED: 'false'
      HMDP_ANALYTICS_ENABLED: 'false'
      CITYMATE_AI_ENABLED: 'false'
      CITYMATE_RELIABILITY_SCHEDULING_ENABLED: 'true'
      CITYMATE_RELIABILITY_ORDER_TIMEOUT_MINUTES: '1'
      CITYMATE_CATALOG_ADMIN_IDS: '1010'
      HMDP_MONITOR_TOKEN: p6-profile-local-only
      LOGGING_LEVEL_COM_HMDP: info
networks:
  mq:
    internal: true
"@
  $overridePath = Join-Path $exchange 'compose.runtime.yml'
  [IO.File]::WriteAllText($overridePath,$override,(New-Object Text.UTF8Encoding($false)))
  $composeArgs += @('-f',$overridePath)
  $started = $true
  & docker @composeArgs up -d --wait mysql redis memcached rocketmq
  if ($LASTEXITCODE -ne 0) { throw 'JAR runtime dependencies failed.' }
  & docker @composeArgs run --rm --no-deps mq-init
  if ($LASTEXITCODE -ne 0) { throw 'Topic initialization failed.' }
  & docker @composeArgs up -d --no-deps app
  if ($LASTEXITCODE -ne 0) { throw 'JAR runtime start failed.' }
  $appStarted = $true
  $deadline = [DateTime]::UtcNow.AddMinutes(4)
  $healthy = $false
  while ([DateTime]::UtcNow -lt $deadline) {
    try {
      $health = Invoke-RestMethod -Uri 'http://127.0.0.1:18091/actuator/health' -TimeoutSec 3
      if ($health.status -eq 'UP') { $healthy = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
  }
  if (-not $healthy) { throw 'Packaged application did not become healthy.' }
  $raw = & docker inspect $app
  if ($LASTEXITCODE -ne 0) { throw 'Cannot verify runtime container.' }
  $runtime = @($raw | ConvertFrom-Json)[0]
  if ($runtime.Path -ne 'java' -or $runtime.Args -notcontains '-jar') { throw 'Runtime is not a standalone java -jar process.' }
  $bindMounts = @($runtime.Mounts | Where-Object { $_.Type -eq 'bind' })
  if ($bindMounts.Count -ne 0) { throw 'Runtime unexpectedly mounts host files.' }
  $jvmText = & docker exec $app sh -c 'java -version 2>&1'
  if ($LASTEXITCODE -ne 0) { throw 'Cannot read runtime JVM version.' }
  $runtimeMeta = @{ runId=$runId; imageId=$runtime.Image; runtimePath=$runtime.Path; runtimeArgs=$runtime.Args; bindMountCount=0; javaVersion=($jvmText | Out-String).Trim(); appHasTestJvm=$false; appHasMavenParent=$false }
  [IO.File]::WriteAllText((Join-Path $exchange 'runtime.json'),($runtimeMeta | ConvertTo-Json -Depth 6),(New-Object Text.UTF8Encoding($false)))
  & python (Join-Path $PSScriptRoot 'verify-expiry-crash.py') --work-dir $exchange --run-id $runId --port 18091
  if ($LASTEXITCODE -ne 0) { throw 'Packaged application reconciliation failed.' }
  $report = Get-Content -LiteralPath (Join-Path $exchange 'application.json') -Raw | ConvertFrom-Json
  if (-not $report.passed -or $report.runId -ne $runId -or $report.cases.Count -ne 1 -or $report.executionMode -ne 'packaged-jar-expiry-crash') { throw 'Missing or stale packaged profile report.' }
  $archive = Join-Path $projectDir ('docs/expiry-recoveries/' + $runId)
  New-Item -ItemType Directory -Path $archive | Out-Null
  foreach ($file in @('application.json','981001-evidence.json','artifact.json','runtime.json')) {
    Copy-Item -LiteralPath (Join-Path $exchange $file) -Destination (Join-Path $archive $file)
  }
  $last = @{runId=$runId;archive=$archive}
  [IO.File]::WriteAllText((Join-Path $projectDir 'target/expiry-profile/last-run.json'),($last | ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
  Write-Output "Expiry interruption recovery verified: $runId"
} finally {
  if ($appStarted) {
    $previous = $ErrorActionPreference
    try {
      $ErrorActionPreference = 'Continue'
      & docker logs $app *> (Join-Path $exchange 'application.log')
      Get-Content -LiteralPath (Join-Path $exchange 'application.log') -Tail 8
    } finally { $ErrorActionPreference = $previous }
  }
  if ($started -and -not $KeepEnvironment) {
    & docker @composeArgs down -v
    if ($LASTEXITCODE -ne 0) { Write-Warning 'Dedicated JAR profile cleanup failed.' }
  }
  if ($imageBuilt -and -not $KeepEnvironment) {
    $meta = & docker image inspect $imageTag
    if ($LASTEXITCODE -eq 0 -and (@($meta | ConvertFrom-Json)[0].Config.Labels.'citymate.profile.run-id' -eq $runId)) {
      & docker image rm $imageTag
    }
  }
  Pop-Location
}
