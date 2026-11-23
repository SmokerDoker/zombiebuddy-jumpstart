package me.zed_0xff.zombie_buddy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Upstream replaces the running ZombieBuddy JAR with a newer, Zed-signed one from the mods
 * directory. The Jumpstart fork does not self-update (see {@link ForkInfo}), so the signature
 * check and the file replacing are gone; what is left keeps the log line and the watermark as
 * they were with self-update switched off.
 */
public final class SelfUpdater {
    private SelfUpdater() {}

    /** The version the JAR was replaced with; the fork never replaces it. */
    public static String getNewVersion() {
        return null;
    }

    public static String getExclusionReasonSuffix(Path jarPath) {
        if (jarPath == null) {
            return " (null not found)";
        }
        if (!Files.isRegularFile(jarPath)) {
            return " (" + jarPath.toAbsolutePath() + " not found)";
        }

        StringBuilder sb = new StringBuilder();
        Manifest manifest = getJarManifest(jarPath);
        if (manifest != null) {
            String manifestVersion = manifest.getMainAttributes().getValue("Implementation-Version");
            if (manifestVersion != null) {
                sb.append(" (version ").append(manifestVersion).append(")");
            }
        }
        return sb.toString();
    }

    public static Manifest getJarManifest(Path jarPath) {
        if (jarPath == null) {
            return null;
        }
        try (JarFile jar = new JarFile(jarPath.toFile(), true)) {
            return jar.getManifest();
        } catch (Exception e) {
            Logger.error("Error getting JAR manifest: " + e);
            return null;
        }
    }
}
