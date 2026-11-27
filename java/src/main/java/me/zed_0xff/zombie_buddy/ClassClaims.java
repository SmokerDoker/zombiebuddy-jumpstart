package me.zed_0xff.zombie_buddy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

/**
 * Jumpstart fork: which mod jar brought which class. All mod jars share one class loader (their
 * advice runs inside the game's classes, which must find them), so a class two jars bring exists
 * once: the second jar used to get the first one's copy without a word, even when it was another
 * version of a library.
 *
 * <p>Now, when a jar brings a class another mod jar brought with other contents, every class of the
 * jar outside its own package is moved to a package of its own ({@code jslibN.}), so the jar's code
 * keeps the version it was built with. Left alone: the jar's own package (its patches are found by
 * it), classes of the game and Java, and classes of a mod the jar's mod requires (it may need to
 * share them). Same contents: one copy for both, as before. Names in strings ({@code Class.forName},
 * {@code META-INF/services}) are not rewritten.
 */
final class ClassClaims {
    private ClassClaims() {}

    /** Who brought a class under its own name. */
    record Claim(Path jar, String sha256) {}

    /** What loading one jar does about classes other jars brought. */
    record Plan(Path jar, Map<String, String> hashes, Set<String> moved, String prefix, List<String> clashes) {
        boolean relocates() {
            return !moved.isEmpty();
        }

        /** Internal name ({@code a/b/C}) after the move. */
        String map(String internalName) {
            return moved.contains(internalName) ? prefix + internalName : internalName;
        }
    }

    static final String OWN_COPY = "own-copy";
    static final String REQUIRED = "required";
    static final String OWN_PACKAGE = "own-package";

    private static final Map<String, Claim> g_claims = new ConcurrentHashMap<>();
    private static final Map<Path, List<String>> g_clashes = new ConcurrentHashMap<>();
    private static final AtomicInteger g_copies = new AtomicInteger();

    /** The game's and Java's own classes (by binary name). */
    static Predicate<String> g_platform = name ->
            ClassLoader.getSystemClassLoader().getResource(name.replace('.', '/') + ".class") != null;

    /** Whether the mod of the first jar requires the mod of the second. */
    static BiPredicate<Path, Path> g_requires = ClassClaims::requires;

    /** Looks at a jar before it loads. */
    static Plan plan(Path jar, String ownPackage, byte[] jarBytes) throws IOException {
        Map<String, String> hashes = classHashes(jarBytes);
        String own = ownPackage == null ? null : ownPackage.replace('.', '/') + "/";

        Map<Path, List<String>> clashing = new LinkedHashMap<>();
        Map<Path, List<String>> ownPackage2 = new LinkedHashMap<>();
        Map<Path, List<String>> required = new LinkedHashMap<>();
        Set<String> keep = new TreeSet<>();
        for (Map.Entry<String, String> e : hashes.entrySet()) {
            String name = e.getKey();
            Claim claim = g_claims.get(name);
            if (claim == null || claim.jar().equals(jar) || claim.sha256().equals(e.getValue())) {
                continue;
            }
            if (own != null && name.startsWith(own)) {
                ownPackage2.computeIfAbsent(claim.jar(), k -> new ArrayList<>()).add(name);
            } else if (g_requires.test(jar, claim.jar())) {
                required.computeIfAbsent(claim.jar(), k -> new ArrayList<>()).add(name);
                keep.add(name);
            } else {
                clashing.computeIfAbsent(claim.jar(), k -> new ArrayList<>()).add(name);
            }
        }

        Set<String> moved = new TreeSet<>();
        String prefix = "";
        if (!clashing.isEmpty()) {
            prefix = "jslib" + g_copies.incrementAndGet() + "/";
            for (String name : hashes.keySet()) {
                boolean shared = keep.contains(name) || requiredElsewhere(jar, name);
                // the game's and Java's classes stay; a mod jar's are on the class path too, but claimed
                boolean platform = g_claims.get(name) == null && g_platform.test(name.replace('/', '.'));
                if ((own == null || !name.startsWith(own)) && !shared && !platform) {
                    moved.add(name);
                }
            }
        }

        List<String> lines = new ArrayList<>();
        String dotted = prefix.replace('/', '.');
        String self = ModGuard.modIdOf(jar, ownPackage);
        // an update of the mod itself brings other versions of its libraries: no clash to report
        clashing.forEach((other, names) -> {
            if (!ModGuard.modIdOf(other, null).equals(self)) {
                lines.add(line(OWN_COPY, other, names, dotted));
            }
        });
        required.forEach((other, names) -> lines.add(line(REQUIRED, other, names, "")));
        ownPackage2.forEach((other, names) -> lines.add(line(OWN_PACKAGE, other, names, "")));
        for (String l : lines) {
            Logger.warn("classes also in another mod jar: " + jar.getFileName() + ": " + l.replace('\t', ' '));
        }
        return new Plan(jar, hashes, moved, prefix, lines);
    }

    /** After the jar loaded: its classes under their own names are its from now on. */
    static void claim(Plan plan) {
        for (Map.Entry<String, String> e : plan.hashes().entrySet()) {
            if (!plan.moved().contains(e.getKey())) {
                g_claims.putIfAbsent(e.getKey(), new Claim(plan.jar(), e.getValue()));
            }
        }
        if (plan.clashes().isEmpty()) {
            g_clashes.remove(plan.jar());
        } else {
            g_clashes.put(plan.jar(), List.copyOf(plan.clashes()));
        }
    }

    static List<String> clashesOf(Path jar) {
        return g_clashes.getOrDefault(jar, List.of());
    }

    /** For tests: forget every claim. */
    static void reset() {
        g_claims.clear();
        g_clashes.clear();
    }

    /** A class of a required mod's jar stays shared even with the same contents. */
    private static boolean requiredElsewhere(Path jar, String name) {
        Claim claim = g_claims.get(name);
        return claim != null && !claim.jar().equals(jar) && g_requires.test(jar, claim.jar());
    }

    private static String line(String kind, Path other, List<String> names, String prefix) {
        String example = names.stream().sorted().findFirst().orElse("").replace('/', '.');
        return kind + "\t" + ModGuard.modIdOf(other, null) + "\t" + names.size() + "\t" + example + "\t" + prefix;
    }

    /** sha256 of every class in the jar, by internal name. */
    static Map<String, String> classHashes(byte[] jarBytes) throws IOException {
        Map<String, String> hashes = new HashMap<>();
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                String name = entry.getName();
                if (!entry.isDirectory() && name.endsWith(".class") && !name.startsWith("META-INF/")
                        && !name.endsWith("module-info.class")) {
                    hashes.put(name.substring(0, name.length() - ".class".length()), Utils.sha256Hex(in.readAllBytes()));
                }
            }
        }
        return hashes;
    }

    /** The game's mod list: does the mod of {@code jar} list the mod of {@code other} under require? */
    private static boolean requires(Path jar, Path other) {
        String modId = ModGuard.modIdOf(jar, null);
        String otherId = ModGuard.modIdOf(other, null);
        if (Utils.isBlank(modId) || Utils.isBlank(otherId)) {
            return false;
        }
        try {
            var mod = zombie.gameStates.ChooseGameInfo.getAvailableModDetails(modId);
            if (mod == null || mod.getRequire() == null) {
                return false;
            }
            for (String r : mod.getRequire()) {
                if (r != null && strip(r).equals(strip(otherId))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // outside the game, or another game version: no requirement known
        }
        return false;
    }

    private static String strip(String id) {
        return id.trim().startsWith("\\") ? id.trim().substring(1) : id.trim();
    }
}
