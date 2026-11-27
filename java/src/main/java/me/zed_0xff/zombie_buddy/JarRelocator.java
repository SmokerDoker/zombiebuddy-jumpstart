package me.zed_0xff.zombie_buddy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.function.UnaryOperator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.commons.ClassRemapper;
import net.bytebuddy.jar.asm.commons.Remapper;

/**
 * Jumpstart fork: moves every class of a mod jar from its package (and subpackages) into another
 * package, so a new version of a mod can be defined next to the old one. Class references are
 * rewritten; string constants (for example a class name passed to Class.forName) are not.
 */
final class JarRelocator {
    private JarRelocator() {}

    static byte[] relocate(byte[] jarBytes, String fromPackage, String toPackage) throws IOException {
        String from = fromPackage.replace('.', '/') + "/";
        String to = toPackage.replace('.', '/') + "/";
        return relocate(jarBytes, internalName -> internalName.startsWith(from) ? to + internalName.substring(from.length()) : internalName);
    }

    /** Renames classes by internal name ({@code a/b/C}); {@code map} returns the name unchanged for the others. */
    static byte[] relocate(byte[] jarBytes, UnaryOperator<String> map) throws IOException {
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return map.apply(internalName);
            }
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream(jarBytes.length + 1024);
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            Manifest manifest = in.getManifest();
            try (JarOutputStream jos = manifest != null ? new JarOutputStream(out, manifest) : new JarOutputStream(out)) {
                JarEntry entry;
                while ((entry = in.getNextJarEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory() || JarFile.MANIFEST_NAME.equals(name)) {
                        continue;
                    }
                    byte[] data = in.readAllBytes();
                    if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.endsWith("module-info.class")) {
                        ClassWriter writer = new ClassWriter(0);
                        new ClassReader(data).accept(new ClassRemapper(writer, remapper), 0);
                        data = writer.toByteArray();
                        name = remapper.map(name.substring(0, name.length() - ".class".length())) + ".class";
                    }
                    jos.putNextEntry(new JarEntry(name));
                    jos.write(data);
                    jos.closeEntry();
                }
            }
        }
        return out.toByteArray();
    }
}
