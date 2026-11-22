<#
.SYNOPSIS
  Puts the Jumpstart fork of ZombieBuddy into a Project Zomboid install for testing, and takes it out again.

.DESCRIPTION
  Install (default):
    - backs up ZombieBuddy.jar as ZombieBuddy.jar.upstream-backup (once; an existing backup is kept)
    - copies the fork jar from java\build\jdk25\libs over ZombieBuddy.jar
    - backs up ProjectZomboid64.json and changes "-agentlib:zbNative" to "-agentlib:zbNative=hotswap=true"
    - with -LuaConsole: also hotswap_lua=true, so the Lua console may switch mods (manual tests only;
      without it Lua can only read the state)
    - with -WithHelloWorld: clones zed-0xff/ZBHelloWorld into %USERPROFILE%\Zomboid\mods\ZBHelloWorld
  -Uninstall restores both backups.

  Steam keeps ZombieBuddy.jar as it is, but the ZombieBuddy installer or a Workshop update can put an
  upstream jar back; run this script again after either.

.EXAMPLE
  .\tools\install-dev.ps1 -WithHelloWorld -LuaConsole
  .\tools\install-dev.ps1 -Uninstall
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$Jar = (Join-Path $PSScriptRoot "..\java\build\jdk25\libs\zombiebuddy-jumpstart.jar"),
    [switch]$WithHelloWorld,
    [switch]$LuaConsole,
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"

$zbJar      = Join-Path $GameDir "ZombieBuddy.jar"
$zbBackup   = "$zbJar.upstream-backup"
$json       = Join-Path $GameDir "ProjectZomboid64.json"
$jsonBackup = "$json.upstream-backup"
$plainArg   = '"-agentlib:zbNative"'
$hotswapArg = $(if ($LuaConsole) { '"-agentlib:zbNative=hotswap=true,hotswap_lua=true"' } else { '"-agentlib:zbNative=hotswap=true"' })

if (-not (Test-Path $GameDir)) { throw "Project Zomboid not found at $GameDir (use -GameDir)" }

if ($Uninstall) {
    if (Test-Path $zbBackup)   { Copy-Item $zbBackup $zbJar -Force;   Remove-Item $zbBackup;   "restored ZombieBuddy.jar" }
    else                       { "no ZombieBuddy.jar backup, left as is" }
    if (Test-Path $jsonBackup) { Copy-Item $jsonBackup $json -Force; Remove-Item $jsonBackup; "restored ProjectZomboid64.json" }
    else                       { "no ProjectZomboid64.json backup, left as is" }
    return
}

$Jar = (Resolve-Path $Jar).Path
if (-not (Test-Path $zbJar)) { throw "no ZombieBuddy.jar in $GameDir; install ZombieBuddy first" }

if (-not (Test-Path $zbBackup)) { Copy-Item $zbJar $zbBackup; "backed up ZombieBuddy.jar" }
Copy-Item $Jar $zbJar -Force
"copied fork jar: $Jar"

$text = Get-Content $json -Raw
if ($text.Contains($hotswapArg)) {
    "ProjectZomboid64.json already has $hotswapArg"
} elseif ($text -match '"-agentlib:zbNative=hotswap=true[^"]*"') {
    [System.IO.File]::WriteAllText($json, ($text -replace '"-agentlib:zbNative=hotswap=true[^"]*"', $hotswapArg))
    "ProjectZomboid64.json: agent options now $hotswapArg"
} elseif ($text.Contains($plainArg)) {
    if (-not (Test-Path $jsonBackup)) { Copy-Item $json $jsonBackup; "backed up ProjectZomboid64.json" }
    [System.IO.File]::WriteAllText($json, $text.Replace($plainArg, $hotswapArg))
    "ProjectZomboid64.json: $plainArg -> $hotswapArg"
} else {
    Write-Warning "ProjectZomboid64.json has no $plainArg entry. Add hotswap=true to the zbNative agent options by hand."
}

if ($WithHelloWorld) {
    $modDir = Join-Path $env:USERPROFILE "Zomboid\mods\ZBHelloWorld"
    if (Test-Path $modDir) { "ZBHelloWorld already in $modDir" }
    else { git clone --depth 1 https://github.com/zed-0xff/ZBHelloWorld $modDir; "cloned ZBHelloWorld into $modDir" }
}

"done. Start the game with -debug (Steam launch options) to get the Lua console."
