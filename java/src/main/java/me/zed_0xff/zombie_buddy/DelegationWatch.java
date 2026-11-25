package me.zed_0xff.zombie_buddy;

import net.bytebuddy.asm.Advice;

/**
 * Jumpstart fork: exit advice around a method a mod replaced with MethodDelegation. It cannot stop
 * the exception (the mod's code is the method now), but it credits it to the mod first, so the
 * crash report and Jumpstart name the mod. The exception goes on as before.
 */
public final class DelegationWatch {
    private DelegationWatch() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.Thrown Throwable thrown, @Advice.Origin("#t.#m") String where) {
        if (thrown != null) {
            ModGuard.attribute(thrown, "delegation", where);
        }
    }
}
