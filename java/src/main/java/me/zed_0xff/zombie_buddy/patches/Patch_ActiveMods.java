package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.LeanMenu;
import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.modding.ActiveMods;

/** Jumpstart fork: while the lean main menu is loaded, "default" needs no reload (see LeanMenu). */
public class Patch_ActiveMods {
    @Patch(className = "zombie.modding.ActiveMods", methodName = "requiresResetLua")
    public static class Patch_requiresResetLua {
        /** True skips the method; a skipped boolean method returns false: nothing to load. */
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(ActiveMods mods) {
            return LeanMenu.keepLean(mods);
        }
    }
}
