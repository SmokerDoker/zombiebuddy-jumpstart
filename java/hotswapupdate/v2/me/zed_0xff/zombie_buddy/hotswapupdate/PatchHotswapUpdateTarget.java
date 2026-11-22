package me.zed_0xff.zombie_buddy.hotswapupdate;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Same patch in both versions; only Suffix differs. Its call to Suffix is moved by the update. */
@Patch(className = "testjar.HotswapUpdateTarget", methodName = "greet")
public class PatchHotswapUpdateTarget {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + Suffix.value();
    }
}
