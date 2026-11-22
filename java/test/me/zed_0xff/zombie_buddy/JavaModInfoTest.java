package me.zed_0xff.zombie_buddy;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import static me.zed_0xff.zombie_buddy.SteamWorkshop.WorkshopItemID;
import java.nio.file.Path;

class JavaModInfoTest {
    private static final String HOME_DIR  = System.getProperty("user.home");
    private static final String CACHE_DIR = HOME_DIR + File.separator + "Zomboid";

    private MockedStatic<Utils> utilsMock;
    @BeforeEach
    void setUp() {
        utilsMock = mockStatic(Utils.class, CALLS_REAL_METHODS);
        utilsMock.when(Utils::getCacheDir).thenReturn(CACHE_DIR);
        utilsMock.when(Utils::getCachePath).thenReturn(Path.of(CACHE_DIR));
    }

    @AfterEach
    void tearDown() {
        utilsMock.close();
    }

    @Test
    void workshopItemIdFromInfPath_valid() {
        assertEquals(
                new WorkshopItemID(3718604798L),
                JavaModInfo.workshopItemIdFromInfPath( Path.of(CACHE_DIR, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/common/mod.info"))
                );
        assertEquals(
                new WorkshopItemID(2986022978L),
                JavaModInfo.workshopItemIdFromInfPath( Path.of(HOME_DIR, "/Library/Application Support/Steam/steamapps/workshop/content/108600/2986022978/mods/DoubleDeckerBusInterior"))
                );
    }

    @Test
    void workshopItemIdFromInfPath_invalid() {
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of(CACHE_DIR, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/common/foo/mod.info")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of(CACHE_DIR, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/mod.info")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of(CACHE_DIR, "Workshop/ZBExhume41/Contents/mods/ZBExhume41/42.13/media/java/ZBExhume41.jar")));
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of("/etc/passwd")));
    }

    @Test
    void workshopItemIdFromInfPath_oneDirBelowRoot() {
        // 4 levels up from mod.info lands on "/x", whose parent is the root (no file name)
        assertNull( JavaModInfo.workshopItemIdFromInfPath( Path.of("/x/cache/mods/ZBPopFix/42.20/mod.info")));
    }

    // --- Jumpstart fork: findFor, used by Hotswap.javaModOf ---------------------------------------

    private static void write(Path file, String text) throws java.io.IOException {
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file, text);
    }

    @Test
    void findFor_jarNextToModInfoInTheVersionFolder(@org.junit.jupiter.api.io.TempDir Path mod) throws Exception {
        // like Project Real Limbs: 42/mod.info, 42/media/java/RealLimbs.jar, and a common folder without either
        write(mod.resolve("42/mod.info"), "id=RealLimbs\njavaJarFile=media/java/RealLimbs.jar\njavaPkgName=reallimbs\n");
        write(mod.resolve("42/media/java/RealLimbs.jar"), "jar");
        write(mod.resolve("common/LICENSE.txt"), "x");
        JavaModInfo info = JavaModInfo.findFor(mod.resolve("common"), mod.resolve("42"), false);
        assertNotNull(info);
        assertEquals(mod.resolve("42/media/java/RealLimbs.jar"), info.jarPath());
        assertEquals("reallimbs", info.javaPkgName());
    }

    @Test
    void findFor_noCommonFolderAtAll(@org.junit.jupiter.api.io.TempDir Path mod) throws Exception {
        // like Staircast: 42.13/mod.info with a client jar, no common folder
        write(mod.resolve("42.13/mod.info"), "id=Staircast\njavaJarFile=media/java/client/staircast.jar\njavaPkgName=pzmod.staircast\n");
        write(mod.resolve("42.13/media/java/client/staircast.jar"), "jar");
        JavaModInfo info = JavaModInfo.findFor(mod.resolve("common"), mod.resolve("42.13"), false);
        assertNotNull(info);
        assertEquals(mod.resolve("42.13/media/java/client/staircast.jar"), info.jarPath());
        assertNull(JavaModInfo.findFor(mod.resolve("common"), mod.resolve("42.13"), true), "a server does not load a client jar");
        assertNotNull(JavaModInfo.findFor(null, mod.resolve("42.13"), false));
    }

    @Test
    void findFor_modInfoInCommonJarInVersionFolder(@org.junit.jupiter.api.io.TempDir Path mod) throws Exception {
        write(mod.resolve("common/mod.info"), "id=Fps\njavaJarFile=media/java/Fps.jar\njavaPkgName=fps\n");
        write(mod.resolve("42.13/media/java/Fps.jar"), "jar");
        JavaModInfo info = JavaModInfo.findFor(mod.resolve("common"), mod.resolve("42.13"), false);
        assertNotNull(info);
        assertEquals(mod.resolve("42.13/media/java/Fps.jar"), info.jarPath());
    }

    @Test
    void findFor_luaOnlyModHasNoJavaPart(@org.junit.jupiter.api.io.TempDir Path mod) throws Exception {
        write(mod.resolve("42/mod.info"), "id=Minimap\nname=Minimap\n");
        write(mod.resolve("42/media/lua/client/Minimap.lua"), "-- lua");
        assertNull(JavaModInfo.findFor(mod.resolve("common"), mod.resolve("42"), false));
    }
}
