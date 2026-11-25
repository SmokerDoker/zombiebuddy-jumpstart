package me.zed_0xff.zombie_buddy.hotswapcrashlive;

/** A helper whose static initializer always fails: ExceptionInInitializerError on first use. */
public final class Bomb {
    public static final String VALUE = make();

    private Bomb() {}

    private static String make() {
        if (System.nanoTime() != 0) {
            throw new IllegalStateException("broken static initializer");
        }
        return " +bomb";
    }
}
