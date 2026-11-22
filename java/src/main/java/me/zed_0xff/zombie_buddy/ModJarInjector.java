package me.zed_0xff.zombie_buddy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarFile;

import me.zed_0xff.zombie_buddy.transformers.TransformedJar;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;
import net.bytebuddy.dynamic.loading.ClassInjector;
import net.bytebuddy.jar.asm.ClassReader;

/** Defines transformed mod jar classes. */
final class ModJarInjector {
    private ModJarInjector() {}

    static ClassLoader inject(TransformedJar jar) {
        ClassLoader parent = parentLoader();
        HashMap<String, byte[]> definitions = new HashMap<>();
        for (var entry : jar.classes().entrySet()) {
            String name = entry.getKey();
            try {
                parent.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                definitions.put(name, entry.getValue());
            }
        }
        byte[] manifest = jar.resources().get(JarFile.MANIFEST_NAME);
        if (manifest != null) {
            definitions.put(JarFile.MANIFEST_NAME, manifest);
        }

        return new ByteArrayClassLoader.ChildFirst(parent, definitions, ByteArrayClassLoader.PersistenceHandler.MANIFEST);
    }

    /** Define patch jars in the ZombieBuddy loader to keep patch/helper static state single. */
    static ClassLoader injectMerged(TransformedJar jar) {
        ClassLoader parent = parentLoader();
        if (!ClassInjector.UsingUnsafe.isAvailable()) {
            Logger.error("ClassInjector.UsingUnsafe not available; cannot define transformed jar in " + parent);
            return parent;
        }

        HashMap<String, byte[]> toInject = new HashMap<>();
        for (var entry : jar.classes().entrySet()) {
            String name = entry.getKey();
            try {
                parent.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                toInject.put(name, entry.getValue());
            }
        }

        if (!toInject.isEmpty()) {
            Map<String, Class<?>> injected = injectInOrder(new ClassInjector.UsingUnsafe(parent), toInject);
            Logger.debug("Defined " + injected.size() + " transformed classes in " + parent);
        }

        return parent;
    }

    private static ClassLoader parentLoader() {
        ClassLoader parent = ZombieBuddy.class.getClassLoader();
        if (parent == null) {
            parent = Thread.currentThread().getContextClassLoader();
        }
        if (parent == null) {
            parent = ClassLoader.getSystemClassLoader();
        }

        return parent;
    }

    /**
     * Define patch classes in {@code targetLoader} so external patch jars can link from isolated
     * target loaders. Bundled jars are merged and skip this path.
     */
    static void exposePatchesInClassLoader(TransformedJar jar, ClassLoader targetLoader) {
        if (jar == null || targetLoader == null || jar.classes().isEmpty() || jar.patches().isEmpty()) {
            return;
        }

        if (!ClassInjector.UsingUnsafe.isAvailable()) {
            Logger.error("ClassInjector.UsingUnsafe not available; cannot expose patch classes to " + targetLoader);
            return;
        }

        Set<String> patchRoots = new HashSet<>(jar.patches());
        Set<String> packagePrefixes = packagesForPatches(jar.patches());
        HashMap<String, byte[]> toInject = new HashMap<>();

        for (var entry : jar.classes().entrySet()) {
            String name = entry.getKey();
            if (!shouldExposeToGameLoader(name, patchRoots, packagePrefixes)) {
                continue;
            }

            try {
                targetLoader.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                toInject.put(name, entry.getValue());
            }
        }

        if (toInject.isEmpty()) {
            return;
        }

        Map<String, Class<?>> injected = injectInOrder(new ClassInjector.UsingUnsafe(targetLoader), toInject);
        Logger.debug("Exposed " + injected.size() + " patch classes to " + targetLoader);
    }

    /** Same patch type as seen by the loader that defines the instrumented class. */
    static Class<?> loadInClassLoader(Class<?> patchClass, ClassLoader targetLoader) {
        if (targetLoader == null || patchClass.getClassLoader() == targetLoader) {
            return patchClass;
        }

        try {
            return targetLoader.loadClass(patchClass.getName());
        } catch (ClassNotFoundException e) {
            Logger.error("Patch class " + patchClass.getName() + " not found in " + targetLoader + ": " + e.getMessage());
            return patchClass;
        }
    }

    /**
     * Jumpstart fork: defines classes one by one, supertypes first. Defining a class makes the JVM
     * resolve its superclass and interfaces at once, and with Unsafe injection a supertype from the
     * same jar that is not defined yet is not found: NoClassDefFoundError, and the mod fails to load
     * depending on hash map order.
     */
    static Map<String, Class<?>> injectInOrder(ClassInjector injector, Map<String, byte[]> classes) {
        Map<String, Class<?>> injected = new LinkedHashMap<>();
        for (String name : definitionOrder(classes)) {
            injected.putAll(injector.injectRaw(Map.of(name, classes.get(name))));
        }
        return injected;
    }

    /** Class names in an order where every superclass and interface in the map comes first. */
    static List<String> definitionOrder(Map<String, byte[]> classes) {
        List<String> order = new ArrayList<>(classes.size());
        Set<String> done = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String name : new TreeSet<>(classes.keySet())) {
            visit(name, classes, order, done, visiting);
        }
        return order;
    }

    private static void visit(String name, Map<String, byte[]> classes, List<String> order,
                              Set<String> done, Set<String> visiting) {
        if (done.contains(name) || !classes.containsKey(name) || !visiting.add(name)) {
            return; // already placed, not in this jar, or a cycle (invalid bytecode; the JVM reports it)
        }
        for (String supertype : supertypes(classes.get(name))) {
            visit(supertype, classes, order, done, visiting);
        }
        visiting.remove(name);
        done.add(name);
        order.add(name);
    }

    private static List<String> supertypes(byte[] bytes) {
        try {
            ClassReader reader = new ClassReader(bytes);
            List<String> result = new ArrayList<>();
            if (reader.getSuperName() != null) {
                result.add(reader.getSuperName().replace('/', '.'));
            }
            for (String iface : reader.getInterfaces()) {
                result.add(iface.replace('/', '.'));
            }
            return result;
        } catch (RuntimeException e) {
            return List.of(); // unreadable bytes: define it where it falls; the JVM will say what is wrong
        }
    }

    private static Set<String> packagesForPatches(List<String> patches) {
        Set<String> out = new LinkedHashSet<>();
        for (String patch : patches) {
            String pkg = packagePrefix(patch);
            if (!pkg.isEmpty()) {
                out.add(pkg);
            }
        }

        return out;
    }

    private static String packagePrefix(String className) {
        int dollar = className.indexOf('$');
        String base = dollar >= 0 ? className.substring(0, dollar) : className;
        int dot = base.lastIndexOf('.');
        return dot < 0 ? "" : base.substring(0, dot);
    }

    private static boolean shouldExposeToGameLoader(String className, Set<String> patchRoots, Set<String> packagePrefixes) {
        // Never expose classes already present in the ZB loader — they are ZB infrastructure
        // and will reach the game through normal parent delegation, not injection.
        // Without this guard, a patch in the base me.zed_0xff.zombie_buddy package would cause
        // the package-prefix logic to match Loader, Reflect, Exposer, etc., injecting them into
        // the game loader via UsingUnsafe and creating a second copy with independent static state.
        ClassLoader zbLoader = parentLoader();
        try {
            zbLoader.loadClass(className);
            return false;
        } catch (ClassNotFoundException ignored) {}

        if (patchRoots.contains(className)) {
            return true;
        }

        for (String root : patchRoots) {
            if (className.startsWith(root + "$")) {
                return true;
            }
        }

        for (String pkg : packagePrefixes) {
            if (className.startsWith(pkg + ".")) {
                return true;
            }
        }

        return false;
    }
}
