package zombie.jstest;

/**
 * Jumpstart fork, journal test: stands in for a game class (package zombie.*), whose static fields
 * a mod writes. Switching the mod off must put the original values back.
 */
public final class WriteTarget {
    public static String VALUE = "orig";
    public static String REFLECTED = "orig";
    /** Game objects whose instance fields a mod writes. */
    public static final WriteTarget ONE = new WriteTarget();
    public static final WriteTarget TWO = new WriteTarget();

    public String name = "orig";
    public int speed = 1;

    private WriteTarget() {}
}
