<#
.SYNOPSIS
  In-game test of the fork's hotswap with ZBHelloWorld, in an isolated test environment.

.DESCRIPTION
  Sets up a separate Zomboid data folder (-cachedir) and ZombieBuddy config dir under -TestDir,
  so your own mods, saves, options and mod_approvals.json are not touched. Nothing in the game
  folder is changed either: the fork jar is passed with -javaagent.

  Then it starts the game (Steam must be running), talks to ZombieBuddy's experimental Lua server
  on 127.0.0.1:4444 and checks, by reading MainScreenState's current bytecode, that ZBHelloWorld's
  patch is there after load, gone after off and back after on, five times, then the same after a
  live update to ZBHelloWorld-2x.jar. Results go to <TestDir>\gametest-result.txt; the game is
  closed at the end unless -KeepRunning.

  The Lua server is in experimental.jar, which only the test jar contains: build it in java\ with
  gradle shadowJarExperimental (the player jar from shadowJar has no Lua server).

  Both ZBHelloWorld jars are approved only in the test config; they are verified in BiteCheck.
#>
param(
    [string]$GameDir = "C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
    [string]$Jar = (Join-Path $PSScriptRoot "..\java\build\jdk25\libs\zombiebuddy-jumpstart-experimental.jar"),
    [string]$TestDir = "$env:USERPROFILE\Zomboid-jumpstart-test",
    [switch]$KeepRunning
)
$ErrorActionPreference = "Stop"
$t = $TestDir

# --- test environment -------------------------------------------------------------------------
New-Item -ItemType Directory -Force "$t\mods", "$t\.zombie_buddy" | Out-Null
if (-not (Test-Path "$t\mods\ZBHelloWorld")) {
    git clone -q --depth 1 https://github.com/zed-0xff/ZBHelloWorld "$t\mods\ZBHelloWorld"
}
Copy-Item (Resolve-Path $Jar) "$t\ZombieBuddy-fork.jar" -Force
$modsTxt = "VERSION = 1,`r`n`r`nmods`r`n{`r`n    mod = ZombieBuddy,`r`n    mod = ZBHelloWorld,`r`n}`r`n`r`nmaps`r`n{`r`n}`r`n"
[IO.File]::WriteAllText("$t\mods\default.txt", $modsTxt)
$agent = "-javaagent:$t\ZombieBuddy-fork.jar=hotswap=true,hotswap_lua=true,experimental,lua_server_port=4444,policy=deny-new,config_dir=$t\.zombie_buddy"
$startCmd = "@echo off`r`ncd /d `"$GameDir`"`r`n`".\jre64\bin\java.exe`" $agent -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Dzomboid.steam=1 -Dzomboid.znetlog=1 -XX:-CreateCoredumpOnCrash -XX:-OmitStackTraceInFastThrow -XX:+UseZGC -Xmx6000m -Djava.library.path=./win64/;./ -cp ./;projectzomboid.jar zombie.gameStates.MainScreenState -cachedir=$t > `"$t\game-out.txt`" 2>&1`r`n"
[IO.File]::WriteAllText("$t\start-test.cmd", $startCmd)

function GameProcess() { Get-Process java -ErrorAction SilentlyContinue | Where-Object { $_.Path -like "$GameDir*" } }

# --- test ---------------------------------------------------------------------------------------
$result = "$t\gametest-result.txt"
$cls    = "zombie.gameStates.MainScreenState"
$text   = "Hello World from ZBHelloWorld!"
$pkg    = "me.zed_0xff.zb_hello_world"
$jar2x  = "$t\mods\ZBHelloWorld\42\media\java\client\ZBHelloWorld-2x.jar".Replace('\', '/')

function Log($line) { $line | Tee-Object -FilePath $result -Append }
function Lua($code) {
    try {
        (Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:4444/lua?sandbox=false' `
            -Body $code -ContentType 'text/plain' -TimeoutSec 20).Content
    } catch { "ERR: $($_.Exception.Message)" }
}
function Has() { Lua "return ZombieBuddy.Hotswap.classHasString('$cls', '$text')" }
function Check($name, $actual, $expected) {
    $actual = "$actual".Trim()
    $ok = $actual -eq "$expected"
    Log ("{0,-4} {1}: {2}" -f ($(if ($ok) { "OK" } else { "FAIL" })), $name, $actual)
    if (-not $ok) { $script:failures++ }
}

Remove-Item $result -ErrorAction SilentlyContinue
$failures = 0
Log "gametest $(Get-Date -Format s)"

# both ZBHelloWorld jars are BiteCheck-verified; approve them in the test config only
$approvals = '{"formatVersion": 2, "mods": [' +
  '{"id": "ZBHelloWorld", "jar_hash": "73ea63e2d02099a1b5f4770c05d3e79cb63d9533e5095ca75f12dc21a136dbc8", "decision": true},' +
  '{"id": "ZBHelloWorld", "jar_hash": "817cde27c290801c6f121d1e0d1ba6fb8baa1c4a133d268e4927e55e3ccddba2", "decision": true}]}'
[IO.File]::WriteAllText("$t\.zombie_buddy\mod_approvals.json", $approvals)

if (-not (GameProcess)) {
    Start-Process cmd.exe -ArgumentList '/c', "`"$t\start-test.cmd`"" -WindowStyle Hidden
    Log "game started"
}

$deadline = (Get-Date).AddMinutes(4)
do { Start-Sleep 5; $status = Lua "return ZombieBuddy.Hotswap.status()" } until ($status -like '*ZBHelloWorld*' -or (Get-Date) -gt $deadline)
if ($status -notlike '*ZBHelloWorld*') {
    Log "ZBHelloWorld not loaded yet, activating it like the mod menu does"
    Lua 'local am = ActiveMods.getById("default") am:setModActive("ZombieBuddy", true) am:setModActive("ZBHelloWorld", true) saveModsFile() getCore():ResetLua("default", "modsChanged") return 1' | Out-Null
    $deadline = (Get-Date).AddMinutes(2)
    do { Start-Sleep 5; $status = Lua "return ZombieBuddy.Hotswap.status()" } until ($status -like '*ZBHelloWorld*' -or (Get-Date) -gt $deadline)
}
Log "status: $status"

Check "patch present after load" (Has) "true"
for ($i = 1; $i -le 5; $i++) {
    Check "off #$i"            (Lua "return ZombieBuddy.Hotswap.off('ZBHelloWorld')") '"OK ZBHelloWorld off"'
    Check "patch gone #$i"     (Has) "false"
    Check "on #$i"             (Lua "return ZombieBuddy.Hotswap.on('ZBHelloWorld')")  '"OK ZBHelloWorld on"'
    Check "patch back #$i"     (Has) "true"
}

Check "update to 2x jar"       (Lua "return ZombieBuddy.Hotswap.updateJar('$jar2x', '$pkg', 'ZBHelloWorld')") '"OK ZBHelloWorld updated"'
Check "patch present (v2)"     (Has) "true"
Check "off after update"       (Lua "return ZombieBuddy.Hotswap.off('ZBHelloWorld')") '"OK ZBHelloWorld off"'
Check "patch gone (v2)"        (Has) "false"
Check "on after update"        (Lua "return ZombieBuddy.Hotswap.on('ZBHelloWorld')")  '"OK ZBHelloWorld on"'
Check "patch back (v2)"        (Has) "true"
Log "status: $(Lua 'return ZombieBuddy.Hotswap.status()')"

Start-Sleep 5
$alive = [bool](GameProcess)
Check "game still running" $alive $true
Log "hotswap log lines:"
Select-String -Path "$t\game-out.txt" -Pattern 'hotswap' | ForEach-Object { Log ("  " + $_.Line) }
Log "failures: $failures"

if (-not $KeepRunning) {
    Lua 'getCore():quit() return 1' | Out-Null
    Start-Sleep 15
    GameProcess | Stop-Process -Force
}
exit $failures
