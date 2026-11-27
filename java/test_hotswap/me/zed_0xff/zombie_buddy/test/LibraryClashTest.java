package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.Hotswap;
import testjar.LibClashTarget;

/**
 * Jumpstart fork: two mods bring a library (libshared) in another version each. All mod jars share
 * one class loader; the second mod used to run with the first one's version without a word.
 */
public class LibraryClashTest {

    @Test
    void eachModKeepsTheVersionOfTheLibraryItBrought() throws Exception {
        Path a = Path.of(System.getProperty("zb.test.libClashAJar")).toRealPath();
        Path b = Path.of(System.getProperty("zb.test.libClashBJar")).toRealPath();
        writeApprovals(entry("LibClashA", a) + ", " + entry("LibClashB", b));
        LibClashTarget target = new LibClashTarget();

        assertNull(Hotswap.load(a, "me.zed_0xff.zombie_buddy.libclasha", "LibClashA"));
        assertNull(Hotswap.load(b, "me.zed_0xff.zombie_buddy.libclashb", "LibClashB"));
        assertEquals("a lib-a", target.a());
        assertEquals("b lib-b", target.b(), "the second mod runs with its own version, Helper (the same in both) included");

        assertEquals("", Hotswap.classClashes("LibClashA"), "the first one keeps the names");
        String[] clash = Hotswap.classClashes("LibClashB").split("\t", -1);
        assertEquals("own-copy", clash[0]);
        assertEquals("LibClashA", clash[1]);
        assertEquals("1", clash[2], "only Lib differs");
        assertEquals("libshared.Lib", clash[3]);
        assertTrue(clash[4].matches("jslib[0-9]+[.]"), clash[4]);
    }

    private static String entry(String id, Path jar) throws Exception {
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        return "{\"id\": \"" + id + "\", \"jar_hash\": \"" + hash + "\", \"decision\": true}";
    }

    private static void writeApprovals(String entries) throws Exception {
        Path file = Agent.configDir().resolve("mod_approvals.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"formatVersion\": 2, \"mods\": [" + entries + "]}", StandardCharsets.UTF_8);
    }
}
