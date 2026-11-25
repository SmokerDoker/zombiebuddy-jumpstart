package me.zed_0xff.zombie_buddy.hotswapcrashlive;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Loaded live: its exit advice touches a class whose static initializer fails (a broken mod). */
@Patch(className = "testjar.CrashLiveTarget", methodName = "greet")
public class PatchCrashLiveTarget {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + Bomb.VALUE;
    }
}
