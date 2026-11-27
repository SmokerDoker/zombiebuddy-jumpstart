package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.Agent;
import me.zed_0xff.zombie_buddy.Hotswap;
import testjar.CrashSwitch;
import testjar.CrashTarget;

/**
 * Jumpstart fork, crash protection: hotswapcrash.jar is loaded at start (patches_jar) and its enter
 * advice throws on demand. The game method must go on, the errors must be counted, and after more
 * than guard_limit (3) errors in a minute the mod must be off. Runs in test_hotswap.
 */
public class GuardTest {
    @Test
    void swallowsCountsAndSwitchesOffAfterTooManyErrors() throws Exception {
        Path jar = GuardTestSupport.jar("zb.test.hotswapCrashJar");
        String mod = jar.toString();
        CrashTarget target = new CrashTarget();
        assertEquals("vanilla +crash", target.greet());
        assertEquals("0\tok", Hotswap.guardStatus(mod));

        long seqBefore = Long.parseLong(Hotswap.guardLastSeq());
        CrashSwitch.enter = true;
        try {
            for (int i = 1; i <= 3; i++) {
                assertEquals("vanilla +crash", target.greet(), "error " + i + " is swallowed, the method runs on");
            }
            assertEquals("3\tok", Hotswap.guardStatus(mod));
            assertEquals("on", Hotswap.stateOf(mod));

            target.greet(); // the fourth error in a minute: off
            assertTrue(GuardTestSupport.eventually(() -> Hotswap.stateOf(mod).equals("off")), Hotswap.stateOf(mod));
            assertEquals("vanilla", target.greet(), "off: no advice runs any more");
            assertEquals("4\toff-by-guard", Hotswap.guardStatus(mod), "a silenced mod throws no more");

            String events = Hotswap.guardEvents(Long.toString(seqBefore));
            String[] lines = events.split("\n");
            assertEquals(4, lines.length, events);
            String[] last = lines[3].split("\t");
            assertEquals(6, last.length, lines[3]);
            assertEquals("advice", last[2]);
            assertTrue(last[4].startsWith("java.lang.NullPointerException: crash test"), last[4]);
            assertEquals("off", last[5]);
            assertEquals("", Hotswap.guardEvents(Hotswap.guardLastSeq()));

            try (Stream<Path> reports = Files.list(Agent.configDir().resolve("guard"))) {
                assertTrue(reports.anyMatch(p -> p.getFileName().toString().contains("hotswapcrash")), "one report file for the mod");
            }

            // switched on again: counting starts over, and an error in probation switches it off at once
            assertTrue(Hotswap.on(mod).startsWith("OK"), "on");
            assertEquals("0\tprobation", Hotswap.guardStatus(mod));
            target.greet();
            assertTrue(GuardTestSupport.eventually(() -> Hotswap.stateOf(mod).equals("off")), "probation: the first error is enough");

            CrashSwitch.enter = false;
            assertTrue(Hotswap.on(mod).startsWith("OK"), "on again");
            assertEquals("vanilla +crash", target.greet());

            // the trip wire in every method: a callback the mod handed out runs while it is on; switched
            // off by the player, it returns at once, and the call is counted (something still holds the mod)
            assertEquals("helper", CrashSwitch.helper.get());
            assertTrue(Hotswap.off(mod).startsWith("OK"), "off");
            assertEquals("0", Hotswap.callsWhileOff(mod));
            assertEquals(null, CrashSwitch.helper.get(), "off: its code does not run");
            assertEquals(null, CrashSwitch.helper.get());
            assertEquals("2", Hotswap.callsWhileOff(mod));
            assertEquals("CrashHelper", CrashSwitch.helper.toString(), "toString has no trip wire");
            assertTrue(Hotswap.on(mod).startsWith("OK"), "on again");
            assertEquals("helper", CrashSwitch.helper.get(), "on: it runs again");
            assertEquals("", Hotswap.callsWhileOff("NoSuchMod"));
        } finally {
            CrashSwitch.enter = false;
        }
    }

    @Test
    void unknownModsHaveNoStatus() {
        assertEquals("", Hotswap.guardStatus("NoSuchMod"));
        assertEquals("", Hotswap.guardEvents("999999999"));
    }
}
