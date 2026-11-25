package me.zed_0xff.zombie_buddy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * Jumpstart fork: what a mod registered with ZombieBuddy, so switching it off takes it back
 * ("sauberes Entfernen", the parts ZombieBuddy controls).
 *
 * <p>Before, only a mod's patches were removed. Its Lua classes and global functions stayed, and
 * {@link Exposer#afterExposeAll} even put them back at every Lua start; its ZombieBuddy callbacks
 * kept running. Now every registration made while a jar is "current" ({@link #during}: its main()
 * and exposure) or by one of its classes later is recorded per jar with an undo and a redo.
 * {@link Hotswap#off} runs the undos, newest first; {@link Hotswap#on} the redos.
 *
 * <p>An undo never forces: a Lua value is only taken back if it is still the one the mod set
 * (another mod may have replaced it since); it returns what it had to keep. Work on Lua tables runs
 * on the game's main thread ({@link #onMainThread}), because Kahlua tables are not thread-safe.
 */
public final class ModJournal {

    /** One registration. undo/redo return null when done, otherwise what was kept and why. */
    record Entry(String kind, String what, Supplier<String> undo, Supplier<String> redo) {
    }

    private static final class Book {
        final List<Entry> entries = new ArrayList<>();
        boolean undone;
    }

    private static final Map<Path, Book> g_books = new ConcurrentHashMap<>();
    private static final ThreadLocal<Path> g_current = new ThreadLocal<>();

    private ModJournal() {}

    /** Runs {@code r} with {@code jar} as the owner of every registration it makes. */
    static void during(Path jar, Runnable r) {
        Path prev = g_current.get();
        g_current.set(jar);
        try {
            r.run();
        } finally {
            if (prev == null) {
                g_current.remove();
            } else {
                g_current.set(prev);
            }
        }
    }

    /** The jar a registration belongs to: the current one, else the mod the class comes from; null for ZombieBuddy and the game. */
    static Path ownerOf(Class<?> cls) {
        if (cls == null) {
            return g_current.get();
        }
        Path own = ModGuard.jarOfClass(cls.getName());
        if (own != null) {
            return own;
        }
        // ZombieBuddy's own classes (e.g. a static initializer that runs during a mod's main())
        // never belong to the mod: taking their registrations back would break ZombieBuddy
        if (cls.getName().startsWith("me.zed_0xff.zombie_buddy.")) {
            return null;
        }
        return g_current.get();
    }

    static void record(Path jar, String kind, String what, Supplier<String> undo, Supplier<String> redo) {
        if (jar == null) {
            return;
        }
        Book book = g_books.computeIfAbsent(jar, k -> new Book());
        synchronized (book) {
            // a redo registers again through the same code: keep one entry per registration
            for (Entry e : book.entries) {
                if (e.kind().equals(kind) && e.what().equals(what)) {
                    return;
                }
            }
            book.entries.add(new Entry(kind, what, undo, redo));
        }
    }

    /** Takes back what the jar registered, newest first. Returns a short report ("" when everything was taken back). */
    static String undo(Path jar) {
        Book book = g_books.get(jar);
        if (book == null) {
            return "";
        }
        List<Entry> entries;
        synchronized (book) {
            if (book.undone) {
                return "";
            }
            book.undone = true;
            entries = new ArrayList<>(book.entries);
        }
        List<String> kept = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            run(e, e.undo(), kept);
        }
        if (!entries.isEmpty()) {
            Logger.info("journal: " + jar.getFileName() + ": took back " + (entries.size() - kept.size()) + " of "
                + entries.size() + " registration(s)" + (kept.isEmpty() ? "" : "; kept: " + String.join("; ", kept)));
        }
        return String.join("; ", kept);
    }

    /** Registers again what {@link #undo} took back, oldest first. */
    static String redo(Path jar) {
        Book book = g_books.get(jar);
        if (book == null) {
            return "";
        }
        List<Entry> entries;
        synchronized (book) {
            if (!book.undone) {
                return "";
            }
            book.undone = false;
            entries = new ArrayList<>(book.entries);
        }
        List<String> kept = new ArrayList<>();
        for (Entry e : entries) {
            run(e, e.redo(), kept);
        }
        return String.join("; ", kept);
    }

    /** Uninstall: take everything back and drop the record; the classes stay until restart. */
    static String forget(Path jar) {
        String kept = undo(jar);
        g_books.remove(jar);
        return kept;
    }

    /** Number of registrations recorded for a jar (tests, status). */
    public static int size(Path jar) {
        Book book = g_books.get(jar);
        if (book == null) {
            return 0;
        }
        synchronized (book) {
            return book.entries.size();
        }
    }

    public static boolean isUndone(Path jar) {
        Book book = g_books.get(jar);
        return book != null && book.undone;
    }

    private static void run(Entry e, Supplier<String> step, List<String> kept) {
        if (step == null) {
            return;
        }
        try {
            String problem = step.get();
            if (problem != null) {
                kept.add(e.kind() + " " + e.what() + ": " + problem);
            }
        } catch (Throwable t) {
            kept.add(e.kind() + " " + e.what() + ": " + t);
        }
    }

    // --- main thread ---------------------------------------------------------------------------------

    private static volatile Thread g_mainThread;
    private static final ConcurrentLinkedQueue<Runnable> g_mainQueue = new ConcurrentLinkedQueue<>();

    /** Registered once at agent start: runs at the end of every UI frame on the game's main thread. */
    static void installPump() {
        Callbacks.onEndFrameUI.register(ModJournal::pump);
    }

    private static void pump() {
        g_mainThread = Thread.currentThread();
        for (Runnable r; (r = g_mainQueue.poll()) != null; ) {
            try {
                r.run();
            } catch (Throwable t) {
                Logger.error("journal: " + t);
            }
        }
    }

    /**
     * Runs {@code r} now if this is the game's main thread (or no frame was drawn yet, e.g. in tests
     * or during start), otherwise at the end of the next frame.
     */
    static void onMainThread(Runnable r) {
        Thread main = g_mainThread;
        if (main == null || main == Thread.currentThread()) {
            r.run();
        } else {
            g_mainQueue.add(r);
        }
    }
}
