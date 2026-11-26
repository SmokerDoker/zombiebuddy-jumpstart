package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import zombie.ZomboidFileSystem;
import zombie.modding.ActiveMods;

/**
 * Jumpstart fork: a lean main menu. At start the game reads the main menu's mod list ("default")
 * and loads all its mods, although every game loads its own list again anyway: a server's, a save's,
 * or a new game's copy of "default". The menu loads only {@link #KEEP}; the "default" list itself
 * stays as it is (new games, saves without a list of their own and hosting read it), and the game
 * is told what is really loaded, so a game with other mods loads them as it always does.
 *
 * <p>The menu's "back" buttons ask ActiveMods.requiresResetLua("default"); while the lean menu is
 * what is loaded, the answer is no, so leaving a menu screen does not load every mod after all.
 *
 * <p>Off with {@code -Dzombiebuddy.fullMenu=true}.
 */
public final class LeanMenu {
    /** What the main menu loads of its list: ZombieBuddy's own Lua side. */
    static final Set<String> KEEP = Set.of("ZombieBuddy");

    private static boolean done;
    private static volatile List<String> lean;

    private LeanMenu() {
    }

    /**
     * ZomboidFileSystem.loadMods(String), on entry. True when it did the load itself (the game's
     * method is skipped): only the first load of "default", in the main menu.
     */
    public static boolean loadInstead(ZomboidFileSystem fs, String activeModsId) {
        if (done || !"default".equalsIgnoreCase(activeModsId) || Boolean.getBoolean("zombiebuddy.fullMenu")) {
            return false;
        }
        done = true;
        try {
            if (!zombie.core.Core.optionModsEnabled || zombie.network.GameClient.client) {
                return false;
            }
            long t0 = System.nanoTime();
            Method read = ZomboidFileSystem.class.getDeclaredMethod("readDefaultModsTxt");
            read.setAccessible(true);
            ActiveMods defaults = (ActiveMods) read.invoke(fs); // as the game does: ActiveMods "default"
            defaults.checkMissingMods();
            defaults.checkMissingMaps();
            ActiveMods menu = ActiveMods.getById("zombiebuddyMenu");
            menu.clear();
            for (String id : keepOf(defaults.getMods())) {
                menu.setModActive(id, true);
            }
            ActiveMods.setLoadedMods(menu);
            lean = ids(menu.getMods());
            Logger.info("lean main menu: loading " + menu.getMods().size() + " of " + defaults.getMods().size()
                    + " mods; the others load with the game you start or join (-Dzombiebuddy.fullMenu=true for all)");
            fs.loadMods(new ArrayList<>(menu.getMods()));
            Logger.info("lean main menu: mods loaded in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
            return true;
        } catch (Throwable t) {
            Logger.error("lean main menu failed, loading all mods: " + t);
            lean = null;
            return false;
        }
    }

    /** ActiveMods.requiresResetLua(mods), on entry: true skips it, and it answers false (nothing to load). */
    public static boolean keepLean(ActiveMods mods) {
        List<String> l = lean;
        if (l == null || mods == null || mods != ActiveMods.getById("default")) {
            return false; // not lean, or a game's list: loads as always
        }
        try {
            java.lang.reflect.Field field = ActiveMods.class.getDeclaredField("s_loaded");
            field.setAccessible(true);
            ActiveMods loaded = (ActiveMods) field.get(null);
            if (!ids(loaded.getMods()).equals(l)) {
                lean = null; // a game loaded its mods since: from now on as the game decides
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            lean = null;
            return false;
        }
    }

    static List<String> keepOf(List<String> mods) {
        List<String> keep = new ArrayList<>();
        for (String id : mods) {
            if (KEEP.contains(strip(id))) {
                keep.add(id);
            }
        }
        return keep;
    }

    /** Mod ids without the leading backslash Build 42 writes in some lists. */
    static List<String> ids(List<String> mods) {
        List<String> out = new ArrayList<>();
        for (String m : mods) {
            out.add(strip(m));
        }
        return out;
    }

    private static String strip(String id) {
        return id != null && id.startsWith("\\") ? id.substring(1) : String.valueOf(id);
    }
}
