package me.zed_0xff.zombie_buddy.hotswapmainfail;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Must never be applied: the mod's main() fails during the live load. */
@Patch(className = "testjar.MainFailTarget", methodName = "greet")
public class PatchMainFailTarget {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + " +mainfail";
    }
}
