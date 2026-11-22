package me.zed_0xff.zombie_buddy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import me.zed_0xff.zombie_buddy.HostServerAccess;

/** The options a hosted game's server gets: safe agent options, never the Lua server or a prompt. */
class HostServerTest {
    @Test
    void keepsHotswapAndConfigDropsClientOnlyOptions() {
        Map<String, String> args = new TreeMap<>(Map.of("hotswap", "true", "experimental", "", "lua_server_port", "4444",
            "policy", "prompt", "config_dir", "C:/zb", "verbosity", "1"));
        List<String> options = HostServerAccess.agentOptions(args, Map.of("jumpstart.bitecheckDir", "C:/bc"), Path.of("ZB.jar").toAbsolutePath());
        assertEquals(List.of("-javaagent:" + Path.of("ZB.jar").toAbsolutePath() + "=policy=deny-new,config_dir=C:/zb,hotswap=true,verbosity=1",
            "-Djumpstart.bitecheckDir=C:/bc"), options);
    }
}
