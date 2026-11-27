package testjar;

/** Target for the library clash test (test_hotswap): two mods patch one method each. */
public class LibClashTarget {
    public String a() {
        return "a";
    }

    public String b() {
        return "b";
    }
}
