$ErrorActionPreference='Stop'
$projectDir=Split-Path $PSScriptRoot -Parent
$runId=[Guid]::NewGuid().ToString('N')
$folder=Join-Path $projectDir ('target/local-releases/'+$runId)
$jar=Join-Path $projectDir 'target/citymate-0.0.1-SNAPSHOT.jar'
$buildCompose=@('compose','-p','citymate-release-build','-f',(Join-Path $projectDir 'dev/docker-compose.test.yml'))
$buildStarted=$false
$installed=$false
function Resolve-CitymateHostBind([string]$Source) {
  # Docker Desktop may report its Linux host-mount spelling for the same Windows path.
  if ($Source -match '^/run/desktop/mnt/host/([a-zA-Z])/(.+)$') {
    $Source=$Matches[1]+':/'+$Matches[2]
  }
  return [IO.Path]::GetFullPath($Source.Replace('/','\')).TrimEnd('\')
}
function Read-CitymateContainer([string]$Name) {
  $text=& docker inspect $Name
  if ($LASTEXITCODE -ne 0) {throw "Cannot inspect $Name"}
  $item=@($text | ConvertFrom-Json)[0]
  if ($item.Config.Labels.'com.docker.compose.project' -ne 'citymate') {throw 'Unexpected project label.'}
  return $item
}
function Wait-CitymateHealthy([string[]]$Names,[int]$Seconds) {
  $deadline=[DateTime]::UtcNow.AddSeconds($Seconds)
  while ([DateTime]::UtcNow -lt $deadline) {
    $ready=$true
    foreach ($name in $Names) {
      $item=Read-CitymateContainer $name
      if ($item.State.Status -ne 'running' -or ($item.State.Health -and $item.State.Health.Status -ne 'healthy')) {$ready=$false}
    }
    if ($ready) {return}
    Start-Sleep -Seconds 3
  }
  throw ('Dependencies did not become ready: '+($Names -join ', '))
}
Push-Location $projectDir
try {
  & docker info --format '{{.ServerVersion}}'
  if ($LASTEXITCODE -ne 0) {throw 'Docker is unavailable.'}
  if (-not (Test-Path -LiteralPath (Join-Path $projectDir '.env'))) {throw 'Existing local .env is required.'}
  $javaBefore=Read-CitymateContainer 'citymate-java-1'
  $mysqlBefore=Read-CitymateContainer 'citymate-mysql-1'
  $redisBefore=Read-CitymateContainer 'citymate-redis-1'
  if ($javaBefore.Config.Cmd -notcontains '-jar') {throw 'Expected the existing prebuilt-JAR runtime.'}
  $bind=@($javaBefore.Mounts | Where-Object { $_.Destination -eq '/workspace' })
  if ($bind.Count -ne 1 -or $bind[0].Type -ne 'bind' -or (Resolve-CitymateHostBind $bind[0].Source) -ne [IO.Path]::GetFullPath($projectDir).TrimEnd('\')) {throw 'Unexpected application workspace mount.'}
  $existing=@(& docker ps -aq --filter 'label=com.docker.compose.project=citymate-release-build')
  if ($existing.Count -gt 0) {throw 'Inspect an existing release-build environment first.'}
  New-Item -ItemType Directory -Force -Path $folder | Out-Null
  if (Test-Path -LiteralPath $jar) {Copy-Item -LiteralPath $jar -Destination (Join-Path $folder 'previous.jar')}
  $command=@'
set -eu
mkdir -p /tmp/citymate-release
cp -a /workspace/pom.xml /workspace/config /workspace/src /tmp/citymate-release/
cd /tmp/citymate-release
mvn -B -DskipTests package
cp target/citymate-0.0.1-SNAPSHOT.jar /workspace/target/local-releases/__RUN__/release.jar
'@
  $command=$command.Replace('__RUN__',$runId).Replace("`r",'')
  $prior=$ErrorActionPreference
  $buildStarted=$true
  try {
    $ErrorActionPreference='Continue'
    & docker @buildCompose run --rm --no-deps test sh -c $command *> (Join-Path $folder 'build.log')
    $buildExit=$LASTEXITCODE
  } finally {$ErrorActionPreference=$prior}
  Get-Content (Join-Path $folder 'build.log') -Tail 12
  if ($buildExit -ne 0) {throw 'Release build failed; running JAR has not been replaced.'}
  & python (Join-Path $PSScriptRoot 'verify-local-release.py') $folder
  if ($LASTEXITCODE -ne 0) {throw 'Artifact verification failed; running JAR has not been replaced.'}
  if ($javaBefore.State.Status -eq 'running') {
    & docker stop --time 20 citymate-java-1
    if ($LASTEXITCODE -ne 0) {throw 'Could not stop the old Java process.'}
  }
  Copy-Item -LiteralPath (Join-Path $folder 'release.jar') -Destination $jar -Force
  $installed=$true
  foreach ($name in @('citymate-mysql-1','citymate-redis-1','citymate-memcached-1','citymate-mq-nameserver-1')) {
    $null=Read-CitymateContainer $name
    & docker start $name
    if ($LASTEXITCODE -ne 0) {throw "Cannot start $name"}
  }
  Wait-CitymateHealthy @('citymate-mysql-1','citymate-redis-1','citymate-mq-nameserver-1') 120
  foreach ($name in @('citymate-rocketmq-1','citymate-kafka-1','citymate-canal-1')) {
    $null=Read-CitymateContainer $name
    & docker start $name
    if ($LASTEXITCODE -ne 0) {throw "Cannot start $name"}
  }
  Wait-CitymateHealthy @('citymate-rocketmq-1','citymate-kafka-1','citymate-canal-1') 240
  & (Join-Path $PSScriptRoot 'start-citymate-ai.ps1')
  $javaAfter=Read-CitymateContainer 'citymate-java-1'
  $port=$javaAfter.HostConfig.PortBindings.'8081/tcp'[0].HostPort
  $deadline=[DateTime]::UtcNow.AddMinutes(4)
  $healthy=$false
  while ([DateTime]::UtcNow -lt $deadline) {
    try {if ((Invoke-RestMethod -Uri "http://127.0.0.1:$port/actuator/health" -TimeoutSec 3).status -eq 'UP') {$healthy=$true;break}} catch {}
    Start-Sleep -Seconds 2
  }
  if (-not $healthy) {throw 'Updated Java did not become healthy.'}
  $null=Read-CitymateContainer 'citymate-web-1'
  & docker start citymate-web-1
  if ($LASTEXITCODE -ne 0) {throw 'Cannot start existing web container.'}
  $mysqlAfter=Read-CitymateContainer 'citymate-mysql-1'
  $redisAfter=Read-CitymateContainer 'citymate-redis-1'
  if ($mysqlBefore.Id -ne $mysqlAfter.Id -or $redisBefore.Id -ne $redisAfter.Id) {throw 'Database containers unexpectedly changed.'}
  $meta=@{runId=$runId;status='JAVA_UPDATED_HEALTHY';apiPort=$port;previousContainerId=$javaBefore.Id;currentContainerId=$javaAfter.Id;javaCommand=$javaAfter.Config.Cmd;databaseContainersPreserved=$true;mysqlVolumes=@($mysqlAfter.Mounts | Where-Object Type -eq 'volume' | Select-Object Name,Destination);redisVolumes=@($redisAfter.Mounts | Where-Object Type -eq 'volume' | Select-Object Name,Destination);previousJarSha256=(Get-FileHash -LiteralPath (Join-Path $folder 'previous.jar') -Algorithm SHA256).Hash.ToLower();installedJarSha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLower();sourceAudit='docs/logic-audit-verification.json'}
  [IO.File]::WriteAllText((Join-Path $folder 'deployment.json'),($meta | ConvertTo-Json -Depth 6),(New-Object Text.UTF8Encoding($false)))
  [IO.File]::WriteAllText((Join-Path $projectDir 'target/local-releases/last-run.json'),(@{runId=$runId;folder=$folder} | ConvertTo-Json),(New-Object Text.UTF8Encoding($false)))
  Write-Output "Local audit release updated and healthy: $runId"
} catch {
  if ($installed) {Write-Warning "New JAR installed, but rollout incomplete. Check target/local-releases/$runId; previous.jar is retained. No automatic data rollback."}
  throw
} finally {
  if ($buildStarted) {& docker @buildCompose down -v}
  Pop-Location
}
