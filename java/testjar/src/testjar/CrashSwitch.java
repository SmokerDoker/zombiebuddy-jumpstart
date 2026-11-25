package testjar;

/** Jumpstart fork, crash-protection tests: makes the test patches throw on demand. */
public final class CrashSwitch {
    public static volatile boolean enter;

    private CrashSwitch() {}
}
