package me.zed_0xff.zombie_buddy.hotswapcrash;

import java.util.function.Supplier;

/**
 * Not a patch: a callback the mod hands out (CrashSwitch.helper), like a listener a mod registers in
 * the game. With the mod off, the trip wire in get() returns null at once and counts the call.
 */
public class CrashHelper implements Supplier<String> {
    @Override
    public String get() {
        return "helper";
    }

    @Override
    public String toString() {
        return "CrashHelper"; // no trip wire: the game's collections may still print it
    }
}
