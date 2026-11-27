package me.zed_0xff.zombie_buddy.hotswaplive;

import java.util.function.Supplier;
import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * Not loaded at start: test_hotswap loads this jar at runtime with Hotswap.load. The target class
 * is loaded already, so the advice goes in by retransforming it, with a lambda (invokedynamic) and
 * a private field of the patch.
 */
@Patch(className = "testjar.HotswapLiveTarget", methodName = "greet")
public class PatchHotswapLiveTarget {
    private static SuffixSource source = new LiveSuffix();

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        Supplier<String> suffix = () -> source.value();
        returnValue = returnValue + suffix.get();
    }
}
