package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.HotswapLua;

/** Without hotswap_lua=true (as in test_hotswap), Lua may read but not change anything. */
class HotswapLuaTest {
    private static final String REFUSED = "FAILED Lua may not change Java mods; start the game with hotswap_lua=true for tests";

    @Test
    void luaMayOnlyRead() {
        assertFalse(HotswapLua.luaMayChange());
        assertTrue(HotswapLua.status().startsWith("hotswap on"));
        assertEquals("not loaded", HotswapLua.stateOf("NoSuchMod"));
        assertEquals(REFUSED, HotswapLua.off("HotswapTest"));
        assertEquals(REFUSED, HotswapLua.on("HotswapTest"));
        assertEquals(REFUSED, HotswapLua.loadJar("x.jar", "x", "X"));
        assertEquals(REFUSED, HotswapLua.updateJar("x.jar", "x", "X"));
        assertFalse(HotswapLua.classHasString("java.lang.String", "x"));
    }
}
