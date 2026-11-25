package me.zed_0xff.zombie_buddy;

/**
 * Jumpstart fork: the crash protection ({@link ModGuard}) as Lua sees it, {@code ZombieBuddy.Guard}.
 * Read-only: Lua mods are not checked by anyone, so they cannot switch or reset anything here.
 *
 * <pre>
 *   print(ZombieBuddy.Guard.status())
 *   print(ZombieBuddy.Guard.modStatus("ZBCrashTest"))   -- "count TAB state" or ""
 * </pre>
 */
@Exposer.LuaClass(name = "ZombieBuddy.Guard")
public final class GuardLua {
    private GuardLua() {}

    public static String status() {
        return ModGuard.statusText();
    }

    public static String modStatus(String modId) {
        return Hotswap.guardStatus(modId);
    }

    public static String lastSeq() {
        return Hotswap.guardLastSeq();
    }
}
