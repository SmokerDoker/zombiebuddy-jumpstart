package me.zed_0xff.zombie_buddy;

import java.nio.file.Path;
import java.util.List;

/** Test access to package-private crash-protection and journal state. */
public final class GuardAccess {
    private GuardAccess() {}

    public static List<String> globalLuaClasses() {
        return Exposer.getClassesWithGlobalLuaMethod().stream().map(Class::getName).toList();
    }

    public static List<String> exposedClasses() {
        return Exposer.getExposedClasses().stream().map(Class::getName).toList();
    }

    public static int journalSize(Path jar) {
        return ModJournal.size(jar);
    }
}
