package me.zed_0xff.zombie_buddy.hotswaplive;

/** Implements an interface from the same jar (regression test for the definition order). */
public final class LiveSuffix implements SuffixSource {
    @Override
    public String value() {
        return " +live";
    }
}
