package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.Callbacks;
import me.zed_0xff.zombie_buddy.Hotswap;
import testjar.CrashLiveTarget;
import testjar.MainFailTarget;

/** Jumpstart fork, crash protection for mods loaded live. Runs in test_hotswap. */
public class GuardLiveTest {
    @Test
    void aBrokenStaticInitializerSwitchesTheModOffAtOnce() throws Exception {
        Path jar = GuardTestSupport.jar("zb.test.hotswapCrashLiveJar");
        GuardTestSupport.approve("CrashLive", jar);
        CrashLiveTarget target = new CrashLiveTarget();
        long seqBefore = Long.parseLong(Hotswap.guardLastSeq());

        assertNull(Hotswap.load(jar, "me.zed_0xff.zombie_buddy.hotswapcrashlive", "CrashLive"));
        assertEquals("0\tprobation", Hotswap.guardStatus("CrashLive"));
        assertEquals("vanilla", target.greet(), "the failing exit advice is swallowed");
        assertTrue(GuardTestSupport.eventually(() -> Hotswap.stateOf("CrashLive").equals("off")), Hotswap.stateOf("CrashLive"));
        assertEquals("vanilla", target.greet());

        String[] event = Hotswap.guardEvents(Long.toString(seqBefore)).split("\n")[0].split("\t");
        assertEquals("CrashLive", event[1]);
        assertEquals("advice", event[2]);
        assertTrue(event[4].startsWith("java.lang.ExceptionInInitializerError"), event[4]);
        assertEquals("off", event[5]);
    }

    @Test
    void aFailingMainRefusesALiveLoad() throws Exception {
        Path jar = GuardTestSupport.jar("zb.test.hotswapMainFailJar");
        GuardTestSupport.approve("MainFail", jar);
        MainFailTarget target = new MainFailTarget();
        int callbacksBefore = Callbacks.afterLuaInit.size();
        long seqBefore = Long.parseLong(Hotswap.guardLastSeq());

        String refused = Hotswap.load(jar, "me.zed_0xff.zombie_buddy.hotswapmainfail", "MainFail");
        assertTrue(refused != null && refused.startsWith("main() failed: java.lang.IllegalStateException: main failed on purpose"), refused);
        assertEquals("vanilla", target.greet(), "no patch from a refused mod");
        assertEquals(callbacksBefore, Callbacks.afterLuaInit.size(), "what main() registered is taken back");
        assertEquals("not loaded", Hotswap.stateOf("MainFail"));

        String[] event = Hotswap.guardEvents(Long.toString(seqBefore)).split("\n")[0].split("\t");
        assertEquals("main", event[2]);
        assertEquals("refused", event[5]);
    }
}
