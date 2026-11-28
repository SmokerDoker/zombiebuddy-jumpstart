package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import se.krka.kahlua.luaj.compiler.LuaCompiler;
import zombie.Lua.LuaManager;
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
 * <p>The menu's New Game takes the "default" list as it is and never reloads Lua (with the full menu
 * the mods were loaded already), so a new game's first session would run without them. After the
 * client's Lua is loaded, {@link #NEW_GAME_LUA} makes New Game load the list first: the game's own
 * reload for a new game's mods (the Mods button in New Game does the same), after which the game
 * opens New Game again. Here in Java, not in ZombieBuddy's Lua: a ZombieBuddy subscribed in the
 * Workshop may be the folder the game loads.
 *
 * <p>Off with {@code -Dzombiebuddy.fullMenu=true}.
 */
public final class LeanMenu {
    /** What the main menu loads of its list: ZombieBuddy's own Lua side. */
    static final Set<String> KEEP = Set.of("ZombieBuddy");

    private static boolean done;
    private static volatile boolean wasLean;
    private static volatile List<String> lean;

    /** MainScreen's menu click, wrapped: Solo (New Game) loads the list first when it is not loaded. */
    static final String NEW_GAME_LUA = """
            if not MainScreen or not MainScreen.onMenuItemMouseDownMainMenu or MainScreen.zbLeanNewGame then return end
            MainScreen.zbLeanNewGame = true
            local menuItemDown = MainScreen.onMenuItemMouseDownMainMenu
            MainScreen.onMenuItemMouseDownMainMenu = function(item, x, y)
                local ms = MainScreen.instance
                if item and item.internal == "SOLO" and ms and not ms.inGame and (ms.delay or 0) <= 0
                        and not ms.tutorialButton and not ms.checkSavefileModal and getCore():getOptionModsEnabled() then
                    local current = ActiveMods.getById("currentGame")
                    current:copyFrom(ActiveMods.getById("default"))
                    if ActiveMods.requiresResetLua(current) then
                        print("[ZB] lean main menu: New Game loads the mods of the list first")
                        getSoundManager():playUISound("UIActivateMainMenuItem")
                        getCore():ResetLua("currentGame", "NewGameMods")
                        return
                    end
                end
                return menuItemDown(item, x, y)
            end
            """;

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
            wasLean = true;
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

    /**
     * LuaManager.LoadDirBase(sub), on exit: once the client's Lua is loaded (before the main menu is
     * built from it), New Game is wrapped ({@link #NEW_GAME_LUA}). Again after every Lua reset; the
     * wrapper does nothing once the list is loaded.
     */
    public static void afterLuaDir(String sub) {
        if (!wasLean || !"client".equals(sub) || zombie.network.GameServer.server || LuaManager.env == null) {
            return;
        }
        try {
            Object[] r = LuaManager.caller.pcall(LuaManager.thread,
                    LuaCompiler.loadstring(NEW_GAME_LUA, "ZombieBuddy LeanMenu", LuaManager.env));
            if (r == null || r.length == 0 || !Boolean.TRUE.equals(r[0])) {
                Logger.error("lean main menu: New Game is not wrapped: " + (r != null && r.length > 1 ? r[1] : "no result"));
            }
        } catch (Throwable t) {
            Logger.error("lean main menu: New Game is not wrapped: " + t);
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
