package me.zed_0xff.zombie_buddy.hotswaplive;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Not loaded at start: test_hotswap loads this jar at runtime with Hotswap.load. */
@Patch(className = "testjar.HotswapLiveTarget", methodName = "greet")
public class PatchHotswapLiveTarget {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + new LiveSuffix().value();
    }
}
