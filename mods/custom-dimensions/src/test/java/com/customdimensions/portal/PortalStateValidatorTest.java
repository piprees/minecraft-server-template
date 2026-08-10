package com.customdimensions.portal;

import com.customdimensions.config.PortalDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each invariant a persisted portal_links.json record must hold, exercised
 * with a hand-built record: one valid, and one violation per rule.
 */
class PortalStateValidatorTest {

    private static PortalHelper.StoredPortalZone zone(
            String sourceWorld, String targetWorld, String frameBlock, boolean singleUse, Integer singleUseTicksLeft) {
        PortalHelper.StoredPortalZone zone = new PortalHelper.StoredPortalZone();
        zone.sourceWorld = sourceWorld;
        zone.targetWorld = targetWorld;
        PortalDefinition definition = new PortalDefinition(
                "test", frameBlock, "minecraft:flint_and_steel", targetWorld, "8844FF", 11);
        definition.setSingleUse(singleUse);
        zone.definition = definition;
        zone.singleUseTicksLeft = singleUseTicksLeft;
        return zone;
    }

    @Test
    void validZonePassesEveryRule() {
        assertEquals(List.of(), PortalStateValidator.validateZone(
                zone("minecraft:overworld", "adventure:the_glasswood", "minecraft:amethyst_block", false, null)));
    }

    @Test
    void sourceWorldMustBeNamespaced() {
        List<String> failures = PortalStateValidator.validateZone(
                zone("overworld", "adventure:the_glasswood", "minecraft:amethyst_block", false, null));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("sourceWorld"));
    }

    @Test
    void targetWorldMustBeNamespaced() {
        List<String> failures = PortalStateValidator.validateZone(
                zone("minecraft:overworld", "the_glasswood", "minecraft:amethyst_block", false, null));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("targetWorld"));
    }

    @Test
    void frameBlockMustNeverBeATagForm() {
        List<String> failures = PortalStateValidator.validateZone(
                zone("minecraft:overworld", "adventure:the_glasswood", "#minecraft:logs", false, null));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("frameBlock"));
    }

    @Test
    void frameBlockMustBePresent() {
        List<String> failures = PortalStateValidator.validateZone(
                zone("minecraft:overworld", "adventure:the_glasswood", null, false, null));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("frameBlock"));
    }

    @Test
    void singleUseTicksLeftArmedWithoutSingleUseIsAFailure() {
        List<String> failures = PortalStateValidator.validateZone(
                zone("minecraft:overworld", "adventure:the_glasswood", "minecraft:amethyst_block", false, 200));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("singleUseTicksLeft"));
    }

    @Test
    void singleUseTicksLeftUnarmedIsFineWithoutSingleUse() {
        assertEquals(List.of(), PortalStateValidator.validateZone(
                zone("minecraft:overworld", "adventure:the_glasswood", "minecraft:amethyst_block", false, -1)));
    }

    @Test
    void singleUseTicksLeftArmedWithSingleUseIsFine() {
        assertEquals(List.of(), PortalStateValidator.validateZone(
                zone("minecraft:overworld", "adventure:the_glasswood", "minecraft:amethyst_block", true, 200)));
    }

    // --- orphan zones ---------------------------------------------------

    @Test
    void aZoneNamingAnUnconfiguredDimensionIsAnOrphan() {
        PortalHelper.StoredPortalZone zone = zone(
                "minecraft:overworld", "adventure:the_ghost", "minecraft:amethyst_block", false, null);
        assertTrue(PortalStateValidator.isOrphanZone(zone, Set.of("minecraft:overworld")));
    }

    @Test
    void aZoneWithBothEndsConfiguredIsNotAnOrphan() {
        PortalHelper.StoredPortalZone zone = zone(
                "minecraft:overworld", "adventure:the_glasswood", "minecraft:amethyst_block", false, null);
        assertFalse(PortalStateValidator.isOrphanZone(
                zone, Set.of("minecraft:overworld", "adventure:the_glasswood")));
    }

    // --- aura sites -------------------------------------------------------

    private static PortalHelper.AuraSite auraSite(String world, int budgetSpent, java.util.List<PortalHelper.StoredPosition> interior) {
        PortalHelper.AuraSite site = new PortalHelper.AuraSite();
        site.world = world;
        site.budgetSpent = budgetSpent;
        site.interior = interior;
        return site;
    }

    @Test
    void validAuraSitePassesEveryRule() {
        assertEquals(List.of(), PortalStateValidator.validateAuraSite(
                auraSite("adventure:the_glasswood", 12, List.of(new PortalHelper.StoredPosition(
                        new net.minecraft.util.math.BlockPos(0, 64, 0))))));
    }

    @Test
    void auraSiteBudgetSpentMustBeNonNegative() {
        List<String> failures = PortalStateValidator.validateAuraSite(
                auraSite("adventure:the_glasswood", -1, List.of(new PortalHelper.StoredPosition(
                        new net.minecraft.util.math.BlockPos(0, 64, 0)))));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("budgetSpent"));
    }

    @Test
    void auraSiteInteriorMustBeNonEmpty() {
        List<String> failures = PortalStateValidator.validateAuraSite(
                auraSite("adventure:the_glasswood", 0, List.of()));
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("interior"));
    }

    // --- legacy return targets --------------------------------------------

    @Test
    void validLegacyTargetPassesEveryRule() {
        assertEquals(List.of(), PortalStateValidator.validateLegacyTarget(
                "minecraft:overworld", "adventure:the_glasswood", "origin"));
    }

    @Test
    void legacyTargetWorldMustBeNamespaced() {
        List<String> failures = PortalStateValidator.validateLegacyTarget("overworld", null, null);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("targetWorld"));
    }

    @Test
    void legacyPortalWorldMustBeNamespacedWhenPresent() {
        List<String> failures = PortalStateValidator.validateLegacyTarget(
                "minecraft:overworld", "the_glasswood", null);
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("portalWorld"));
    }

    @Test
    void legacyExitModeMustBeKnownWhenPresent() {
        List<String> failures = PortalStateValidator.validateLegacyTarget(
                "minecraft:overworld", null, "somewhereElse");
        assertEquals(1, failures.size());
        assertTrue(failures.get(0).contains("exitMode"));
    }
}
