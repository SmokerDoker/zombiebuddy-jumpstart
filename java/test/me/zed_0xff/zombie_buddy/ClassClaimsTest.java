package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Jumpstart fork: what a jar gets when another mod jar brought some of its classes already. */
class ClassClaimsTest {
    private static final Path A = Path.of("a.jar").toAbsolutePath();
    private static final Path B = Path.of("b.jar").toAbsolutePath();

    private BiPredicate<Path, Path> requires;

    @BeforeEach
    void setUp() throws Exception {
        requires = ClassClaims.g_requires;
        ClassClaims.g_requires = (jar, other) -> false;
        ClassClaims.reset();
        ClassClaims.claim(ClassClaims.plan(A, "moda", jar(Map.of("moda/Main", "1", "lib/X", "x1", "lib/Same", "s"))));
    }

    @AfterEach
    void tearDown() {
        ClassClaims.g_requires = requires;
        ClassClaims.reset();
    }

    @Test
    void anotherVersionOfALibraryMovesEverythingOutsideTheOwnPackage() throws Exception {
        ClassClaims.Plan plan = ClassClaims.plan(B, "modb", jar(Map.of(
                "modb/Main", "2", "lib/X", "x2", "lib/Same", "s", "lib/Y", "y", "java/lang/String", "not really")));
        assertTrue(plan.relocates());
        assertEquals(Set.of("lib/X", "lib/Same", "lib/Y"), plan.moved(),
                "the whole library, the same classes too (they call the other ones); not the own package, not Java's classes");
        assertEquals(plan.prefix() + "lib/X", plan.map("lib/X"));
        assertEquals("modb/Main", plan.map("modb/Main"));
        assertEquals(1, plan.clashes().size());
        String[] f = plan.clashes().get(0).split("\t", -1);
        assertEquals(List.of(ClassClaims.OWN_COPY, "a.jar", "1", "lib.X"), List.of(f[0], f[1], f[2], f[3]));
        assertEquals(plan.prefix().replace('/', '.'), f[4]);

        ClassClaims.claim(plan);
        assertEquals(plan.clashes(), ClassClaims.clashesOf(B));
        ClassClaims.Plan third = ClassClaims.plan(Path.of("c.jar").toAbsolutePath(), "modc", jar(Map.of("lib/X", "x1")));
        assertFalse(third.relocates(), "lib.X under its own name is still A's");
    }

    @Test
    void theSameContentsAreShared() throws Exception {
        ClassClaims.Plan plan = ClassClaims.plan(B, "modb", jar(Map.of("modb/Main", "2", "lib/X", "x1", "lib/Same", "s")));
        assertFalse(plan.relocates());
        assertTrue(plan.clashes().isEmpty());
    }

    @Test
    void aRequiredModsClassesStayShared() throws Exception {
        ClassClaims.g_requires = (jar, other) -> jar.equals(B) && other.equals(A);
        ClassClaims.Plan plan = ClassClaims.plan(B, "modb", jar(Map.of("modb/Main", "2", "lib/X", "x2", "lib/Same", "s")));
        assertFalse(plan.relocates(), "B needs A's library: one copy, A's");
        assertEquals(ClassClaims.REQUIRED, plan.clashes().get(0).split("\t")[0]);
    }

    @Test
    void aClashInTheOwnPackageIsOnlyReported() throws Exception {
        ClassClaims.Plan plan = ClassClaims.plan(B, "moda", jar(Map.of("moda/Main", "other")));
        assertFalse(plan.relocates(), "the own package holds the patches: it is not moved");
        assertEquals(ClassClaims.OWN_PACKAGE, plan.clashes().get(0).split("\t")[0]);
    }

    /** A jar with entries name.class whose contents are the given text (only hashed, never loaded). */
    private static byte[] jar(Map<String, String> classes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JarOutputStream jos = new JarOutputStream(out)) {
            for (Map.Entry<String, String> e : new LinkedHashMap<>(classes).entrySet()) {
                jos.putNextEntry(new JarEntry(e.getKey() + ".class"));
                jos.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
