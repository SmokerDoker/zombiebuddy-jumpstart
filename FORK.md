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
| `Hotswap`: switch a mod's patches off and on at runtime, also from Lua as `ZombieBuddy.Hotswap` | `Hotswap.java` |
| `Hotswap.load` (Lua: `loadJar`): load a mod jar at runtime, only if mod_approvals.json allows its SHA-256 | `Hotswap.java` |
| `Hotswap.update` (Lua: `updateJar`): replace a loaded mod with a new version; the new classes are moved to package `jsv<n>.<package>` so they do not clash with the old ones | `Hotswap.java`, `JarRelocator.java` |
| No self-update from an upstream, Zed-signed ZombieBuddy.jar, so the fork is not silently replaced | `SelfUpdater.java`, `ForkInfo.java` |
| Version string says "Jumpstart fork" (log, watermark) | `ZombieBuddy.java` |
| Test `test_hotswap`: a patch on a class loaded after premain, switched off and on 50 times; a jar loaded at runtime only after approval; a mod updated v1 → v2 → v1 at runtime | `build.gradle`, `hotswappatches/`, `hotswaplive/`, `hotswapupdate/`, `test_hotswap/`, `testjar/src/testjar/Hotswap*Target.java` |

Without `hotswap=true` the fork patches exactly like upstream.

## Try it in the game

Add `hotswap=true` to the agent options, for example
`-javaagent:ZombieBuddy.jar=hotswap=true --`, then in the debug console:

```lua
print(ZombieBuddy.Hotswap.status())
print(ZombieBuddy.Hotswap.off("ZBHelloWorld"))
print(ZombieBuddy.Hotswap.on("ZBHelloWorld"))
print(ZombieBuddy.Hotswap.loadJar("C:/path/to/MyMod.jar", "com.example.mymod", "MyMod"))
print(ZombieBuddy.Hotswap.updateJar("C:/path/to/MyMod-1.1.jar", "com.example.mymod", "MyMod"))
```

Step-by-step test: [TESTING.md](TESTING.md). CI proposal: [tools/ci](tools/ci/README.md).

## Limits

- Only patches are switched. A mod's classes stay defined, and what its `main()` did at load
  (Lua exposures, event handlers, files) stays. Undoing that is Jumpstart's job.
- Mods with MethodDelegation patches keep their patches until restart.
- With `hotswap=true`, an Advice patch that needs to change the class format (rare) cannot be
  applied; the log says which class, and `status()` shows the mod as incomplete.
- Regular mod jars are defined in ZombieBuddy's class loader, so a second version with the same
  class names cannot be loaded next to the first. `Hotswap.load` refuses that case;
  `Hotswap.update` moves the new version's classes to `jsv<n>.<package>` instead. Class names a
  mod builds as strings (for example for `Class.forName`) are not moved, and the old version's
  classes and static state stay in memory until restart.
- `update` runs the new version's `Main` class. Whatever the old `main()` registered (Lua events,
  exposures) is not undone; that is Jumpstart's job.
- `Hotswap.load` never prompts. Approve the jar first (ZombieBuddy's dialog at a normal start,
  BiteCheck, or Jumpstart writing mod_approvals.json).

## Building

```sh
cd java
gradle -PgameClasspath=<ProjectZomboid>/projectzomboid.jar test_vanilla test_patched test_hotswap
```

Needs JDK 25. `unitTest` has one upstream test (`JavaModInfoTest`) that expects macOS paths and
fails on Windows.

## Staying close to upstream

Fork commits live on top of upstream's `main`. To take a new upstream release:
`git fetch upstream && git rebase upstream/main`. The changes are kept small and in separate
commits so they can be offered upstream as a pull request at any time.
