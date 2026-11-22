package me.zed_0xff.zombie_buddy.hotswappatches;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Advice-only patch, so with hotswap=true it can be switched off and on (test_hotswap). */
@Patch(className = "testjar.HotswapTarget", methodName = "greet")
public class PatchHotswapTarget {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + " +patched";
    }
}
