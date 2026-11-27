package me.zed_0xff.zombie_buddy.hotswappatches;

import java.util.function.Supplier;
import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * Advice-only patch, so with hotswap=true it can be switched off and on (test_hotswap). Built into
 * the class when it loads, with a lambda (invokedynamic) and a private field of the patch in the
 * advice: switching off rebuilds the class without them, switching on puts them back.
 */
@Patch(className = "testjar.HotswapTarget", methodName = "greet")
public class PatchHotswapTarget {
    private static int calls;

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        calls++;
        String text = " +patched";
        Supplier<String> mark = () -> text;
        returnValue = returnValue + mark.get();
    }
}
