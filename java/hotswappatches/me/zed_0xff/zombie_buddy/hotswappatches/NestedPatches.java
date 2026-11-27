package me.zed_0xff.zombie_buddy.hotswappatches;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/**
 * A patch nested in a class that is no patch itself, reading a private field of it (the usual
 * layout of ZombieBuddy mods). The two are nestmates; the advice inside the game class is not.
 */
public class NestedPatches {
    private static int answer = 42;

    @Patch(className = "testjar.HotswapTarget", methodName = "answer")
    public static class Patch_answer {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) int returnValue) {
            returnValue = answer;
        }
    }
}
