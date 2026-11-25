package me.zed_0xff.zombie_buddy.hotswapjournal;

import se.krka.kahlua.integration.annotations.LuaMethod;

/** A global Lua function of the mod. */
public class JournalGlobals {
    @LuaMethod(name = "hotswapJournalVersion", global = true)
    public String version() {
        return "1";
    }
}
