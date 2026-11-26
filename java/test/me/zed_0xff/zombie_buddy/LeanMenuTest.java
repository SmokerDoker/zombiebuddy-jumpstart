package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Jumpstart fork: the lean main menu keeps only ZombieBuddy of the menu's mod list. */
public class LeanMenuTest {
    @Test
    void keepsOnlyZombieBuddyAsTheListWritesIt() {
        assertEquals(List.of("\\ZombieBuddy"), LeanMenu.keepOf(List.of("\\HereGoesTheSun", "\\ZombieBuddy", "\\Bandits2")));
        assertEquals(List.of("ZombieBuddy"), LeanMenu.keepOf(List.of("ZombieBuddy", "tsarslib")));
        assertEquals(List.of(), LeanMenu.keepOf(List.of("tsarslib")));
    }

    @Test
    void comparesIdsWithoutTheBackslash() {
        assertEquals(List.of("ZombieBuddy", "Bandits2"), LeanMenu.ids(List.of("\\ZombieBuddy", "Bandits2")));
    }
}
