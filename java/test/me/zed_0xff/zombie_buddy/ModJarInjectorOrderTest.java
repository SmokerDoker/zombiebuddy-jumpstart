package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Opcodes;

/** Jumpstart fork: classes of a merged mod jar are defined supertypes first. */
public class ModJarInjectorOrderTest {

    private static byte[] type(String name, String superName, String... interfaces) {
        ClassWriter w = new ClassWriter(0);
        int access = Opcodes.ACC_PUBLIC | (superName == null ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
        w.visit(Opcodes.V17, access, name.replace('.', '/'),
                null, superName == null ? "java/lang/Object" : superName.replace('.', '/'),
                java.util.Arrays.stream(interfaces).map(i -> i.replace('.', '/')).toArray(String[]::new));
        w.visitEnd();
        return w.toByteArray();
    }

    @Test
    void putsSuperclassesAndInterfacesFirst() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("a.Impl", type("a.Impl", "z.Base", "m.Iface"));
        classes.put("z.Base", type("z.Base", "java.lang.Object", "y.BaseIface"));
        classes.put("m.Iface", type("m.Iface", null, "y.BaseIface"));
        classes.put("y.BaseIface", type("y.BaseIface", null));
        classes.put("b.Other", type("b.Other", "java.lang.Object"));

        List<String> order = ModJarInjector.definitionOrder(classes);

        assertEquals(5, order.size());
        assertTrue(order.indexOf("z.Base") < order.indexOf("a.Impl"), order.toString());
        assertTrue(order.indexOf("m.Iface") < order.indexOf("a.Impl"), order.toString());
        assertTrue(order.indexOf("y.BaseIface") < order.indexOf("z.Base"), order.toString());
        assertTrue(order.indexOf("y.BaseIface") < order.indexOf("m.Iface"), order.toString());
    }

    @Test
    void keepsTypesFromOutsideTheJarOutOfTheOrder() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("a.Impl", type("a.Impl", "java.util.ArrayList", "java.lang.Runnable"));
        assertEquals(List.of("a.Impl"), ModJarInjector.definitionOrder(classes));
    }

    @Test
    void aClassWhoseSupertypeIsNowhereIsSkippedNotTheWholeJar() {
        Map<String, byte[]> classes = new HashMap<>();
        classes.put("knoxtest.Good", type("knoxtest.Good", "java.lang.Object"));
        classes.put("knoxtest.OtherRuntimeEntry", type("knoxtest.OtherRuntimeEntry", "java.lang.Object", "otherruntime.api.Module"));
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
        Map<String, Class<?>> injected = ModJarInjector.injectInOrder(
                new net.bytebuddy.dynamic.loading.ClassInjector.UsingReflection(loader), classes);
        assertEquals(List.of("knoxtest.Good"), List.copyOf(injected.keySet()));
    }

    @Test
    void namesTheMissingType() {
        assertEquals("a.b.C", ModJarInjector.missingType(new IllegalStateException(new NoClassDefFoundError("a/b/C"))));
        assertEquals(null, ModJarInjector.missingType(new IllegalStateException("other")));
    }
}
