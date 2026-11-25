# ZombieBuddy, Jumpstart fork

This is a fork of [ZombieBuddy](https://github.com/zed-0xff/ZombieBuddy) by Andrey "Zed" Zaikin
(MIT license, see [LICENSE.txt](LICENSE.txt)). It adds what
[Jumpstart](https://github.com/SmokerDoker/jumpstart-pz) needs to switch Java mods on and off
while Project Zomboid runs. It is not made or supported by Zed. Every Java mod written for
ZombieBuddy works unchanged; mod_approvals.json, agent options and the mod API stay as upstream.

## What is different

| Change | Files |
| --- | --- |
| `PatchEngine.applyPatches` returns a `PatchHandle` instead of discarding the installed transformers | `PatchEngine.java`, `Loader.java` |
| Agent option `hotswap=true`: Advice-only mod jars get one retransformable transformer that does not change the class format, for loaded and not-yet-loaded classes | `PatchEngine.java` |
| `Hotswap`: switch a mod's patches off and on at runtime. Lua (`ZombieBuddy.Hotswap`) may only read the state; switching, loading and updating from Lua needs the agent option `hotswap_lua=true` (tests), because Lua mods are not checked | `Hotswap.java`, `HotswapLua.java` |
| `Hotswap.load` (Lua: `loadJar`): load a mod jar at runtime, only if mod_approvals.json allows its SHA-256 | `Hotswap.java` |
| `Hotswap.update` (Lua: `updateJar`): replace a loaded mod with a new version; the new classes are moved to package `jsv<n>.<package>` so they do not clash with the old ones | `Hotswap.java`, `JarRelocator.java` |
| `Hotswap.classHasString` (Lua too): does the bytecode a loaded class runs right now contain a string; used to check that a patch is in or out | `Hotswap.java` |
| Bug fix: a merged mod jar's classes are defined supertypes first; before, a class implementing an interface from the same jar could fail with NoClassDefFoundError depending on hash map order | `ModJarInjector.java` |
| No self-update from an upstream, Zed-signed ZombieBuddy.jar, so the fork is not silently replaced | `SelfUpdater.java`, `ForkInfo.java` |
| Version string says "Jumpstart fork" (log, watermark) | `ZombieBuddy.java` |
| Test `test_hotswap`: a patch on a class loaded after premain, switched off and on 50 times; a jar loaded at runtime only after approval; a mod updated v1 → v2 → v1 at runtime | `build.gradle`, `hotswappatches/`, `hotswaplive/`, `hotswapupdate/`, `test_hotswap/`, `testjar/src/testjar/Hotswap*Target.java` |
| No `byte-buddy-agent` in the jar: ZombieBuddy is always started with `-javaagent` and never attaches itself, so the attach code and its `attach_hotspot_windows.dll` files are left out | `build.gradle` |
| No `experimental.jar` in the player jar (`shadowJar`); the automated in-game tests, which need its Lua server, use `zombiebuddy-jumpstart-experimental.jar` from `shadowJarExperimental`. The agent option `experimental` with the player jar logs one line and is ignored | `build.gradle`, `Agent.java`, `tools/gametest.ps1`, `TESTING.md` |
| No `PatchAnnotationProcessor` in the player jar: it checks `@Patch` classes at compile time and never runs in the game; the build still uses it from the compiled classes | `build.gradle` |
| Self-update code removed (jar signature check, replacing the running jar): with self-update off it never ran; the log line and watermark stay as before | `SelfUpdater.java`, `ForkInfo.java` |
| Ed25519 signatures (`.jar.zbs`, authors.json) are checked with the JDK (`java.security`, Java 15+) instead of Bouncy Castle, which is now only a test dependency; same keys, same results | `ZBSVerifier.java`, `KnownAuthors.java`, `build.gradle`, `ZBSVerifierTest.java`, `KnownAuthorsTest.java` |
| A mod loaded or updated live gets its Lua classes and global functions (`@LuaClass`, `@LuaMethod(global = true)`) right away, not only at the next Lua start; before, a mod whose Lua checks for its Java part at once (Knox Inventory) said its Java part was missing. In-game test: `tools\lualivetest.ps1` in jumpstart-pz | `Exposer.java`, `Hotswap.java` |
| Embedded mod: an agent jar whose manifest has `ZombieBuddy-Embedded-Mod: <package>` loads that mod from its own entries under `embedded-mod/` when the mods load, like a mod jar (patches, Lua classes, `Main.main`). Jumpstart ships as one `Jumpstart.jar` this way: the fork, Jumpstart and its BiteCheck classes. Stored as plain entries, not a jar in the jar, so reviewers (BiteCheck) see every class | `EmbeddedMod.java`, `Loader.java`, `EmbeddedModTest.java` |
| Crash protection (`ModGuard`): the new pipeline step `guard` gives every Advice method of a mod `suppress = Throwable`, and `PatchEngine` installs `ModGuard.adviceFailed` as the handler. A throwing patch no longer ends the game; the game method runs on. Errors are credited to the mod, logged with a rate limit and written once per mod and session to `<config_dir>/guard/`. Agent options `guard=off\|log\|switch` (default `switch` with hotswap, else `log`), `guard_limit` (default 3), `guard_exempt=<pkg,...>`. With `switch` a mod is switched off after more than `guard_limit` errors in 60 s, at its first error in the 120 s after a live load or update, or at once for a `LinkageError`; a trip wire at the start of each advice (`ModGuard.isOff`) silences it at once, also without hotswap. A `main()` that fails during a live load refuses the mod. Errors in ZombieBuddy callbacks, Lua calls (`KahluaThread.debugException`) and MethodDelegation patches are credited to the mod too (reported only). ZombieBuddy's own patches and the embedded mod are never switched off | `ModGuard.java`, `transformers/asmtree/AdviceGuard.java`, `PatchEngine.java`, `DelegationWatch.java`, `patches/Patch_LuaErrors.java`, `Callbacks.java`, `Loader.java`, `Hotswap.java`, `GuardLua.java` |
| Clean removal (`ModJournal`): what a mod registers (Lua classes, global functions and methods via `Exposer`, ZombieBuddy callbacks) is recorded per jar. `Hotswap.off` takes it back, `on` registers it again; `update` takes the old version's registrations back; `Hotswap.forget` is for uninstall. Lua values are only taken back while they are still the mod's (identity), and on the game's main thread. Before, `afterExposeAll` put a switched-off mod's Lua classes back at every Lua start | `ModJournal.java`, `Exposer.java`, `Callbacks.java`, `Hotswap.java`, `Agent.java` |
| Java writes: the pipeline step `writes` reports `PUTSTATIC` into game classes (`zombie/`, `se/krka/`, `fmod/`, `astar/`) and static `Field.set*` to `ModWrites`, which puts the original value back when the mod is switched off (if nobody changed it since), and file writes (`Files.write*`, `newOutputStream`, `newBufferedWriter`, `new FileOutputStream/FileWriter`) to `ModIO`, which logs them to `<config_dir>/guard/files-<modId>.txt` | `transformers/asmtree/JavaWrites.java`, `ModWrites.java`, `ModIO.java`, `transformers/Pipeline.java` |
| For Jumpstart (Strings only, via reflection): `Hotswap.guardLastSeq()`, `guardEvents(sinceSeq)` (tab-separated `seq, modId, kind, where, exception, action`), `guardStatus(modId)` (`count TAB ok\|probation\|off-by-guard`), `forget(modId)`; Lua, read-only: `ZombieBuddy.Guard.status()`, `modStatus(modId)` | `Hotswap.java`, `GuardLua.java` |
| Tests: `test_hotswap` (crash jar loaded at start, broken static initializer and failing `main()` loaded live, journal with Lua registrations, callbacks, static and reflective writes and a file, update leaves only the new version's Lua class), `test_guard` (trip wire without hotswap); in-game test mod `ZBCrashTest` (`gradle zbCrashTestMod`) checked by `tools/gametest.ps1` | `hotswapcrash/`, `hotswapcrashlive/`, `hotswapmainfail/`, `hotswapjournal/`, `test_hotswap/`, `test_guard/`, `zbcrashtest/`, `testjar/src/`, `build.gradle`, `tools/gametest.ps1` |

Without `hotswap=true` the fork patches exactly like upstream.

## Try it in the game

Add `hotswap=true` to the agent options, for example
`-javaagent:ZombieBuddy.jar=hotswap=true,hotswap_lua=true --` (the second option only for trying it from Lua), then in the debug console:

```lua
print(ZombieBuddy.Hotswap.status())
print(ZombieBuddy.Hotswap.off("ZBHelloWorld"))
print(ZombieBuddy.Hotswap.on("ZBHelloWorld"))
print(ZombieBuddy.Hotswap.loadJar("C:/path/to/MyMod.jar", "com.example.mymod", "MyMod"))
print(ZombieBuddy.Hotswap.updateJar("C:/path/to/MyMod-1.1.jar", "com.example.mymod", "MyMod"))
```

Step-by-step and automated in-game test: [TESTING.md](TESTING.md). CI proposal: [tools/ci](tools/ci/README.md).

## Limits

- A mod's classes stay defined until restart. Its patches are switched, and what it registered
  through ZombieBuddy (Lua exposures, callbacks) and its static writes into game classes are taken
  back by the journal; what its Lua did (events, globals, files) is Jumpstart's job.
- Mods with MethodDelegation patches keep their patches until restart.
- With `hotswap=true`, an Advice patch that needs to change the class format (rare) cannot be
  applied; the log says which class, and `status()` shows the mod as incomplete.
- Regular mod jars are defined in ZombieBuddy's class loader, so a second version with the same
  class names cannot be loaded next to the first. `Hotswap.load` refuses that case;
  `Hotswap.update` moves the new version's classes to `jsv<n>.<package>` instead. Class names a
  mod builds as strings (for example for `Class.forName`) are not moved, and the old version's
  classes and static state stay in memory until restart.
- `update` runs the new version's `Main` class and takes back what the old version registered
  through ZombieBuddy; Lua events the old version's Lua added are Jumpstart's job.
- Crash protection cannot catch: threads a mod starts, MethodDelegation patches (credited only),
  Java classes the game calls directly (a mod's UI element), static initializers run outside its
  advice and `main()`, errors the mod causes later in game code (no mod class on the stack),
  `OutOfMemoryError`, endless loops and native crashes. An advice that throws half-way may leave
  a game object half-changed. A mod that throws on purpose to abort a game method needs
  `guard_exempt`.
- The journal undoes only what goes through ZombieBuddy (Exposer, Callbacks) and static writes
  into game classes. Lua globals, events and files a mod's Lua sets are Jumpstart's job; instance
  fields of game objects, `VarHandle` writes and `@FieldValue(readOnly = false)` are not tracked.
- `Hotswap.load` never prompts. Approve the jar first (ZombieBuddy's dialog at a normal start,
  BiteCheck, or Jumpstart writing mod_approvals.json).

## Building

```sh
cd java
gradle -PgameClasspath=<ProjectZomboid>/projectzomboid.jar test_vanilla test_patched test_hotswap test_guard
```

Needs JDK 25. `unitTest` has one upstream test (`JavaModInfoTest`) that expects macOS paths and
fails on Windows.

## Staying close to upstream

Fork commits live on top of upstream's `main`. To take a new upstream release:
`git fetch upstream && git rebase upstream/master`. The changes are kept small and in separate
commits so they can be offered upstream as a pull request at any time.
