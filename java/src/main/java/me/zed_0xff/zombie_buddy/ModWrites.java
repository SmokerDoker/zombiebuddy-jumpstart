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
 * <p>Not covered: instance fields of game objects, {@code VarHandle} writes, Byte Buddy
 * {@code @FieldValue(readOnly = false)} writes.
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
        Logger.info("journal: " + ModGuard.modIdOfPackage(pkg) + " writes " + what + " (was " + fresh.original + ")");
        ModJournal.record(jar, "static field", what,
            () -> {
                try {
                    Object now = f.get(null);
                    if (!same(now, fresh.last)) {
                        return "changed since (" + now + "), kept";
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
