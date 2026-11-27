package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.UnmodifiableClassException;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import me.zed_0xff.zombie_buddy.transformers.TransformedJar;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;

/**
 * Jumpstart fork: switches the patches of loaded Java mods off and on while the game runs.
 *
 * <p>Needs the agent option {@code hotswap=true} (or {@code -Dzb.hotswap=true} with
 * {@code prop_prefix=zb}). Then every Advice-only mod jar is patched with one retransformable
 * transformer that does not change the class format. Switching a mod off removes that transformer
 * and retransforms its target classes: the JVM rebuilds them from their original bytes and re-runs
 * every other transformer, so all other mods stay patched.
 *
 * <p>Only patches are switched. The mod's classes stay defined, and whatever its {@code main()} did
 * when it was loaded (Lua exposures, event handlers, files) stays as it is. Mods with
 * MethodDelegation patches, and every mod when hotswap is off, keep their patches until restart.
 *
 * <p>{@link #load} adds a mod jar that was not loaded at start. It only loads a jar whose SHA-256 is
 * approved in mod_approvals.json (written by ZombieBuddy's own prompt, by BiteCheck or by
 * Jumpstart); there is no prompt at runtime. A jar whose classes are already defined, for example
 * a newer version of a mod loaded at start, is refused: its classes would not replace the old ones.
 * {@link #update} handles that case: it switches the old version off and loads the new one with
 * all its classes moved to a fresh package ({@code jsv2.<package>}, {@code jsv3.<package>}, ...).
 *
 * <p>Lua sees this class only through {@link HotswapLua}: there it can read the state, and change
 * it only when the game was started with the agent option {@code hotswap_lua=true} (tests).
 */
public final class Hotswap {
    private static final Set<Path> g_switchedOff = ConcurrentHashMap.newKeySet();
    /** Last version number used per mod package by {@link #update}; the first update is 2. */
    private static final Map<String, Integer> g_generations = new ConcurrentHashMap<>();

    private Hotswap() {}

    public static boolean isEnabled() {
        String value = Agent.arguments.get("hotswap");
        return value != null && !value.equalsIgnoreCase("false"); // "hotswap" without a value means on
    }

    /** Lua: switches off every jar of a mod (mod id or jar path). Returns a one-line result. */
    public static synchronized String off(String mod) {
        return forEachJar(mod, true);
    }

    /**
     * Calls into a mod's code since it was switched off (every method has the trip wire, which returns
     * at once and counts): "0" when nothing holds it any more, else the number. "" for an unknown mod.
     */
    public static synchronized String callsWhileOff(String mod) {
        List<Path> jars = resolve(mod);
        if (jars.isEmpty()) {
            return "";
        }
        long calls = 0;
        for (Path jar : jars) {
            calls += ModGuard.callsWhileOff(jar);
        }
        return Long.toString(calls);
    }

    /** Lua: switches a mod back on that was switched off with {@link #off}. Returns a one-line result. */
    public static synchronized String on(String mod) {
        return forEachJar(mod, false);
    }

    /** Lua: loads a mod jar that was not loaded at start. Returns a one-line result. */
    public static synchronized String loadJar(String jarPath, String packageName, String modId) {
        String problem = Utils.isBlank(jarPath) ? "no jar path" : load(Path.of(jarPath), packageName, modId);
        return problem == null ? "OK " + modId + " loaded" : "FAILED " + modId + ": " + problem;
    }

    /** Lua: replaces the loaded version of a mod with the jar at jarPath. Returns a one-line result. */
    public static synchronized String updateJar(String jarPath, String packageName, String modId) {
        String problem = Utils.isBlank(jarPath) ? "no jar path" : update(Path.of(jarPath), packageName, modId);
        return problem == null ? "OK " + modId + " updated" : "FAILED " + modId + ": " + problem;
    }

    /**
     * Lua: the state of a mod (id or jar path): "on", "on, until restart", "off" or "not loaded".
     * With several jars (a mod with libraries, or old versions after an update), the most "on" wins.
     */
    public static synchronized String stateOf(String mod) {
        String best = "not loaded";
        for (Path jar : resolve(mod)) {
            String state;
            if (g_switchedOff.contains(jar) || ModGuard.isTripped(jar)) {
                state = "off";
            } else {
                PatchEngine.PatchHandle handle = Loader.g_patch_handles.get(jar);
                if (handle == null && !Loader.g_mod_loaders.containsKey(jar)) {
                    continue; // known to ZombieBuddy, but skipped or denied
                }
                state = handle == null || !handle.isSwitchable() ? "on, until restart" : "on";
            }
            if (STATE_RANK.indexOf(state) > STATE_RANK.indexOf(best)) {
                best = state;
            }
        }
        return best;
    }

    private static final List<String> STATE_RANK = List.of("not loaded", "off", "on, until restart", "on");

    /**
     * Lua (Jumpstart's mod list): the Java part of a mod folder exactly as ZombieBuddy would load it,
     * as "jar path TAB package", or "" when the mod has none that fits this ZombieBuddy and side.
     */
    public static String javaModOf(String commonDir, String versionDir) {
        return describeJavaMod(JavaModInfo.findFor(pathOrNull(commonDir), pathOrNull(versionDir), Utils.isServer()));
    }

    /** Jumpstart (multiplayer): the jar a game client loads from a mod folder, also when asked on a server. */
    public static String clientJavaModOf(String commonDir, String versionDir) {
        return describeJavaMod(JavaModInfo.findFor(pathOrNull(commonDir), pathOrNull(versionDir), false));
    }

    private static String describeJavaMod(JavaModInfo info) {
        return info == null ? "" : info.jarPath().toAbsolutePath() + "\t" + info.javaPkgName();
    }

    private static Path pathOrNull(String dir) {
        return Utils.isBlank(dir) ? null : Path.of(dir);
    }

    /** Lua: one line per loaded Java mod jar: on/off, switchable or not. */
    public static synchronized String status() {
        StringBuilder sb = new StringBuilder("hotswap " + (isEnabled() ? "on" : "off (start with hotswap=true)"));
        for (var entry : jarsById().entrySet()) {
            for (Path jar : entry.getValue()) {
                sb.append('\n').append(entry.getKey()).append("  ").append(jar.getFileName()).append("  ").append(describe(jar));
            }
        }
        return sb.toString();
    }

    /**
     * Diagnostics (Lua too): whether the bytecode a loaded class runs right now contains a string
     * constant, for example a text only a patch adds. Retransforms the class once to read its
     * current bytes; all registered transformers run again, so nothing changes.
     */
    public static synchronized boolean classHasString(String className, String text) {
        if (Utils.isBlank(className) || text == null || text.isEmpty()) {
            return false;
        }
        Class<?> target = null;
        for (Class<?> loaded : Loader.g_instrumentation.getAllLoadedClasses()) {
            if (loaded.getName().equals(className)) {
                target = loaded;
                break;
            }
        }
        if (target == null || !Loader.g_instrumentation.isModifiableClass(target)) {
            return false;
        }
        final Class<?> wanted = target;
        AtomicReference<byte[]> current = new AtomicReference<>();
        // Added last, so it sees the bytes after every other retransformable transformer.
        ClassFileTransformer capture = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (redefined == wanted) {
                    current.set(bytes.clone());
                }
                return null;
            }
        };
        Loader.g_instrumentation.addTransformer(capture, true);
        try {
            Loader.g_instrumentation.retransformClasses(target);
        } catch (UnmodifiableClassException | RuntimeException e) {
            Logger.error("hotswap: cannot read " + className + ": " + e);
            return false;
        } finally {
            Loader.g_instrumentation.removeTransformer(capture);
        }
        byte[] bytes = current.get();
        return bytes != null && indexOf(bytes, text.getBytes(StandardCharsets.UTF_8)) >= 0;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** Removes the jar's patches. Returns null on success, otherwise why nothing was changed. */
    public static synchronized String switchOff(Path jar) {
        if (!isEnabled()) {
            return "hotswap is off (start with hotswap=true)";
        }
        PatchEngine.PatchHandle handle = Loader.g_patch_handles.get(jar);
        if (handle == null) {
            return g_switchedOff.contains(jar) ? "already off" : "no patches loaded from this jar";
        }
        if (!handle.isSwitchable()) {
            return "its patches stay until restart (MethodDelegation, or loaded without hotswap)";
        }
        for (ResettableClassFileTransformer transformer : handle.transformers()) {
            if (!transformer.reset(Loader.g_instrumentation, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)) {
                return "a transformer was no longer registered; state unknown, restart recommended";
            }
        }
        Loader.g_patch_handles.remove(jar);
        g_switchedOff.add(jar);
        Logger.info("hotswap: switched off " + jar + " (" + handle.targetClasses().size() + " classes rebuilt)");
        return null;
    }

    /** Applies the patches of a jar switched off before. Returns null on success, otherwise why. */
    public static synchronized String switchOn(Path jar) {
        if (!g_switchedOff.contains(jar)) {
            return Loader.g_patch_handles.containsKey(jar) ? "already on" : "was not switched off";
        }
        ClassLoader modLoader = Loader.g_mod_loaders.get(jar);
        TransformedJar transformed = Loader.g_transformed_jars.get(jar);
        if (modLoader == null || transformed == null) {
            return "the jar's classes are gone; restart to load it again";
        }
        PatchEngine.PatchHandle handle = PatchEngine.applyPatches(transformed.patches(), modLoader, transformed);
        Loader.g_patch_handles.put(jar, handle);
        g_switchedOff.remove(jar);
        Logger.info("hotswap: switched on " + jar);
        return handle.isComplete() ? null : "on, but some classes could not be patched (see log)";
    }

    /**
     * Gives Lua what a live load registered (its @LuaClass classes, global functions) right away,
     * not only at the next Lua start, so the mod's Lua finds its Java part at once.
     */
    private static void exposeNow(String modId, Exposer.Registered before) {
        try {
            int n = Exposer.exposeAddedSince(before);
            if (n > 0) {
                Logger.info("hotswap: " + modId + ": " + n + " Lua exposure(s) available right away");
            }
        } catch (Throwable t) {
            Logger.error("hotswap: " + modId + ": exposing to Lua failed, available after the next load: " + t);
        }
    }

    /**
     * Loads a mod jar that was not loaded at start and applies its patches (switchable, like every
     * jar with hotswap on). Runs the mod's Main class like a normal load. Returns null on success,
     * otherwise why nothing was loaded.
     */
    public static synchronized String load(Path jarPath, String packageName, String modId) {
        if (!isEnabled()) {
            return "hotswap is off (start with hotswap=true)";
        }
        if (Utils.isBlank(packageName)) {
            return "no Java package name";
        }
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return "jar not found: " + jarPath;
        }
        Path jar = realPath(jarPath);
        if (Loader.g_mod_loaders.containsKey(jar)) {
            return "already loaded in this session; use off/on";
        }

        String hash = Utils.sha256Hex(jar);
        if (hash == null) {
            return "cannot hash jar";
        }
        Boolean decision = approvalOnDisk(hash);
        if (Boolean.FALSE.equals(decision)) {
            return "denied in mod_approvals.json (sha256 " + hash + ")";
        }
        if (decision == null) {
            return "not approved: mod_approvals.json has no allow decision for sha256 " + hash;
        }

        String clash;
        try {
            clash = firstAlreadyDefinedClass(jar);
        } catch (IOException e) {
            return "cannot read jar: " + e.getMessage();
        }
        if (clash != null) {
            return "class " + clash + " is already defined (another version of this mod?); use update, or restart";
        }

        // loadJar re-checks the hash right before it reads the jar, so a swapped file is refused.
        Exposer.Registered before = Exposer.registered();
        ModGuard.nameJar(jar, Utils.isBlank(modId) ? jar.getFileName().toString() : modId);
        boolean loaded;
        Loader.g_liveLoad.set(Boolean.TRUE);
        Loader.g_mainFailure.remove();
        try {
            loaded = Loader.loadJar(jar, packageName, hash, Loader.Phase.MAIN);
        } finally {
            Loader.g_liveLoad.remove();
        }
        Throwable mainFailure = Loader.g_mainFailure.get();
        Loader.g_mainFailure.remove();
        if (mainFailure != null) {
            ModJournal.forget(jar); // whatever main() registered before it threw
            return "main() failed: " + mainFailure;
        }
        if (!loaded) {
            return "ZombieBuddy could not load it (see log)";
        }
        String id = Utils.isBlank(modId) ? jar.getFileName().toString() : modId;
        Loader.g_jarLoadStatus.put(jar, new Loader.JavaModLoadState(
            id, jar, new ModFlags(ModFlags.MF_ACTIVE), "loaded live", hash, Boolean.TRUE));
        Logger.info("hotswap: loaded " + id + " from " + jar);
        exposeNow(id, before);
        ModGuard.startProbation(jar);

        PatchEngine.PatchHandle handle = Loader.g_patch_handles.get(jar);
        return handle == null || handle.isComplete() ? null : "loaded, but some classes could not be patched (see log)";
    }

    /**
     * Replaces the loaded version of a mod with a new jar while the game runs. The new jar must be
     * approved in mod_approvals.json, and every loaded jar of the mod must be switchable. Its classes
     * are moved from packageName to a fresh package so they do not clash with the old version; the
     * old version is switched off first and switched on again if the new one fails to load. The
     * new version's Main class runs like at a normal load. Returns null on success, otherwise why.
     */
    public static synchronized String update(Path jarPath, String packageName, String modId) {
        if (!isEnabled()) {
            return "hotswap is off (start with hotswap=true)";
        }
        if (Utils.isBlank(packageName) || Utils.isBlank(modId)) {
            return "need the mod id and its Java package name";
        }
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            return "jar not found: " + jarPath;
        }
        Path jar = realPath(jarPath);

        List<Path> oldJars = new ArrayList<>();
        for (Path candidate : resolve(modId)) {
            if (Loader.g_patch_handles.containsKey(candidate)) {
                oldJars.add(candidate);
            }
        }
        if (oldJars.isEmpty()) {
            return "no loaded version of " + modId + " to replace; use load";
        }
        for (Path old : oldJars) {
            PatchEngine.PatchHandle handle = Loader.g_patch_handles.get(old);
            if (!handle.isSwitchable()) {
                return old.getFileName() + " cannot be switched off (MethodDelegation, or loaded without hotswap); restart";
            }
        }

        // Read once: the approved hash and the loaded bytes are the same bytes.
        byte[] original;
        try {
            original = Files.readAllBytes(jar);
        } catch (IOException e) {
            return "cannot read jar: " + e.getMessage();
        }
        String hash = Utils.sha256Hex(original);
        Boolean decision = approvalOnDisk(hash);
        if (!Boolean.TRUE.equals(decision)) {
            return Boolean.FALSE.equals(decision)
                ? "denied in mod_approvals.json (sha256 " + hash + ")"
                : "not approved: mod_approvals.json has no allow decision for sha256 " + hash;
        }

        int generation = g_generations.merge(packageName, 2, (prev, ignored) -> prev + 1);
        String newPackage = "jsv" + generation + "." + packageName;
        byte[] relocated;
        try {
            relocated = JarRelocator.relocate(original, packageName, newPackage);
        } catch (IOException | RuntimeException e) {
            return "cannot move the new version to package " + newPackage + ": " + e;
        }

        List<Path> switchedOff = new ArrayList<>();
        for (Path old : oldJars) {
            String problem = switchOff(old);
            if (problem != null) {
                switchOnAgain(switchedOff);
                return "could not switch off " + old.getFileName() + ": " + problem;
            }
            switchedOff.add(old);
        }

        // the old version's Lua exposures and callbacks go, so only the new version's stay
        for (Path old : oldJars) {
            ModJournal.undo(old);
        }

        Path key = Path.of(jar + "#" + newPackage);
        Exposer.Registered before = Exposer.registered();
        ModGuard.nameJar(key, modId);
        boolean loaded;
        Loader.g_liveLoad.set(Boolean.TRUE);
        Loader.g_mainFailure.remove();
        try {
            loaded = Loader.transformAndLoadJar(key, newPackage, Loader.Phase.MAIN, relocated);
        } catch (RuntimeException e) {
            Logger.error("hotswap: loading the new version of " + modId + " failed: " + e);
            loaded = false;
        } finally {
            Loader.g_liveLoad.remove();
        }
        Throwable mainFailure = Loader.g_mainFailure.get();
        Loader.g_mainFailure.remove();
        if (!loaded || mainFailure != null) {
            ModJournal.forget(key);
            switchOnAgain(switchedOff);
            for (Path old : oldJars) {
                ModJournal.redo(old);
            }
            return mainFailure != null
                ? "the new version's main() failed: " + mainFailure + "; the old version is on again"
                : "the new version could not be loaded (see log); the old version is on again";
        }

        for (Path old : oldJars) {
            Loader.g_jarLoadStatus.remove(old);
        }
        Loader.g_jarLoadStatus.put(key, new Loader.JavaModLoadState(
            modId, key, new ModFlags(ModFlags.MF_ACTIVE), "updated live", hash, Boolean.TRUE));
        Logger.info("hotswap: updated " + modId + " to " + jar + " as package " + newPackage);
        exposeNow(modId, before);
        ModGuard.startProbation(key);

        PatchEngine.PatchHandle handle = Loader.g_patch_handles.get(key);
        return handle == null || handle.isComplete() ? null : "updated, but some classes could not be patched (see log)";
    }

    // --- Jumpstart fork: crash protection and uninstall --------------------------------------------

    /**
     * Called by {@link ModGuard} on its own thread when a mod failed too often: removes its patches
     * if they are switchable and takes back what it registered. Its advice is already silenced by
     * the trip wire. Returns null, or why the patches stay until restart.
     */
    static synchronized String offByGuard(Path jar) {
        String problem = g_switchedOff.contains(jar) ? null : switchOff(jar);
        ModJournal.undo(jar);
        return problem;
    }

    /** Jumpstart: the newest crash-protection event number, as a decimal string ("0" if none). */
    public static String guardLastSeq() {
        return Long.toString(ModGuard.lastSeq());
    }

    /**
     * Jumpstart: crash-protection events newer than {@code sinceSeq}, one per line, fields separated
     * by tabs: seq, mod id, kind (advice|main|callback|lua|delegation), where, exception (one line),
     * action (logged|off|refused). "" if there are none.
     */
    public static String guardEvents(String sinceSeq) {
        long since;
        try {
            since = Utils.isBlank(sinceSeq) ? 0 : Long.parseLong(sinceSeq.trim());
        } catch (NumberFormatException e) {
            since = 0;
        }
        return ModGuard.eventsSince(since);
    }

    /** Jumpstart: "count TAB state" for a mod (state ok, off-by-guard or probation), or "" if unknown. */
    public static synchronized String guardStatus(String modId) {
        List<Path> jars = resolve(modId);
        return jars.isEmpty() ? "" : ModGuard.status(jars);
    }

    /**
     * Jumpstart (uninstall): switches every jar of the mod off for good in this session and takes
     * back everything it registered. Patches that cannot be removed are silenced. The classes stay
     * until restart. Returns "OK ..." or "FAILED ...".
     */
    public static synchronized String forget(String modId) {
        List<Path> jars = resolve(modId);
        if (jars.isEmpty()) {
            return "FAILED " + modId + ": no loaded Java mod with this id or jar";
        }
        List<String> kept = new ArrayList<>();
        for (Path jar : jars) {
            if (!g_switchedOff.contains(jar) && Loader.g_patch_handles.containsKey(jar)) {
                String problem = switchOff(jar);
                if (problem != null) {
                    kept.add(jar.getFileName() + ": patches silenced until restart (" + problem + ")");
                }
            }
            ModGuard.silence(jar);
            String left = ModJournal.forget(jar);
            if (!left.isEmpty()) {
                kept.add(jar.getFileName() + ": " + left);
            }
        }
        Logger.info("hotswap: forgot " + modId + (kept.isEmpty() ? "" : "; " + String.join("; ", kept)));
        return "OK " + modId + " forgotten (classes stay until restart)" + (kept.isEmpty() ? "" : "; " + String.join("; ", kept));
    }

    private static void switchOnAgain(List<Path> jars) {
        for (Path jar : jars) {
            String problem = switchOn(jar);
            if (problem != null) {
                Logger.error("hotswap: could not switch " + jar + " on again: " + problem);
            }
        }
    }

    /** Reads mod_approvals.json again: BiteCheck or Jumpstart may have written to it since start. */
    private static Boolean approvalOnDisk(String sha256) {
        Boolean decision = null;
        for (ModApprovalsStore.ModEntry entry : ModApprovalsStore.load().mods) {
            if (entry != null && sha256.equals(entry.jarHash)) {
                if (!entry.decision) {
                    return Boolean.FALSE; // a deny anywhere wins
                }
                decision = Boolean.TRUE;
            }
        }
        return decision;
    }

    /** A class from the jar that the JVM already has, or null. Checks names only, loads nothing. */
    private static String firstAlreadyDefinedClass(Path jar) throws IOException {
        Set<String> inJar = new java.util.HashSet<>();
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry entry : java.util.Collections.list(jf.entries())) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class")) {
                    inJar.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }
        }
        for (Class<?> loaded : Loader.g_instrumentation.getAllLoadedClasses()) {
            if (inJar.contains(loaded.getName())) {
                return loaded.getName();
            }
        }
        return null;
    }

    private static String forEachJar(String mod, boolean off) {
        List<Path> jars = resolve(mod);
        if (jars.isEmpty()) {
            return "FAILED " + mod + ": no loaded Java mod with this id or jar";
        }
        List<String> problems = new ArrayList<>();
        for (Path jar : jars) {
            String problem;
            if (off) {
                problem = switchOff(jar);
                if (problem == null) {
                    ModJournal.undo(jar);
                    ModGuard.silence(jar); // the trip wire: nothing of it runs while it is off
                }
            } else {
                boolean tripped = ModGuard.isTripped(jar);
                ModGuard.reset(jar); // counting starts over, with probation
                problem = switchOn(jar);
                if (tripped && "was not switched off".equals(problem)) {
                    problem = null; // only its advice was silenced (not switchable); now it runs again
                }
                if (problem == null) {
                    ModJournal.redo(jar);
                }
            }
            if (problem != null) {
                problems.add(jar.getFileName() + ": " + problem);
            }
        }
        String verb = off ? "off" : "on";
        return problems.isEmpty()
            ? "OK " + mod + " " + verb
            : "FAILED " + mod + " " + verb + ": " + String.join("; ", problems);
    }

    private static String describe(Path jar) {
        if (g_switchedOff.contains(jar)) {
            return ModGuard.isTripped(jar) ? "off (crash protection)" : "off";
        }
        if (ModGuard.isTripped(jar)) {
            return "off (crash protection; its patches stay until restart, but do nothing)";
        }
        PatchEngine.PatchHandle handle = Loader.g_patch_handles.get(jar);
        if (handle == null) {
            return "no patches";
        }
        String state = handle.isSwitchable() ? "on, switchable" : "on, until restart";
        return handle.isComplete() ? state : state + ", incomplete";
    }

    /** A mod id (as in mod.info, with or without leading backslash) or a jar path. */
    private static List<Path> resolve(String mod) {
        List<Path> jars = new ArrayList<>();
        if (Utils.isBlank(mod)) {
            return jars;
        }
        Path asPath = Path.of(mod);
        if (Files.isRegularFile(asPath)) {
            jars.add(realPath(asPath));
            return jars;
        }
        String wanted = stripBackslash(mod);
        for (var entry : jarsById().entrySet()) {
            if (stripBackslash(entry.getKey()).equals(wanted)) {
                jars.addAll(entry.getValue());
            }
        }
        return jars;
    }

    private static Map<String, List<Path>> jarsById() {
        Map<String, List<Path>> byId = new TreeMap<>();
        for (Loader.JavaModLoadState state : Loader.g_jarLoadStatus.values()) {
            if (state.jarPath() != null && state.id() != null) {
                byId.computeIfAbsent(state.id(), k -> new ArrayList<>()).add(realPath(state.jarPath()));
            }
        }
        return byId;
    }

    private static String stripBackslash(String id) {
        return id.startsWith("\\") ? id.substring(1) : id;
    }

    private static Path realPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize();
        }
    }
}
