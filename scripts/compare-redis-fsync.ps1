param()

$ErrorActionPreference = 'Stop'
$projectDir = Split-Path $PSScriptRoot -Parent
$pairId = [Guid]::NewGuid().ToString('N')
$results = @()
$previousRun = $null
Push-Location $projectDir
try {
  foreach ($mode in @('always','everysec')) {
    Write-Output "Starting isolated diagnostic mode: $mode"
    & (Join-Path $PSScriptRoot 'measure-independent-transaction.ps1') -RedisFsync $mode -NoUpdateLatest
    $manifest = Get-Content -LiteralPath 'target/transaction-profile/last-run.json' -Raw | ConvertFrom-Json
    if ($manifest.redisFsync -ne $mode -or $manifest.runId -eq $previousRun) {
      throw 'Missing or stale diagnostic run manifest.'
    }
    $app = Get-Content -LiteralPath (Join-Path $manifest.archive 'application.json') -Raw | ConvertFrom-Json
    if (-not $app.passed -or $app.redisFsync -ne $mode -or $app.redisAppendOnly -ne 'yes') {
      throw 'Actual Redis config or reconciliation invalid.'
    }
    $results += @{ mode=$mode; runId=$manifest.runId; archive=('docs/transaction-profiles/' + $manifest.runId) }
    $previousRun=$manifest.runId
  }
  $pair = @{ pairId=$pairId; generatedAt=[DateTimeOffset]::UtcNow.ToString('o'); runs=$results;
    deliberateVariable='isolated Redis appendfsync'; productionDurabilityChanged=$false;
    diagnosticOnly=$true; repeatedPair=$false }
  $archiveDir=Join-Path $projectDir ('docs/fsync-comparisons/' + $pairId)
  New-Item -ItemType Directory -Path $archiveDir | Out-Null
  $json=$pair | ConvertTo-Json -Depth 6
  [IO.File]::WriteAllText((Join-Path $archiveDir 'pair.json'),$json,(New-Object Text.UTF8Encoding($false)))
  [IO.File]::WriteAllText((Join-Path $projectDir 'docs/phase6-fsync-comparison-pair.json'),$json,(New-Object Text.UTF8Encoding($false)))
  Write-Output "Both isolated modes passed. Pair id: $pairId"
} finally { Pop-Location }
