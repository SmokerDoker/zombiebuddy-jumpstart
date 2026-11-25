package me.zed_0xff.zb_crash_test;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * Throws at the end of every UI frame while armed (Core.EndFrameUI runs in every game state, the
 * main menu's render does not): without the crash protection the game would end in the middle of a
 * frame (like the Viewpoint case). With it, the frame goes on and the mod is switched off after more
 * than guard_limit errors in a minute.
 */
@Patch(className = "zombie.core.Core", methodName = "EndFrameUI")
public class PatchRender {
    public static volatile long armedUntil;
    /** Render calls seen by the patch, so a test can tell "not armed" from "not running". */
    public static volatile long calls;

    @Patch.OnEnter
    public static void enter() {
        calls++;
        if (System.currentTimeMillis() < armedUntil) {
            throw new IllegalStateException("ZBCrashTest: armed");
        }
    }
}
