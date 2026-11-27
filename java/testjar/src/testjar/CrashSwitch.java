package testjar;

/** Jumpstart fork, crash-protection tests: makes the test patches throw on demand. */
public final class CrashSwitch {
    public static volatile boolean enter;
    /** A callback the crash test mod registers, like a mod hands the game a listener. */
    public static volatile java.util.function.Supplier<String> helper;

    private CrashSwitch() {}
}
