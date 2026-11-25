package me.zed_0xff.zombie_buddy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Jumpstart fork: crash protection for Java mods ("Absturzschutz").
 *
 * <p>A mod's Advice is inlined into game methods, so before this a throw from a patch went straight
 * up the game's render or update loop and ended the game. The {@code guard} pipeline step
 * ({@link me.zed_0xff.zombie_buddy.transformers.asmtree.AdviceGuard}) gives every Advice method
 * {@code suppress = Throwable} and {@link PatchEngine} installs {@link #adviceFailed} as its handler:
 * the game method then runs on as if the patch were not there, and the error is counted here.
 *
 * <p>Every caught error is credited to the mod it came from (class index filled when a jar's
 * patches are applied), logged with a rate limit, written once per mod and session to
 * {@code <config_dir>/guard/}, and kept as an event that Jumpstart shows the player. With the
 * agent option {@code guard=switch} (default with hotswap) a mod is switched off when it fails more
 * than {@code guard_limit} times (default 3) in 60 s, at its first error in the 120 s after a live
 * load or update ("probation"), or at once when it is broken for good (a {@link LinkageError}). The
 * trip wire ({@link #isOff}) silences its advice immediately; the patches themselves are removed on
 * a background thread through {@link Hotswap}. ZombieBuddy's own patches and the agent jar's
 * embedded mod are never switched off.
 */
public final class ModGuard {
    static final long WINDOW_MS = 60_000;
    static final long PROBATION_MS = 120_000;
    private static final long LOG_INTERVAL_MS = 60_000;
    private static final int FULL_TRACES = 3;
    private static final int MAX_EVENTS = 500;
    /** ZombieBuddy's own bundled patches; never switched off. */
    private static final String OWN_PATCHES = "me.zed_0xff.zombie_buddy.patches";

    private ModGuard() {}

    // --- options -----------------------------------------------------------------------------------

    /** off: upstream behaviour (no suppress, no counting); log: count and report; switch: also switch off. */
    public static String mode() {
        String value = Agent.arguments.get("guard");
        if (value != null) {
            value = value.trim().toLowerCase();
            if (value.equals("off") || value.equals("log") || value.equals("switch")) {
                return value;
            }
        }
        return Hotswap.isEnabled() ? "switch" : "log";
    }

    public static boolean isEnabled() {
        return !mode().equals("off");
    }

    static int limit() {
        String value = Agent.arguments.get("guard_limit");
        try {
            return value == null ? 3 : Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    /** Agent option guard_exempt=pkg1,pkg2: mods that throw on purpose to abort a game method. */
    public static boolean isExempt(String pkg) {
        String value = Agent.arguments.get("guard_exempt");
        if (Utils.isBlank(value) || Utils.isBlank(pkg)) {
            return false;
        }
        for (String p : value.split("[,;]")) {
            String e = p.trim();
            if (!e.isEmpty() && (pkg.equals(e) || pkg.startsWith(e + "."))) {
                return true;
            }
        }
        return false;
    }

    private static final Set<String> g_protected = ConcurrentHashMap.newKeySet();

    /** The agent jar's embedded mod (Jumpstart): guarded, but never switched off. */
    static void protectPackage(String pkg) {
        if (!Utils.isBlank(pkg)) {
            g_protected.add(pkg);
        }
    }

    /** True for ZombieBuddy's own patches and the embedded mod: never switched off, no trip wire. */
    public static boolean isProtected(String pkg) {
        if (Utils.isBlank(pkg)) {
            return true;
        }
        if (pkg.equals(OWN_PATCHES) || pkg.startsWith(OWN_PATCHES + ".")) {
            return true;
        }
        for (String p : g_protected) {
            if (pkg.equals(p) || pkg.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    // --- trip wire ---------------------------------------------------------------------------------

    private static final Map<String, Integer> g_ids = new HashMap<>();
    private static final List<String> g_idPackages = new ArrayList<>();
    private static volatile boolean[] g_off = new boolean[16];

    /** The trip wire id of a mod package, fixed when its jar is transformed. */
    public static synchronized int idFor(String pkg) {
        Integer id = g_ids.get(pkg);
        if (id == null) {
            id = g_idPackages.size();
            g_idPackages.add(pkg);
            g_ids.put(pkg, id);
        }
        return id;
    }

    /**
     * Called first in every guarded advice method (inserted by AdviceGuard): true when the mod is
     * switched off by the guard, so the advice returns at once. One volatile read.
     */
    public static boolean isOff(int id) {
        boolean[] off = g_off;
        return id >= 0 && id < off.length && off[id];
    }

    private static synchronized void setOff(String pkg, boolean value) {
        if (Utils.isBlank(pkg)) {
            return;
        }
        int id = idFor(pkg);
        boolean[] next = Arrays.copyOf(g_off, Math.max(g_off.length, id + 1));
        next[id] = value;
        g_off = next;
    }

    // --- class index -------------------------------------------------------------------------------

    private static final Map<String, String> g_classPackage = new ConcurrentHashMap<>();
    private static final Map<String, Path> g_packageJar = new ConcurrentHashMap<>();
    private static final Map<Path, String> g_jarPackage = new ConcurrentHashMap<>();

    /** Called when a jar's patches are applied: which classes and package belong to which jar. */
    static void register(Path jar, String pkg, Collection<String> classNames) {
        if (jar == null || Utils.isBlank(pkg)) {
            return;
        }
        g_packageJar.put(pkg, jar);
        g_jarPackage.put(jar, pkg);
        for (String name : classNames) {
            g_classPackage.put(name, pkg);
        }
    }

    /** The mod package a class belongs to, by index or by package prefix; null if none. */
    static String packageOf(String className) {
        if (className == null) {
            return null;
        }
        String pkg = g_classPackage.get(className);
        if (pkg != null) {
            return pkg;
        }
        String best = null;
        for (String p : g_packageJar.keySet()) {
            if (className.startsWith(p + ".") && (best == null || p.length() > best.length())) {
                best = p;
            }
        }
        return best;
    }

    /** The jar a class of a loaded mod comes from, or null for game and ZombieBuddy classes. */
    static Path jarOfClass(String className) {
        String pkg = packageOf(className);
        return pkg == null ? null : g_packageJar.get(pkg);
    }

    static String packageOfJar(Path jar) {
        return jar == null ? null : g_jarPackage.get(jar);
    }

    static Path jarOfPackage(String pkg) {
        return pkg == null ? null : g_packageJar.get(pkg);
    }

    static String modIdOfPackage(String pkg) {
        return modIdOf(jarOfPackage(pkg), pkg);
    }

    // --- stats and events --------------------------------------------------------------------------

    private static final class Stats {
        int count;
        final ArrayDeque<Long> recent = new ArrayDeque<>();
        long probationUntil;
        boolean tripped;
        int fullTraces;
        long lastLog;
        boolean reported;
    }

    /** One caught error, as Jumpstart reads it ({@link #eventsSince}). */
    record Event(long seq, String modId, String kind, String where, String exception, String action) {
        String line() {
            return seq + "\t" + modId + "\t" + kind + "\t" + where + "\t" + exception + "\t" + action;
        }
    }

    private static final Map<Path, Stats> g_stats = new ConcurrentHashMap<>();
    private static final ArrayDeque<Event> g_events = new ArrayDeque<>();
    private static final AtomicLong g_seq = new AtomicLong();

    private static final ExecutorService g_offThread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ZB ModGuard");
        t.setDaemon(true);
        return t;
    });

    /**
     * Exception handler of every guarded advice (installed by {@link PatchEngine}). Runs on the
     * thread that called the game method; must be cheap and must not throw, except for errors the
     * game has to see (out of memory, JVM internal errors).
     */
    public static void adviceFailed(Throwable t, String patchClass) {
        if (t instanceof OutOfMemoryError || t instanceof InternalError) {
            throw (Error) t;
        }
        try {
            record(packageOf(patchClass), "advice", patchClass, t);
        } catch (Throwable ignored) {
            // the guard must never be the one that crashes the game
        }
    }

    /** A mod's main()/premain() threw. Live loads refuse the mod; at game start it is only reported. */
    static void mainFailed(Path jar, String pkg, Throwable t, boolean live) {
        try {
            record(pkg, jar, "main", "main()", t, live ? "refused" : null);
        } catch (Throwable ignored) {
            // reporting only
        }
    }

    /** An error caught somewhere else (Lua, ZombieBuddy callbacks): credited to the first mod class on its stack. */
    public static void attribute(Throwable t, String kind, String where) {
        try {
            String pkg = packageFromStack(t);
            if (pkg != null) {
                record(pkg, kind, where, t);
            }
        } catch (Throwable ignored) {
            // reporting only
        }
    }

    /** A ZombieBuddy callback threw: credited to the mod that registered it, else by stack. */
    static void callbackFailed(Throwable t, String callbackName, Object callback) {
        try {
            String pkg = callback == null ? null : packageOf(callback.getClass().getName());
            if (pkg == null) {
                pkg = packageFromStack(t);
            }
            if (pkg != null) {
                record(pkg, "callback", callbackName, t);
            }
        } catch (Throwable ignored) {
            // reporting only
        }
    }

    private static String packageFromStack(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            for (StackTraceElement e : c.getStackTrace()) {
                String pkg = packageOf(e.getClassName());
                if (pkg != null) {
                    return pkg;
                }
            }
        }
        return null;
    }

    private static void record(String pkg, String kind, String where, Throwable t) {
        record(pkg, pkg == null ? null : g_packageJar.get(pkg), kind, where, t, null);
    }

    private static void record(String pkg, Path jar, String kind, String where, Throwable t, String forcedAction) {
        if (!isEnabled() || t == null) {
            return;
        }
        String modId = modIdOf(jar, pkg);
        long now = System.currentTimeMillis();
        String action;
        boolean schedule = false;
        boolean fullTrace;
        boolean writeReport;
        synchronized (ModGuard.class) {
            Stats s = jar == null ? new Stats() : g_stats.computeIfAbsent(jar, k -> new Stats());
            s.count++;
            s.recent.addLast(now);
            while (!s.recent.isEmpty() && now - s.recent.peekFirst() > WINDOW_MS) {
                s.recent.removeFirst();
            }
            action = forcedAction != null ? forcedAction : "logged";
            boolean switchable = forcedAction == null && jar != null && mode().equals("switch")
                && !isProtected(pkg) && (kind.equals("advice") || kind.equals("callback"));
            if (switchable && !s.tripped) {
                boolean broken = t instanceof LinkageError;
                boolean probation = now < s.probationUntil;
                if (broken || probation || s.recent.size() > limit()) {
                    s.tripped = true;
                    setOff(pkg, true);
                    schedule = true;
                    action = "off";
                }
            }
            fullTrace = s.fullTraces < FULL_TRACES;
            if (fullTrace) {
                s.fullTraces++;
            }
            boolean logLine = fullTrace || now - s.lastLog > LOG_INTERVAL_MS || schedule;
            if (logLine) {
                s.lastLog = now;
            }
            writeReport = !s.reported;
            s.reported = true;
            if (logLine) {
                Logger.error("guard: " + modId + ": " + kind + " " + where + " threw " + oneLine(t)
                    + " (" + s.count + " so far)" + (schedule ? "; switching it off" : ""));
            }
            g_events.addLast(new Event(g_seq.incrementAndGet(), modId, kind, clean(where), oneLine(t), action));
            while (g_events.size() > MAX_EVENTS) {
                g_events.removeFirst();
            }
        }
        if (fullTrace) {
            Logger.printStackTrace(t);
        }
        if (writeReport) {
            writeReport(modId, jar, kind, where, t);
        }
        if (schedule) {
            final Path offJar = jar;
            g_offThread.submit(() -> {
                try {
                    String problem = Hotswap.offByGuard(offJar);
                    if (problem != null) {
                        Logger.warn("guard: " + modId + ": patches stay until restart (" + problem + "); its advice is silenced");
                    }
                } catch (Throwable e) {
                    Logger.error("guard: switching off " + modId + " failed: " + e);
                }
            });
        }
    }

    private static void writeReport(String modId, Path jar, String kind, String where, Throwable t) {
        try {
            Path dir = Agent.configDir().resolve("guard");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            StringWriter trace = new StringWriter();
            t.printStackTrace(new PrintWriter(trace));
            String text = "mod: " + modId + "\njar: " + jar + "\nkind: " + kind + "\nwhere: " + where
                + "\ntime: " + LocalDateTime.now() + "\n\n" + trace;
            Files.writeString(dir.resolve(stamp + "-" + safe(modId) + ".txt"), text, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Logger.warn("guard: cannot write the report for " + modId + ": " + e);
        }
    }

    // --- state changes from Hotswap ----------------------------------------------------------------

    /** After a live load or update: the first error in the next 120 s switches the mod off. */
    static synchronized void startProbation(Path jar) {
        Stats s = g_stats.computeIfAbsent(jar, k -> new Stats());
        s.probationUntil = System.currentTimeMillis() + PROBATION_MS;
        s.tripped = false;
        s.recent.clear();
        setOff(packageOfJar(jar), false);
    }

    /** Switched on again by the player: counting starts over, with probation. */
    static synchronized void reset(Path jar) {
        Stats s = g_stats.computeIfAbsent(jar, k -> new Stats());
        s.count = 0;
        s.fullTraces = 0;
        s.reported = false;
        startProbation(jar);
    }

    /** Uninstalled: its advice stays silent until restart, even if its patches cannot be removed. */
    static synchronized void silence(Path jar) {
        setOff(packageOfJar(jar), true);
    }

    static synchronized boolean isTripped(Path jar) {
        Stats s = g_stats.get(jar);
        return s != null && s.tripped;
    }

    // --- read-only API -----------------------------------------------------------------------------

    public static long lastSeq() {
        return g_seq.get();
    }

    /** Events newer than {@code since}, one per line (see {@link Event#line()}). */
    public static synchronized String eventsSince(long since) {
        StringBuilder sb = new StringBuilder();
        for (Event e : g_events) {
            if (e.seq() > since) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(e.line());
            }
        }
        return sb.toString();
    }

    /** {@code count TAB state} over the given jars (state ok, probation or off-by-guard); "" if none is known. */
    static synchronized String status(Collection<Path> jars) {
        int count = 0;
        boolean known = false;
        boolean tripped = false;
        boolean probation = false;
        long now = System.currentTimeMillis();
        for (Path jar : jars) {
            Stats s = g_stats.get(jar);
            if (s == null) {
                known |= g_jarPackage.containsKey(jar);
                continue;
            }
            known = true;
            count += s.count;
            tripped |= s.tripped;
            probation |= now < s.probationUntil;
        }
        if (!known) {
            return "";
        }
        return count + "\t" + (tripped ? "off-by-guard" : probation ? "probation" : "ok");
    }

    /** For Lua and the log: one line per mod with errors. */
    public static synchronized String statusText() {
        StringBuilder sb = new StringBuilder("guard " + mode() + ", limit " + limit() + " per minute");
        for (var e : g_stats.entrySet()) {
            Stats s = e.getValue();
            sb.append('\n').append(modIdOf(e.getKey(), packageOfJar(e.getKey()))).append("  errors ").append(s.count)
                .append(s.tripped ? "  off by guard" : System.currentTimeMillis() < s.probationUntil ? "  probation" : "");
        }
        return sb.toString();
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** Mod ids Hotswap names before a live load, when ZombieBuddy has no load state for the jar yet. */
    private static final Map<Path, String> g_jarIds = new ConcurrentHashMap<>();

    static void nameJar(Path jar, String modId) {
        if (jar != null && !Utils.isBlank(modId)) {
            g_jarIds.put(jar, modId.startsWith("\\") ? modId.substring(1) : modId);
        }
    }

    private static String modIdOf(Path jar, String pkg) {
        if (jar != null && g_jarIds.containsKey(jar)) {
            return g_jarIds.get(jar);
        }
        if (jar != null) {
            Loader.JavaModLoadState state = Loader.g_jarLoadStatus.get(jar);
            if (state == null) {
                for (Loader.JavaModLoadState s : Loader.g_jarLoadStatus.values()) {
                    if (s.jarPath() != null && same(s.jarPath(), jar)) {
                        state = s;
                        break;
                    }
                }
            }
            if (state != null && !Utils.isBlank(state.id())) {
                return state.id().startsWith("\\") ? state.id().substring(1) : state.id();
            }
            if (jar.getFileName() != null) {
                return jar.getFileName().toString();
            }
        }
        return pkg == null ? "?" : pkg;
    }

    private static boolean same(Path a, Path b) {
        try {
            return a.toRealPath().equals(b.toRealPath());
        } catch (IOException | RuntimeException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static String oneLine(Throwable t) {
        String msg = t.getMessage();
        return clean(t.getClass().getName() + (msg == null ? "" : ": " + msg));
    }

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
        return one.length() > 300 ? one.substring(0, 300) + "..." : one;
    }

    private static String safe(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
