package me.zed_0xff.zb_crash_test;

import se.krka.kahlua.integration.annotations.LuaMethod;

/** Global Lua functions of ZBCrashTest; gone while the mod is off (the guard's journal takes them back). */
public class CrashLua {
    /** Makes the render patch throw for the given number of seconds. */
    @LuaMethod(name = "zbCrashArm", global = true)
    public boolean arm(double seconds) {
        PatchRender.armedUntil = System.currentTimeMillis() + (long) (seconds * 1000);
        return true;
    }

    /** The static field Main wrote into the game (zombie.network.GameClient.ip). */
    @LuaMethod(name = "zbCrashWrites", global = true)
    public String writes() {
        return zombie.network.GameClient.ip;
    }
}
