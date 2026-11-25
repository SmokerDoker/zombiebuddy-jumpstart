package me.zed_0xff.zombie_buddy.hotswapmainfail;

import me.zed_0xff.zombie_buddy.Callbacks;

/** main() registers a callback and then fails: a live load must refuse the mod and take the callback back. */
public class Main {
    public static void main(String[] args) {
        Callbacks.afterLuaInit.register(() -> { });
        throw new IllegalStateException("main failed on purpose");
    }
}
