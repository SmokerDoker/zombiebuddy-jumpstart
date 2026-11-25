package me.zed_0xff.zombie_buddy.hotswapcrash;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import testjar.CrashSwitch;

/**
 * Loaded at start (patches_jar). The enter advice throws while CrashSwitch.enter is set; the crash
 * protection must swallow it, count it against this mod and switch the mod off after too many. The
 * skipOn enter and the read-write @Return exit also check that the trip wire keeps frames valid.
 */
@Patch(className = "testjar.CrashTarget", methodName = "greet")
public class PatchCrashTarget {
    @Patch.OnEnter(skipOn = true)
    public static boolean enter() {
        if (CrashSwitch.enter) {
            throw new NullPointerException("crash test");
        }
        return false;
    }

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + " +crash";
    }
}
