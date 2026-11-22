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
import testjar.HotswapUpdateTarget;

/**
 * Jumpstart fork: version 1 of a mod is loaded at runtime, then replaced by version 2 with the
 * same class names. Runs in test_hotswap (hotswap=true, own config_dir).
 */
public class HotswapUpdateTest {
    private static final String PACKAGE = "me.zed_0xff.zombie_buddy.hotswapupdate";
    private static final String MOD = "HotswapUpdate";

    @Test
    void replacesALoadedVersionWithANewOne() throws Exception {
        Path v1 = Path.of(System.getProperty("zb.test.hotswapUpdateV1Jar")).toRealPath();
        Path v2 = Path.of(System.getProperty("zb.test.hotswapUpdateV2Jar")).toRealPath();
        writeApprovals(v1, v2);

        HotswapUpdateTarget target = new HotswapUpdateTarget();
        assertEquals("vanilla", target.greet());

        assertNull(Hotswap.load(v1, PACKAGE, MOD));
        assertEquals("vanilla +v1", target.greet());

        String clash = Hotswap.load(v2, PACKAGE, MOD);
        assertTrue(clash != null && clash.contains("use update"), clash);
        assertEquals("vanilla +v1", target.greet(), "a refused load must not change anything");

        assertNull(Hotswap.update(v2, PACKAGE, MOD));
        assertEquals("vanilla +v2", target.greet());

        assertEquals("OK " + MOD + " off", Hotswap.off(MOD));
        assertEquals("vanilla", target.greet());
        assertEquals("OK " + MOD + " on", Hotswap.on(MOD));
        assertEquals("vanilla +v2", target.greet());

        // a second update of the same jar gets the next package and works the same way
        assertNull(Hotswap.update(v1, PACKAGE, MOD));
        assertEquals("vanilla +v1", target.greet());
    }

    @Test
    void refusesAnUpdateWithoutALoadedVersion() throws Exception {
        Path v2 = Path.of(System.getProperty("zb.test.hotswapUpdateV2Jar")).toRealPath();
        String refused = Hotswap.update(v2, PACKAGE, "NoSuchMod");
        assertTrue(refused != null && refused.startsWith("no loaded version"), refused);
    }

    private static void writeApprovals(Path... jars) throws Exception {
        StringBuilder entries = new StringBuilder();
        for (Path jar : jars) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
            if (entries.length() > 0) entries.append(", ");
            entries.append("{\"id\": \"" + MOD + "\", \"jar_hash\": \"" + hash + "\", \"decision\": true}");
        }
        Path file = Agent.configDir().resolve("mod_approvals.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"formatVersion\": 2, \"mods\": [" + entries + "]}", StandardCharsets.UTF_8);
    }
}
