package me.zed_0xff.zombie_buddy;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jumpstart fork, clean removal: files a mod's Java code writes. The {@code writes} pipeline step
 * calls {@link #note} before {@code Files.write*}, {@code newOutputStream}, {@code newBufferedWriter}
 * and {@code new FileOutputStream/FileWriter} in the mod's code. Each path is logged once per
 * session to {@code <config_dir>/guard/files-<modId>.txt} ("new" or "existed"), so a removal can
 * show or archive what the mod left behind. Nothing is redirected or blocked.
 */
public final class ModIO {
    private static final Set<String> g_seen = ConcurrentHashMap.newKeySet();

    private ModIO() {}

    /** Before a mod writes to {@code target} (a Path, File or file name). Never throws. */
    public static void note(Object target, String pkg) {
        try {
            Path path = target instanceof Path p ? p
                : target instanceof File f ? f.toPath()
                : target instanceof String s ? Path.of(s)
                : null;
            if (path == null) {
                return;
            }
            Path abs = path.toAbsolutePath().normalize();
            if (!g_seen.add(pkg + "|" + abs)) {
                return;
            }
            String modId = ModGuard.modIdOfPackage(pkg);
            String line = LocalDateTime.now() + "\t" + abs + "\t" + (Files.exists(abs) ? "existed" : "new") + "\n";
            Path dir = Agent.configDir().resolve("guard");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("files-" + modId.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt"), line,
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // reporting must never break the mod's write
        }
    }
}
