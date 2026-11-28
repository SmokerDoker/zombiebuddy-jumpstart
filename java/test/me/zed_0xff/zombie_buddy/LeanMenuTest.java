package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Jumpstart fork: the lean main menu keeps only ZombieBuddy of the menu's mod list. */
public class LeanMenuTest {
    @Test
    void keepsOnlyZombieBuddyAsTheListWritesIt() {
        assertEquals(List.of("\\ZombieBuddy"), LeanMenu.keepOf(List.of("\\HereGoesTheSun", "\\ZombieBuddy", "\\Bandits2")));
        assertEquals(List.of("ZombieBuddy"), LeanMenu.keepOf(List.of("ZombieBuddy", "tsarslib")));
        assertEquals(List.of(), LeanMenu.keepOf(List.of("tsarslib")));
    }

    @Test
    void newGameLuaCompiles() throws Exception {
        // the game's Lua compiler (the game jar is on the test classpath only when running)
        Class<?> table;
        try {
            table = Class.forName("se.krka.kahlua.vm.KahluaTable");
        } catch (ClassNotFoundException e) {
            org.junit.jupiter.api.Assumptions.abort("no game jar");
            return;
        }
        Object env = Class.forName("se.krka.kahlua.j2se.KahluaTableImpl").getConstructor(java.util.Map.class)
                .newInstance(new java.util.HashMap<>());
        assertNotNull(Class.forName("se.krka.kahlua.luaj.compiler.LuaCompiler")
                .getMethod("loadstring", String.class, String.class, table).invoke(null, LeanMenu.NEW_GAME_LUA, "LeanMenu", env));
    }

    @Test
    void comparesIdsWithoutTheBackslash() {
        assertEquals(List.of("ZombieBuddy", "Bandits2"), LeanMenu.ids(List.of("\\ZombieBuddy", "Bandits2")));
    }
}
