package me.zed_0xff.zombie_buddy.hotswapupdate;

import me.zed_0xff.zombie_buddy.Exposer;

/** Exposed to Lua in both versions: after an update only the active version's class may stay exposed. */
@Exposer.LuaClass(name = "HotswapUpdate.Api")
public class UpdateApi {
    public static String version() {
        return "v2";
    }
}
