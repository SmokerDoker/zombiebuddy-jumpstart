package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.Hotswap;
import testjar.HotswapTarget;

/**
 * Jumpstart fork: runs with the agent options hotswap=true and patches_jar=hotswappatches.jar.
 * The patch targets a class that loads only after premain, the case upstream ZombieBuddy patches
 * with a transformer that can never be removed.
 */
public class HotswapTest {
    private static Path patchJar() throws Exception {
        return Path.of(System.getProperty("zb.test.hotswapJar")).toRealPath();
    }

    @Test
    void switchesAModOffAndOnAgain() throws Exception {
        assertTrue(Hotswap.isEnabled(), "agent must run with hotswap=true");
        HotswapTarget target = new HotswapTarget();
        assertEquals("vanilla +patched", target.greet());

        Path jar = patchJar();
        for (int round = 0; round < 50; round++) {
            assertNull(Hotswap.switchOff(jar), "off, round " + round);
            assertEquals("vanilla", target.greet(), "off, round " + round);

            assertNull(Hotswap.switchOn(jar), "on, round " + round);
            assertEquals("vanilla +patched", target.greet(), "on, round " + round);
        }

        assertTrue(Hotswap.classHasString("testjar.HotswapTarget", " +patched"));
        assertNull(Hotswap.switchOff(jar));
        assertFalse(Hotswap.classHasString("testjar.HotswapTarget", " +patched"));
        assertNull(Hotswap.switchOn(jar));
        assertTrue(Hotswap.classHasString("testjar.HotswapTarget", " +patched"));
    }

    @Test
    void aNestedPatchReadsAPrivateFieldOfTheClassAroundIt() {
        assertEquals(42, new HotswapTarget().answer());
    }

    @Test
    void refusesWhatItCannotDo() throws Exception {
        Path jar = patchJar();
        assertEquals("already on", Hotswap.switchOn(jar));
        assertEquals("no patches loaded from this jar", Hotswap.switchOff(Path.of("does-not-exist.jar").toAbsolutePath()));
    }
}
