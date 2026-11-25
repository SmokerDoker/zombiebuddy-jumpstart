package me.zed_0xff.zombie_buddy.hotswapjournal;

import me.zed_0xff.zombie_buddy.Exposer;

/** A Lua class of the mod: must be taken out of the Lua registries when the mod is off. */
@Exposer.LuaClass(name = "HotswapJournal.Api")
public class JournalApi {
    public static String hello() {
        return "hello";
    }
}
