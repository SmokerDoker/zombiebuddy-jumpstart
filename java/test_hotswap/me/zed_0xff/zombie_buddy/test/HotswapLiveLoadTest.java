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
import testjar.HotswapLiveTarget;

/**
 * Jumpstart fork: a mod jar that was not loaded at start is loaded at runtime, but only once its
 * hash is approved in mod_approvals.json. Runs in test_hotswap (hotswap=true, own config_dir).
 */
public class HotswapLiveLoadTest {
    private static final String PACKAGE = "me.zed_0xff.zombie_buddy.hotswaplive";

    @Test
    void loadsAnApprovedJarAtRuntimeAndSwitchesIt() throws Exception {
        Path jar = Path.of(System.getProperty("zb.test.hotswapLiveJar")).toRealPath();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        HotswapLiveTarget target = new HotswapLiveTarget();
        assertEquals("vanilla", target.greet());

        writeApprovals("");
        String refused = Hotswap.load(jar, PACKAGE, "HotswapLive");
        assertTrue(refused != null && refused.startsWith("not approved"), refused);
        assertEquals("vanilla", target.greet(), "refused jar must not patch anything");

        writeApprovals("{\"id\": \"HotswapLive\", \"jar_hash\": \"" + hash + "\", \"decision\": true}");
        assertNull(Hotswap.load(jar, PACKAGE, "HotswapLive"));
        assertEquals("vanilla +live", target.greet());

        assertEquals("already loaded in this session; use off/on", Hotswap.load(jar, PACKAGE, "HotswapLive"));

        assertEquals("OK HotswapLive off", Hotswap.off("HotswapLive"));
        assertEquals("vanilla", target.greet());
        assertEquals("OK HotswapLive on", Hotswap.on("HotswapLive"));
        assertEquals("vanilla +live", target.greet());
    }

    @Test
    void refusesAMissingJar() {
        String refused = Hotswap.load(Path.of("does-not-exist.jar"), PACKAGE, "x");
        assertTrue(refused != null && refused.startsWith("jar not found"), refused);
    }

    private static void writeApprovals(String entry) throws Exception {
        Path file = Agent.configDir().resolve("mod_approvals.json");
        Files.createDirectories(file.getParent());
        String json = "{\"formatVersion\": 2, \"mods\": [" + entry + "]}";
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }
}
