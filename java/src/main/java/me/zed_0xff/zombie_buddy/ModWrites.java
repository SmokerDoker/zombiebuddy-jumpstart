package me.zed_0xff.zombie_buddy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jumpstart fork, clean removal: values a mod writes into static fields of game classes. The
 * {@code writes} pipeline step calls {@link #beforeStatic} before every such {@code PUTSTATIC} and
 * {@link #beforeFieldSet} before every {@code Field.set*} in the mod's code. The first write per
 * field and mod keeps the field's original value and records an undo in the mod's
 * {@link ModJournal}: switching the mod off puts the original back, but only if the field still
 * holds the mod's last value (otherwise the game or another mod changed it since, and it is kept).
 *
 * <p>Instance fields of game objects too ({@link #beforeField}, before every {@code PUTFIELD} into a
 * game class): per field and mod, each object it wrote (held weakly: the game may drop it) with its
 * original value and the mod's last; switching off puts the original back where the field still
 * holds the mod's value. At most {@link #MAX_OBJECTS} objects per field and mod are followed.
 *
 * <p>Not covered: {@code VarHandle} writes, Byte Buddy {@code @FieldValue(readOnly = false)} writes,
 * writes in a mod's constructors (the object is not initialized there yet).
 */
public final class ModWrites {
    private static final String[] GAME_PREFIXES = { "zombie.", "se.krka.", "fmod.", "astar." };

    private static final class Write {
        final Field field;
        final Object original;
        volatile Object last;

        Write(Field field, Object original) {
            this.field = field;
            this.original = original;
        }
    }

    private static final Map<String, Write> g_writes = new ConcurrentHashMap<>();

    private ModWrites() {}

    /** Before {@code PUTSTATIC owner.name} in a mod's code (owner as internal name). Never throws. */
    public static void beforeStatic(Object value, String owner, String name, String pkg) {
        try {
            String key = owner + "." + name + "|" + pkg;
            Write w = g_writes.get(key);
            if (w == null) {
                Class<?> cls = classFor(owner.replace('/', '.'));
                if (cls == null) {
                    return;
                }
                Field f = findField(cls, name);
                if (f == null) {
                    return;
                }
                w = first(key, f, pkg);
                if (w == null) {
                    return;
                }
            }
            w.last = value;
        } catch (Throwable ignored) {
            // reporting must never break the mod's write
        }
    }

    // --- instance fields --------------------------------------------------------------------------

    static final int MAX_OBJECTS = 50_000;

    /** A game object by identity, held weakly. */
    private static final class Ref extends java.lang.ref.WeakReference<Object> {
        final int hash;

        Ref(Object o, java.lang.ref.ReferenceQueue<Object> q) {
            super(o, q);
            hash = System.identityHashCode(o);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            Object mine = get();
            return mine != null && other instanceof Ref r && r.get() == mine;
        }
    }

    /** One object a mod wrote a field of: the value before its first write, and its last. */
    private static final class Slot {
        final Object original;
        volatile Object last;

        Slot(Object original) {
            this.original = original;
        }
    }

    /** One instance field of a game class a mod writes, on every object it wrote it on. */
    private static final class InstanceWrites {
        final Field field;
        final Map<Ref, Slot> objects = new ConcurrentHashMap<>();
        final java.lang.ref.ReferenceQueue<Object> gone = new java.lang.ref.ReferenceQueue<>();
        volatile boolean full;

        InstanceWrites(Field field) {
            this.field = field;
        }

        void wrote(Object target, Object value) throws IllegalAccessException {
            for (java.lang.ref.Reference<?> r; (r = gone.poll()) != null; ) {
                objects.remove(r);
            }
            Ref probe = new Ref(target, null);
            Slot s = objects.get(probe);
            if (s == null) {
                if (objects.size() >= MAX_OBJECTS) {
                    if (!full) {
                        full = true;
                        Logger.warn("journal: more than " + MAX_OBJECTS + " objects for " + field.getDeclaringClass().getName() + "."
                            + field.getName() + "; the further ones are not taken back");
                    }
                    return;
                }
                s = new Slot(field.get(target));
                objects.put(new Ref(target, gone), s);
            }
            s.last = value;
        }

        String undo() {
            int kept = 0;
            for (Map.Entry<Ref, Slot> e : objects.entrySet()) {
                Object o = e.getKey().get();
                if (o == null) {
                    continue;
                }
                try {
                    if (same(field.get(o), e.getValue().last)) {
                        field.set(o, e.getValue().original);
                    } else {
                        kept++;
                    }
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    kept++;
                }
            }
            return kept == 0 ? null : kept + " objects changed since, kept";
        }

        String redo() {
            for (Map.Entry<Ref, Slot> e : objects.entrySet()) {
                Object o = e.getKey().get();
                if (o == null) {
                    continue;
                }
                try {
                    if (same(field.get(o), e.getValue().original)) {
                        field.set(o, e.getValue().last);
                    }
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    // left as it is
                }
            }
            return null;
        }
    }

    private static final Map<String, InstanceWrites> g_instances = new ConcurrentHashMap<>();

    /** Before {@code PUTFIELD owner.name} on {@code target} in a mod's code (owner a game class). Never throws. */
    public static void beforeField(Object target, Object value, String owner, String name, String pkg) {
        try {
            if (target == null) {
                return;
            }
            String key = owner + "." + name + "|" + pkg;
            InstanceWrites w = g_instances.get(key);
            if (w == null) {
                w = firstInstance(key, target.getClass(), name, pkg);
                if (w == null) {
                    return;
                }
            }
            w.wrote(target, value);
        } catch (Throwable ignored) {
            // reporting must never break the mod's write
        }
    }

    private static synchronized InstanceWrites firstInstance(String key, Class<?> cls, String name, String pkg) {
        InstanceWrites w = g_instances.get(key);
        if (w != null) {
            return w;
        }
        Path jar = ModGuard.jarOfPackage(pkg);
        Field f = findInstanceField(cls, name);
        if (jar == null || f == null) {
            return null;
        }
        f.setAccessible(true);
        InstanceWrites fresh = new InstanceWrites(f);
        g_instances.put(key, fresh);
        String what = f.getDeclaringClass().getName() + "." + f.getName();
        Logger.info("journal: " + ModGuard.modIdOfPackage(pkg) + " writes " + what + " of game objects (" + f.getType().getSimpleName() + ")");
        ModJournal.record(jar, "instance field", what, fresh::undo, fresh::redo);
        return fresh;
    }

    private static Field findInstanceField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (!Modifier.isStatic(f.getModifiers())) {
                    return f;
                }
            } catch (NoSuchFieldException e) {
                // superclass
            }
        }
        return null;
    }

    /** Before {@code field.set*(target, value)} in a mod's code; only static fields of game classes count. Never throws. */
    public static void beforeFieldSet(Field field, Object target, Object value, String pkg) {
        try {
            if (field == null || !Modifier.isStatic(field.getModifiers()) || !isGame(field.getDeclaringClass().getName())) {
                return;
            }
            String key = field.getDeclaringClass().getName().replace('.', '/') + "." + field.getName() + "|" + pkg;
            Write w = g_writes.get(key);
            if (w == null) {
                w = first(key, field, pkg);
                if (w == null) {
                    return;
                }
            }
            w.last = value;
        } catch (Throwable ignored) {
            // reporting must never break the mod's write
        }
    }

    private static synchronized Write first(String key, Field f, String pkg) throws ReflectiveOperationException {
        Write w = g_writes.get(key);
        if (w != null) {
            return w;
        }
        Path jar = ModGuard.jarOfPackage(pkg);
        if (jar == null) {
            return null;
        }
        f.setAccessible(true);
        Write fresh = new Write(f, f.get(null));
        g_writes.put(key, fresh);
        String what = f.getDeclaringClass().getName() + "." + f.getName();
        // field names only: a game field can hold a server address or a password, and logs get shared
        Logger.info("journal: " + ModGuard.modIdOfPackage(pkg) + " writes " + what + " (" + f.getType().getSimpleName() + ")");
        ModJournal.record(jar, "static field", what,
            () -> {
                try {
                    Object now = f.get(null);
                    if (!same(now, fresh.last)) {
                        return "changed since, kept";
                    }
                    f.set(null, fresh.original);
                    return null;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return "cannot restore: " + e;
                }
            },
            () -> {
                try {
                    if (same(f.get(null), fresh.original)) {
                        f.set(null, fresh.last);
                    }
                    return null;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return "cannot set again: " + e;
                }
            });
        return fresh;
    }

    /** Same value: equal for strings and boxed primitives, the same object otherwise. */
    private static boolean same(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof String || a instanceof Number || a instanceof Boolean || a instanceof Character) {
            return a.equals(b);
        }
        return false;
    }

    private static boolean isGame(String className) {
        for (String p : GAME_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> classFor(String name) {
        for (ClassLoader cl : new ClassLoader[] { Thread.currentThread().getContextClassLoader(), ClassLoader.getSystemClassLoader() }) {
            if (cl == null) {
                continue;
            }
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException e) {
                // next loader
            }
        }
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                if (Modifier.isStatic(f.getModifiers())) {
                    return f;
                }
            } catch (NoSuchFieldException e) {
                // superclass
            }
        }
        return null;
    }
}
