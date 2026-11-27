package libshared;

/** A library both test mods bring, in another version each. */
public final class Lib {
    private Lib() {}

    public static String value() {
        return "lib-b";
    }
}
