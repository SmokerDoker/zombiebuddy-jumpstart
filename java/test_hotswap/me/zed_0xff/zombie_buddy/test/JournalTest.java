package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.Callbacks;
import me.zed_0xff.zombie_buddy.GuardAccess;
import me.zed_0xff.zombie_buddy.Hotswap;
import testjar.JournalTarget;
import zombie.jstest.WriteTarget;

/**
 * Jumpstart fork, clean removal: what a mod registered (Lua class, global functions, callback) and
 * the static fields it wrote into a game class are taken back when it is switched off, and come
 * back when it is switched on. Its file writes are logged. Runs in test_hotswap.
 */
public class JournalTest {
    private static final String MOD = "HotswapJournal";
    private static final String API = "me.zed_0xff.zombie_buddy.hotswapjournal.JournalApi";
    private static final String GLOBALS = "me.zed_0xff.zombie_buddy.hotswapjournal.JournalGlobals";

    @Test
    void takesBackWhatTheModRegisteredAndWrote() throws Exception {
        Path jar = GuardTestSupport.jar("zb.test.hotswapJournalJar");
        GuardTestSupport.approve(MOD, jar);
        Path written = Path.of(System.getProperty("zb.test.journalFile")).toAbsolutePath().normalize();
        Files.deleteIfExists(written);
        JournalTarget target = new JournalTarget();
        int callbacksBefore = Callbacks.beforeLuaInit.size();

        assertNull(Hotswap.load(jar, "me.zed_0xff.zombie_buddy.hotswapjournal", MOD));
        assertEquals("vanilla +journal", target.greet());
        assertTrue(GuardAccess.exposedClasses().contains(API));
        assertTrue(GuardAccess.globalLuaClasses().contains(GLOBALS));
        assertEquals(callbacksBefore + 1, Callbacks.beforeLuaInit.size());
        assertEquals("mod", WriteTarget.VALUE);
        assertEquals("mod", WriteTarget.REFLECTED);
        assertTrue(GuardAccess.journalSize(jar) >= 5, "class, globals, callback, two fields: " + GuardAccess.journalSize(jar));

        Path log = Agent.configDir().resolve("guard").resolve("files-" + MOD + ".txt");
        assertTrue(Files.readString(log, StandardCharsets.UTF_8).contains(written + "\tnew"), "file write logged");

        assertEquals("OK " + MOD + " off", Hotswap.off(MOD));
        assertEquals("vanilla", target.greet());
        assertFalse(GuardAccess.exposedClasses().contains(API), "not exposed again at the next Lua start");
        assertFalse(GuardAccess.globalLuaClasses().contains(GLOBALS));
        assertEquals(callbacksBefore, Callbacks.beforeLuaInit.size());
        assertEquals("orig", WriteTarget.VALUE);
        assertEquals("orig", WriteTarget.REFLECTED);
        assertEquals("orig", WriteTarget.ONE.name, "instance fields of game objects go back too");
        assertEquals(1, WriteTarget.ONE.speed);
        assertEquals(1, WriteTarget.TWO.speed);

        assertEquals("OK " + MOD + " on", Hotswap.on(MOD));
        assertEquals("mod", WriteTarget.ONE.name);
        assertEquals(5, WriteTarget.ONE.speed);
        assertEquals(7, WriteTarget.TWO.speed);
        WriteTarget.TWO.speed = 3; // the game changes one of them: switching off keeps that
        assertEquals("OK " + MOD + " off", Hotswap.off(MOD));
        assertEquals(1, WriteTarget.ONE.speed);
        assertEquals(3, WriteTarget.TWO.speed);
        assertEquals("OK " + MOD + " on", Hotswap.on(MOD));
        assertEquals("vanilla +journal", target.greet());
        assertTrue(GuardAccess.exposedClasses().contains(API));
        assertTrue(GuardAccess.globalLuaClasses().contains(GLOBALS));
        assertEquals(callbacksBefore + 1, Callbacks.beforeLuaInit.size());
        assertEquals("mod", WriteTarget.VALUE);

        // something else changed the field while the mod was off: switching it on leaves that alone
        assertEquals("OK " + MOD + " off", Hotswap.off(MOD));
        WriteTarget.VALUE = "game";
        assertEquals("OK " + MOD + " on", Hotswap.on(MOD));
        assertEquals("game", WriteTarget.VALUE);

        // uninstall: everything goes, for good in this session
        assertTrue(Hotswap.forget(MOD).startsWith("OK " + MOD + " forgotten"), "forget");
        assertEquals("vanilla", target.greet());
        assertFalse(GuardAccess.exposedClasses().contains(API));
        assertEquals(callbacksBefore, Callbacks.beforeLuaInit.size());
        assertEquals(0, GuardAccess.journalSize(jar));
        assertTrue(Hotswap.forget("NoSuchMod").startsWith("FAILED"));
    }
}
