package libshared;

/** The same in both versions of the library: it must still call the version of its own jar. */
public final class Helper {
    private Helper() {}

    public static String value() {
        return Lib.value();
    }
}
