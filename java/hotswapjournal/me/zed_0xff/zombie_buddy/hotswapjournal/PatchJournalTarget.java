package me.zed_0xff.zombie_buddy.hotswapjournal;

import me.zed_0xff.zombie_buddy.annotations.Patch;

@Patch(className = "testjar.JournalTarget", methodName = "greet")
public class PatchJournalTarget {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + " +journal";
    }
}
