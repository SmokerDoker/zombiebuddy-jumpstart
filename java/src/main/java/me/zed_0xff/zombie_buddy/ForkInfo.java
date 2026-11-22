package me.zed_0xff.zombie_buddy;

/**
 * Jumpstart fork of ZombieBuddy: what differs from upstream (zed-0xff/ZombieBuddy).
 *
 * <ul>
 *   <li>{@link Hotswap}: switch the patches of loaded Java mods off and on at runtime
 *       (agent option {@code hotswap=true}).</li>
 *   <li>{@link PatchEngine#applyPatches} returns a {@link PatchEngine.PatchHandle} instead of
 *       discarding the installed transformers.</li>
 *   <li>No self-update from an upstream ZombieBuddy.jar, so the fork is not silently replaced.</li>
 * </ul>
 *
 * Everything else, including mod_approvals.json, agent options and the mod API, stays as upstream.
 */
public final class ForkInfo {
    private ForkInfo() {}

    public static final String NAME = "Jumpstart fork";
    public static final String UPSTREAM = "https://github.com/zed-0xff/ZombieBuddy";

    /** Upstream replaces the running jar with a newer Zed-signed one; the fork must not allow that. */
    static final boolean UPSTREAM_SELF_UPDATE = false;
}
