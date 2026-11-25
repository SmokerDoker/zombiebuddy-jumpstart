package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.Hotswap;
import testjar.CrashSwitch;
import testjar.CrashTarget;

/**
 * Jumpstart fork, trip wire without hotswap (test_guard: hotswap off, guard=switch, guard_limit=1):
 * the patches cannot be removed, but after the second error in a minute the mod's advice returns
 * at once, so it neither throws nor changes anything any more.
 */
public class GuardNoHotswapTest {
    @Test
    void silencesAModWhosePatchesCannotBeRemoved() throws Exception {
        assertFalse(Hotswap.isEnabled());
        String mod = Path.of(System.getProperty("zb.test.hotswapCrashJar")).toRealPath().toString();
        CrashTarget target = new CrashTarget();
        assertEquals("vanilla +crash", target.greet());

        CrashSwitch.enter = true;
        try {
            assertEquals("vanilla +crash", target.greet(), "first error swallowed");
            assertEquals("1\tok", Hotswap.guardStatus(mod));
            target.greet(); // second error in a minute: more than guard_limit=1
            assertEquals("2\toff-by-guard", Hotswap.guardStatus(mod));
            for (int i = 0; i < 10; i++) {
                assertEquals("vanilla", target.greet(), "silenced: neither the throwing enter nor the exit runs");
            }
            assertEquals("2\toff-by-guard", Hotswap.guardStatus(mod));
            assertEquals("off", Hotswap.stateOf(mod));
        } finally {
            CrashSwitch.enter = false;
        }
    }
}
