package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Jumpstart fork: a mod built into the agent jar. */
public class EmbeddedModTest {

    @TempDir
    Path tmp;

    private Path agentJar(String attribute, String... names) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (attribute != null) {
            manifest.getMainAttributes().putValue(EmbeddedMod.ATTRIBUTE, attribute);
        }
        Path jar = tmp.resolve("Agent.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out, manifest)) {
            for (String name : names) {
                jos.putNextEntry(new JarEntry(name));
                jos.write(name.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jar;
    }

    private static List<String> names(byte[] jar) throws IOException {
        List<String> names = new ArrayList<>();
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            for (JarEntry e; (e = in.getNextJarEntry()) != null; ) {
                names.add(e.getName());
            }
        }
        return names;
    }

    @Test
    void takesTheNamedPackageWithNormalNames() throws IOException {
        Path jar = agentJar("com.example.mod",
                "me/zed_0xff/zombie_buddy/Agent.class",
                "embedded-mod/com/example/mod/Main.class",
                "embedded-mod/com/example/mod/sub/Patch.class",
                "embedded-mod/com/example/other/Hidden.class",
                "embedded-mod/com/example/mod/texts.properties",
                "com/example/core/Core.class");
        EmbeddedMod.Jar mod = EmbeddedMod.read(jar);
        assertEquals("com.example.mod", mod.packageName());
        assertEquals(List.of("com/example/mod/Main.class", "com/example/mod/sub/Patch.class",
                "com/example/mod/texts.properties"), names(mod.bytes()));
    }

    @Test
    void anAgentJarWithoutTheAttributeHasNoEmbeddedMod() throws IOException {
        assertNull(EmbeddedMod.read(agentJar(null, "embedded-mod/com/example/mod/Main.class")));
    }

    @Test
    void anAttributeWithoutFilesIsAnError() throws IOException {
        Path jar = agentJar("com.example.mod", "com/example/mod/Main.class");
        assertThrows(IOException.class, () -> EmbeddedMod.read(jar));
    }
}
