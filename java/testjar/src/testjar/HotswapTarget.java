package testjar;

/** Target for the Jumpstart fork's hotswap test (test_hotswap). Loaded by the test, after premain. */
public class HotswapTarget {
    public String greet() {
        return "vanilla";
    }
}
