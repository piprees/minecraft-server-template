package com.customdimensions.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roster's dedup and precedence rules — one card per seed, strongest
 * badge, earliest slot — pinned without a config, a bank or a server.
 */
class SeedRosterTest {

    private static Set<Long> shortlist(long... seeds) {
        Set<Long> out = new LinkedHashSet<>();
        for (long s : seeds) {
            out.add(s);
        }
        return out;
    }

    private static List<Long> ranked(long... seeds) {
        List<Long> out = new java.util.ArrayList<>();
        for (long s : seeds) {
            out.add(s);
        }
        return out;
    }

    @Test
    @DisplayName("current, starting and best all the same seed collapse to one starred card")
    void oneCardWhenAllThreeCoincide() {
        List<SeedRoster.Slot> roster =
                SeedRoster.of(7L, 7L, ranked(7L, 1L, 2L, 3L, 4L, 5L, 6L), Set.of(), 5);

        assertEquals(6, roster.size(), "one named card plus five ranked");
        assertEquals(new SeedRoster.Slot(7L, SeedRoster.Role.BEST), roster.get(0));
        // The five others are the ranking with the named seed taken out.
        assertEquals(ranked(1L, 2L, 3L, 4L, 5L),
                roster.subList(1, 6).stream().map(SeedRoster.Slot::seed).toList());
    }

    @Test
    @DisplayName("current and starting the same but not best gives a pin card and a star card")
    void pinAndStarWhenBestDiffers() {
        List<SeedRoster.Slot> roster =
                SeedRoster.of(9L, 9L, ranked(1L, 2L, 3L, 4L, 5L, 6L), Set.of(), 5);

        assertEquals(7, roster.size(), "two named cards plus five ranked");
        assertEquals(new SeedRoster.Slot(9L, SeedRoster.Role.CURRENT), roster.get(0));
        assertEquals(new SeedRoster.Slot(1L, SeedRoster.Role.BEST), roster.get(1));
        assertEquals(ranked(2L, 3L, 4L, 5L, 6L),
                roster.subList(2, 7).stream().map(SeedRoster.Slot::seed).toList());
    }

    @Test
    @DisplayName("a current seed differing from the starting one shows both")
    void currentAndStartingBothShownWhenTheyDiffer() {
        List<SeedRoster.Slot> roster = SeedRoster.of(50L, 60L, ranked(1L, 2L), Set.of(), 5);

        assertEquals(new SeedRoster.Slot(50L, SeedRoster.Role.CURRENT), roster.get(0));
        assertEquals(new SeedRoster.Slot(60L, SeedRoster.Role.STARTING), roster.get(1));
        assertEquals(new SeedRoster.Slot(1L, SeedRoster.Role.BEST), roster.get(2));
        assertEquals(new SeedRoster.Slot(2L, SeedRoster.Role.OTHER), roster.get(3));
    }

    @Test
    @DisplayName("shortlisted seeds are kept and do not use up a ranked place")
    void shortlistDoesNotConsumeRankedPlaces() {
        List<SeedRoster.Slot> roster = SeedRoster.of(null, null,
                ranked(1L, 2L, 3L, 4L, 5L, 6L, 7L), shortlist(90L, 91L), 5);

        // best + two shortlisted + five ranked
        assertEquals(8, roster.size());
        assertEquals(SeedRoster.Role.BEST, roster.get(0).role());
        assertEquals(SeedRoster.Role.SHORTLISTED, roster.get(1).role());
        assertEquals(90L, roster.get(1).seed());
        assertEquals(SeedRoster.Role.SHORTLISTED, roster.get(2).role());
        assertEquals(91L, roster.get(2).seed());
        assertEquals(ranked(2L, 3L, 4L, 5L, 6L),
                roster.subList(3, 8).stream().map(SeedRoster.Slot::seed).toList());
    }

    @Test
    @DisplayName("a shortlisted seed that is also current wears the pin, and is listed once")
    void shortlistedCurrentWearsTheStrongerBadge() {
        List<SeedRoster.Slot> roster =
                SeedRoster.of(42L, null, ranked(1L), shortlist(42L), 5);

        assertEquals(new SeedRoster.Slot(42L, SeedRoster.Role.CURRENT), roster.get(0));
        assertEquals(new SeedRoster.Slot(1L, SeedRoster.Role.BEST), roster.get(1));
        assertEquals(2, roster.size());
    }

    @Test
    @DisplayName("a seed shortlisted out of a stale bank still gets a card")
    void shortlistedSeedSurvivesAnEmptyBank() {
        List<SeedRoster.Slot> roster =
                SeedRoster.of(null, null, List.of(), shortlist(123L), 5);

        assertEquals(1, roster.size());
        assertEquals(new SeedRoster.Slot(123L, SeedRoster.Role.SHORTLISTED), roster.get(0));
    }

    @Test
    @DisplayName("a configured seed nothing ever rolled is still shown, and still drawn")
    void configuredSeedShownWithNoBank() {
        List<SeedRoster.Slot> roster = SeedRoster.of(5L, 5L, List.of(), Set.of(), 5);

        assertEquals(1, roster.size());
        assertEquals(new SeedRoster.Slot(5L, SeedRoster.Role.CURRENT), roster.get(0));
        assertTrue(roster.get(0).role().pinned(), "a named seed is always drawn");
        assertEquals(Set.of(5L), SeedRoster.seeds(roster));
    }

    @Test
    @DisplayName("nothing configured and nothing banked is an empty roster, not a null card")
    void emptyEverything() {
        assertEquals(List.of(), SeedRoster.of(null, null, List.of(), Set.of(), 5));
    }

    @Test
    @DisplayName("only OTHER is uncapped-exempt: named roles always count as pinned")
    void pinnedCoversEveryNamedRole() {
        assertTrue(SeedRoster.Role.CURRENT.pinned());
        assertTrue(SeedRoster.Role.STARTING.pinned());
        assertTrue(SeedRoster.Role.BEST.pinned());
        assertTrue(SeedRoster.Role.SHORTLISTED.pinned());
        assertEquals(false, SeedRoster.Role.OTHER.pinned());
    }

    @Test
    @DisplayName("the ranked cap is honoured exactly")
    void rankedCapHonoured() {
        List<SeedRoster.Slot> roster = SeedRoster.of(null, null,
                ranked(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L), Set.of(), 5);

        // best plus five others
        assertEquals(6, roster.size());
        assertEquals(SeedRoster.OTHERS, roster.stream()
                .filter(s -> s.role() == SeedRoster.Role.OTHER).count());
    }
}
