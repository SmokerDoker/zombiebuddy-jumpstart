package me.zed_0xff.zombie_buddy.hotswapjournal;

import java.nio.file.Files;
import java.nio.file.Path;

import me.zed_0xff.zombie_buddy.Callbacks;
import zombie.jstest.WriteTarget;

/** Registers what the journal must take back when the mod is switched off. */
public class Main {
    public static void main(String[] args) throws Exception {
        Callbacks.beforeLuaInit.register(() -> { });
        WriteTarget.VALUE = "mod";
        WriteTarget.class.getField("REFLECTED").set(null, "mod");
        WriteTarget.ONE.name = "mod";
        WriteTarget.ONE.speed = 5;
        WriteTarget.TWO.speed = 7;
        String file = System.getProperty("zb.test.journalFile");
        if (file != null) {
            Files.writeString(Path.of(file), "written by HotswapJournal");
        }
    }
}
