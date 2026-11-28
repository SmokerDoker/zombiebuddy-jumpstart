package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.LeanMenu;
import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Jumpstart fork: after the client's Lua the lean main menu's New Game loads the list first (see LeanMenu). */
public class Patch_LuaManager {
    @Patch(className = "zombie.Lua.LuaManager", methodName = "LoadDirBase")
    public static class Patch_LoadDirBase {
        @Patch.OnExit
        public static void exit(String sub) {
            LeanMenu.afterLuaDir(sub);
        }
    }
}
