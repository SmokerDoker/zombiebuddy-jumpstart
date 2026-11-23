# Testing the fork in the game

Goal: switch a Java mod's patch off and on in the running game, without a restart. Test mod:
[ZBHelloWorld](https://github.com/zed-0xff/ZBHelloWorld), one Advice patch on
`MainScreenState.renderBackground` that draws "Hello World from ZBHelloWorld!" in the top-left
corner of the main menu. It is verified in the BiteCheck registry.

## Automated: `tools\gametest.ps1`

```powershell
cd java
gradle -PgameClasspath="C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid\projectzomboid.jar" shadowJarExperimental
cd ..
.\tools\gametest.ps1
```

The test needs `java\build\jdk25\libs\zombiebuddy-jumpstart-experimental.jar`: the player jar plus
`experimental.jar`, which has the Lua server the test talks to. The player jar from `shadowJar`
leaves `experimental.jar` out; with it, the agent option `experimental` only logs that it is ignored.

Runs the whole test without clicking and without touching your own setup: a separate data folder
(`%USERPROFILE%\Zomboid-jumpstart-test`, passed as `-cachedir`), its own ZombieBuddy config, and
the fork jar passed with `-javaagent` (the game folder stays as it is). Steam must be running.
It drives the game through ZombieBuddy's experimental Lua server on `127.0.0.1:4444` and checks
`ZombieBuddy.Hotswap.classHasString("zombie.gameStates.MainScreenState", "Hello World from ZBHelloWorld!")`
after load, five off/on rounds, and a live update to `ZBHelloWorld-2x.jar`. The result is in
`gametest-result.txt` in that folder; the exit code is the number of failed checks.

Do not call anything that needs the OpenGL context from that Lua server (for example
`getCore():TakeScreenshot()`): it runs on the game's main thread and crashes the game.

The manual steps below are still useful to see the text appear and disappear on screen.

## 1. Build

In `java/` with JDK 25:

```powershell
gradle -PgameClasspath="C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid\projectzomboid.jar" shadowJar
```

The jar is `java\build\jdk25\libs\zombiebuddy-jumpstart.jar`.

## 2. Install

```powershell
.\tools\install-dev.ps1 -WithHelloWorld -LuaConsole
```

This backs up `ZombieBuddy.jar` and `ProjectZomboid64.json`, puts the fork jar in place, adds
`hotswap=true,hotswap_lua=true` to the `zbNative` agent options and clones ZBHelloWorld into your mods
folder. `hotswap_lua=true` lets the Lua console below switch mods; without `-LuaConsole` Lua may only
read the state (`status`, `stateOf`), because Lua mods are not checked by anyone.
Add `-debug` to the Steam launch options to get the Lua console.

## 3. Test

1. Start the game. The log (`%USERPROFILE%\Zomboid\console.txt`) and the main menu watermark should
   say `ZombieBuddy v… (Jumpstart fork)`.
2. Mods: enable ZombieBuddy and ZBHelloWorld, approve the jar when ZombieBuddy asks. Back on the
   main menu the Hello World text is in the top-left corner.
3. Load or start a game, open the Lua console (debug menu) and run:

   ```lua
   print(ZombieBuddy.Hotswap.status())
   ```

   Expected: `hotswap on` and a line for ZBHelloWorld with `on, switchable`.
4. Switch it off:

   ```lua
   print(ZombieBuddy.Hotswap.off("ZBHelloWorld"))
   ```

   Expected: `OK ZBHelloWorld off`. Quit to the main menu: the text is gone, without a restart.
5. Load the game again, run `print(ZombieBuddy.Hotswap.on("ZBHelloWorld"))`, quit to the main menu:
   the text is back.
6. Repeat a few times; then check `console.txt` for `ERROR` lines from ZombieBuddy.

Please note for each step what you saw, and copy `console.txt` if something is off.

## 4. Undo

```powershell
.\tools\install-dev.ps1 -Uninstall
```

Restores the original `ZombieBuddy.jar` and `ProjectZomboid64.json`. The ZBHelloWorld folder in
`Zomboid\mods` stays; delete it by hand if you want.
