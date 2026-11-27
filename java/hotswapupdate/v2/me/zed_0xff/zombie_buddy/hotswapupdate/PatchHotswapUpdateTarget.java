package me.zed_0xff.zombie_buddy.hotswapupdate;

import java.util.function.Supplier;
import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * Same patch in both versions; only Suffix differs. Its call to Suffix is moved by the update.
 * Version 2 calls it through a lambda (invokedynamic) inside the advice that goes into the game class.
 */
@Patch(className = "testjar.HotswapUpdateTarget", methodName = "greet")
public class PatchHotswapUpdateTarget {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        Supplier<String> suffix = () -> Suffix.value();
        returnValue = returnValue + suffix.get();
    }
}
