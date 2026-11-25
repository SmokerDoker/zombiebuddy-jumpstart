package me.zed_0xff.zb_crash_test;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * Throws in the main menu's render while armed: without the crash protection the game would end
 * in the middle of a frame (like the Viewpoint case). With it, the frame goes on and the mod is
 * switched off after more than guard_limit errors in a minute.
 */
@Patch(className = "zombie.gameStates.MainScreenState", methodName = "render")
public class PatchRender {
    public static volatile long armedUntil;

    @Patch.OnEnter
    public static void enter() {
        if (System.currentTimeMillis() < armedUntil) {
            throw new IllegalStateException("ZBCrashTest: armed");
        }
    }
}
