package me.zed_0xff.zombie_buddy;

/**
 * Jumpstart fork: {@link Hotswap} as Lua sees it, {@code ZombieBuddy.Hotswap}.
 *
 * <p>Lua mods are not checked by anyone, so by default they may only read: {@link #status} and
 * {@link #stateOf}. Switching mods, loading or updating jars and the bytecode probe only work when
 * the game was started with the agent option {@code hotswap_lua=true}, which only automated tests
 * set. Players switch and install mods through Jumpstart, which talks to {@link Hotswap} in Java.
 *
 * <pre>
 *   print(ZombieBuddy.Hotswap.status())
 *   print(ZombieBuddy.Hotswap.stateOf("ZBHelloWorld"))
 *   -- only with hotswap_lua=true:
 *   print(ZombieBuddy.Hotswap.off("ZBHelloWorld"))
 *   print(ZombieBuddy.Hotswap.on("ZBHelloWorld"))
 *   print(ZombieBuddy.Hotswap.loadJar("C:/.../MyMod.jar", "com.example.mymod", "MyMod"))
 *   print(ZombieBuddy.Hotswap.updateJar("C:/.../MyMod-1.1.jar", "com.example.mymod", "MyMod"))
 * </pre>
 */
@Exposer.LuaClass(name = "ZombieBuddy.Hotswap")
public final class HotswapLua {
    static final String REFUSED = "FAILED Lua may not change Java mods; start the game with hotswap_lua=true for tests";

    private HotswapLua() {}

    /** Whether Lua may switch, load and update mods: only with the agent option hotswap_lua=true. */
    public static boolean luaMayChange() {
        String value = Agent.arguments.get("hotswap_lua");
        return value != null && !value.equalsIgnoreCase("false");
    }

    public static boolean isEnabled() {
        return Hotswap.isEnabled();
    }

    public static String status() {
        return Hotswap.status();
    }

    public static String stateOf(String mod) {
        return Hotswap.stateOf(mod);
    }

    public static String off(String mod) {
        return luaMayChange() ? Hotswap.off(mod) : REFUSED;
    }

    public static String on(String mod) {
        return luaMayChange() ? Hotswap.on(mod) : REFUSED;
    }

    public static String loadJar(String jarPath, String packageName, String modId) {
        return luaMayChange() ? Hotswap.loadJar(jarPath, packageName, modId) : REFUSED;
    }

    public static String updateJar(String jarPath, String packageName, String modId) {
        return luaMayChange() ? Hotswap.updateJar(jarPath, packageName, modId) : REFUSED;
    }

    /** Test probe (retransforms a class, so not for every Lua mod): see {@link Hotswap#classHasString}. */
    public static boolean classHasString(String className, String text) {
        return luaMayChange() && Hotswap.classHasString(className, text);
    }
}
