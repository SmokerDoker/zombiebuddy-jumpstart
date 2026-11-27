package me.zed_0xff.zombie_buddy.libclashb;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Appends what the library this jar brought says (test_hotswap, LibraryClashTest). */
@Patch(className = "testjar.LibClashTarget", methodName = "b")
public class PatchLibClashB {
    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) String returnValue) {
        returnValue = returnValue + " " + libshared.Helper.value();
    }
}
