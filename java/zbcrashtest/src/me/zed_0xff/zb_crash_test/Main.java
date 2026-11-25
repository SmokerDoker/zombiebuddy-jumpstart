package me.zed_0xff.zb_crash_test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * ZBCrashTest, the in-game test mod of the fork's crash protection (tools/gametest.ps1). At load it
 * writes a static field of a game class and a file, so the test sees the guard's write tracking;
 * its render patch throws while armed from Lua ({@code zbCrashArm(seconds)}).
 */
public class Main {
    public static final String IP = "zbcrashtest";

    public static void main(String[] args) throws Exception {
        zombie.network.GameClient.ip = IP;
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "zbcrashtest.txt"), "written by ZBCrashTest");
    }
}
