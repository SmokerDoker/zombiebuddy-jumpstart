package me.zed_0xff.zombie_buddy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Jumpstart fork: a Java mod built into the agent jar itself, so a player installs one jar instead
 * of ZombieBuddy plus a mod jar. The agent jar's manifest names the mod's package in
 * {@value #ATTRIBUTE}; its files lie under {@value #PREFIX} (for example
 * {@code embedded-mod/io/github/jumpstartpz/mod/Main.class}).
 *
 * <p>The files are stored as plain entries, not as a jar inside the jar, so tools that review the
 * agent jar (BiteCheck) see every class. They are under a prefix so the system class loader, which
 * has the agent jar on its path, cannot load them untransformed: ZombieBuddy loads them like any
 * mod jar (patches applied, Lua classes exposed, {@code Main.main} called) when the mods load.
 */
final class EmbeddedMod {
    static final String ATTRIBUTE = "ZombieBuddy-Embedded-Mod";
    static final String PREFIX = "embedded-mod/";

    /** The mod's package and its files as a jar with normal entry names. */
    record Jar(String packageName, byte[] bytes) {
    }

    private EmbeddedMod() {
    }

    /** The embedded mod of the given agent jar; null if its manifest names none. */
    static Jar read(Path agentJar) throws IOException {
        try (JarFile jar = new JarFile(agentJar.toFile())) {
            Manifest manifest = jar.getManifest();
            String pkg = manifest == null ? null : manifest.getMainAttributes().getValue(ATTRIBUTE);
            if (Utils.isBlank(pkg)) {
                return null;
            }
            pkg = pkg.strip();
            String pkgPath = PREFIX + pkg.replace('.', '/') + "/";
            Manifest inner = new Manifest();
            inner.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int files = 0;
            try (JarOutputStream jos = new JarOutputStream(out, inner)) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith(PREFIX)) {
                        continue;
                    }
                    String name = e.getName().substring(PREFIX.length());
                    if (name.isEmpty() || name.startsWith("/") || name.contains("..")) {
                        continue;
                    }
                    if (name.endsWith(".class") && !e.getName().startsWith(pkgPath)) {
                        continue; // only the named package's classes; the rest is on the classpath
                    }
                    jos.putNextEntry(new JarEntry(name));
                    try (InputStream in = jar.getInputStream(e)) {
                        in.transferTo(jos);
                    }
                    jos.closeEntry();
                    files++;
                }
            }
            if (files == 0) {
                throw new IOException(ATTRIBUTE + " names " + pkg + ", but " + agentJar.getFileName()
                        + " has no files under " + pkgPath);
            }
            return new Jar(pkg, out.toByteArray());
        }
    }

    private static Jar cached;
    private static boolean looked;

    /** Loads the agent jar's embedded mod, if it has one. Called with the mods, each time they load. */
    static synchronized void load(Loader.Phase phase) {
        if (!looked) {
            looked = true;
            Path agentJar = Utils.getCurrentJarPath();
            if (agentJar == null) {
                return;
            }
            try {
                cached = read(agentJar);
            } catch (IOException e) {
                Logger.error("cannot read the embedded mod of " + agentJar.getFileName() + ": " + e.getMessage());
                return;
            }
        }
        Jar mod = cached;
        if (mod == null) {
            return;
        }
        Logger.info("loading embedded mod " + mod.packageName() + " from the agent jar");
        Loader.transformAndLoadJar(Path.of(PREFIX + mod.packageName()), mod.packageName(), phase, mod.bytes());
    }
}
