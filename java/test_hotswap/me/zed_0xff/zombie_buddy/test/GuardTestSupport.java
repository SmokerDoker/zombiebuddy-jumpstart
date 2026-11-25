package me.zed_0xff.zombie_buddy.test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.BooleanSupplier;

import me.zed_0xff.zombie_buddy.Agent;

/** Helpers for the crash-protection and journal tests. */
final class GuardTestSupport {
    private GuardTestSupport() {}

    static Path jar(String property) throws Exception {
        return Path.of(System.getProperty(property)).toRealPath();
    }

    /** Writes allow decisions for the jars to mod_approvals.json (replaces the file, like the other tests). */
    static synchronized void approve(String modId, Path... jars) throws Exception {
        StringBuilder entries = new StringBuilder();
        for (Path jar : jars) {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
            if (entries.length() > 0) {
                entries.append(", ");
            }
            entries.append("{\"id\": \"").append(modId).append("\", \"jar_hash\": \"").append(hash).append("\", \"decision\": true}");
        }
        Path file = Agent.configDir().resolve("mod_approvals.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"formatVersion\": 2, \"mods\": [" + entries + "]}", StandardCharsets.UTF_8);
    }

    /** Waits up to 10 s: the guard switches mods off on its own thread. */
    static boolean eventually(BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }
}
