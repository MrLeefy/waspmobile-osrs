# Starts one outbound Wasp bridge per account and optional process supervisor.
# Run under the logged-in Windows account using Node.js 18+.
param(
  [string]$Profiles = "$PSScriptRoot\worker-profiles.json",
  [string]$HostConfig = "$PSScriptRoot\worker-host.json",
  [switch]$BridgeOnly
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path -LiteralPath $Profiles -PathType Leaf)) {
  throw 'Copy worker-profiles.example.json to worker-profiles.json and configure secrets.'
}
$node = (Get-Command node.exe -ErrorAction Stop).Source
$cfg = Get-Content -Raw -LiteralPath $Profiles | ConvertFrom-Json
if (!$cfg.controllerUrl -or $cfg.controllerUrl -notmatch '^https://') {
  throw 'Controller URL must be a private HTTPS address.'
}
if (!$cfg.accounts -or $cfg.accounts.Count -lt 1) { throw 'No accounts configured.' }
$seen = @{}
foreach ($a in $cfg.accounts) {
  if ($a.id -cnotmatch '^[a-z0-9][a-z0-9_-]{0,31}$' -or $seen.ContainsKey($a.id)) {
    throw 'Invalid or duplicate account ID.'
  }
  $seen[$a.id] = $true
  if (!$a.workerToken -or $a.workerToken.Length -lt 32) { throw 'Worker token is missing or too short.' }
  if (!(Test-Path -LiteralPath $a.swarmDir -PathType Container)) {
    throw ('Swarm directory missing for ' + $a.id)
  }
}
$bridgePath = Join-Path $PSScriptRoot 'worker.js'
foreach ($a in $cfg.accounts) {
  $env:CONTROL_URL = $cfg.controllerUrl
  $env:CONTROL_ACCOUNT_ID = $a.id
  $env:WASP_BOT_ID = $a.id
  $env:CONTROL_WORKER_TOKEN = $a.workerToken
  $env:SWARM_DIR = $a.swarmDir
  $quoted = '"' + $bridgePath + '"'
  $p = Start-Process -FilePath $node -ArgumentList $quoted -PassThru -WindowStyle Hidden
  Write-Host ('Started Wasp bridge for ' + $a.id + ' as PID ' + $p.Id)
}
Remove-Item Env:\CONTROL_WORKER_TOKEN -ErrorAction SilentlyContinue
Remove-Item Env:\CONTROL_URL -ErrorAction SilentlyContinue
Remove-Item Env:\CONTROL_ACCOUNT_ID -ErrorAction SilentlyContinue
Remove-Item Env:\WASP_BOT_ID -ErrorAction SilentlyContinue
Remove-Item Env:\SWARM_DIR -ErrorAction SilentlyContinue
if (!$BridgeOnly) {
  if (!(Test-Path -LiteralPath $HostConfig -PathType Leaf)) { throw 'Missing local worker-host.json. Copy the example first.' }
  $env:WASP_SUPERVISOR_CONFIG = $HostConfig
  $supervisorPath = Join-Path $PSScriptRoot 'supervisor.js'
  $quoted = '"' + $supervisorPath + '"'
  $p = Start-Process -FilePath $node -ArgumentList $quoted -PassThru -WindowStyle Hidden
  Remove-Item Env:\WASP_SUPERVISOR_CONFIG -ErrorAction SilentlyContinue
  Write-Host ('Started host-local supervisor as PID ' + $p.Id)
}
Write-Host 'Bridge processes stay running independently of this PowerShell window and the phone.'
