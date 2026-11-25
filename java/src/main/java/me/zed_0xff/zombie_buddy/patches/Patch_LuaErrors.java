package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.ModGuard;
import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * Jumpstart fork: Kahlua turns a Java exception thrown under a Lua call into a Lua error, so it
 * does not crash the game. Every such error passes debugException; if a Java mod's class is on its
 * stack, the crash protection counts it against that mod (reported only, never switched off).
 */
@Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "debugException")
public class Patch_LuaErrors {
    @Patch.OnEnter
    public static void enter(Throwable t) {
        ModGuard.attribute(t, "lua", "Lua call");
    }
}
