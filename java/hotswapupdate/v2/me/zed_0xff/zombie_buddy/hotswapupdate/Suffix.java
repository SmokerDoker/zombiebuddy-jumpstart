package me.zed_0xff.zombie_buddy.hotswapupdate;

/**
 * Version 2 of a helper the patch calls, so the test sees which version is active. Its structure
 * differs from version 1 (a new field, a new method, a nested class reading a private member of
 * its host, so the two are nestmates): an update loads it beside the old one, nothing is redefined.
 */
public final class Suffix {
    private static final String TEXT = " +v2";

    private Suffix() {}

    public static String value() {
        return Part.text();
    }

    private static String raw() {
        return TEXT;
    }

    static final class Part {
        static String text() {
            return raw();
        }
    }
}
