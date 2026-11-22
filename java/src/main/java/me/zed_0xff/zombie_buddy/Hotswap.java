package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

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
 * <p>From Lua (debug console):
 * <pre>
 *   print(ZombieBuddy.Hotswap.status())
 *   print(ZombieBuddy.Hotswap.off("ZBHelloWorld"))
 *   print(ZombieBuddy.Hotswap.on("ZBHelloWorld"))
 * </pre>
 */
@Exposer.LuaClass(name = "ZombieBuddy.Hotswap")
public final class Hotswap {
    private static final Set<Path> g_switchedOff = ConcurrentHashMap.newKeySet();

    private Hotswap() {}

    public static boolean isEnabled() {
        String value = Agent.arguments.get("hotswap");
        return value != null && !value.equalsIgnoreCase("false"); // "hotswap" without a value means on
    }

    /** Lua: switches off every jar of a mod (mod id or jar path). Returns a one-line result. */
    public static synchronized String off(String mod) {
        return forEachJar(mod, true);
    }

    /** Lua: switches a mod back on that was switched off with {@link #off}. Returns a one-line result. */
    public static synchronized String on(String mod) {
        return forEachJar(mod, false);
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

    private static String forEachJar(String mod, boolean off) {
        List<Path> jars = resolve(mod);
        if (jars.isEmpty()) {
            return "FAILED " + mod + ": no loaded Java mod with this id or jar";
        }
        List<String> problems = new ArrayList<>();
        for (Path jar : jars) {
            String problem = off ? switchOff(jar) : switchOn(jar);
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
            return "off";
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
