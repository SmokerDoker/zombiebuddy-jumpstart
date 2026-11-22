package me.zed_0xff.zombie_buddy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Test access to package-private HostServer methods. */
public final class HostServerAccess {
    private HostServerAccess() {}

    public static List<String> agentOptions(Map<String, String> arguments, Map<String, String> properties, Path jar) {
        return HostServer.agentOptions(arguments, properties, jar);
    }
}
