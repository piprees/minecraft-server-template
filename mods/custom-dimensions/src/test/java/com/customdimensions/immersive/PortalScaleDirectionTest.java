package com.customdimensions.immersive;

import com.customdimensions.config.MultiverseConfig;
import com.customdimensions.config.PortalDefinition;
import com.customdimensions.portal.PortalHelper;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scale describes the DIMENSION, not the portal, so the transform between two
 * worlds is vanilla's own rule: {@code pos * sourceScale / targetScale}.
 * Entering a scale-8 Nether divides by 8; leaving it multiplies by 8. Applied
 * in a fixed direction it is right on one side and wrong on the other, and at
 * scale 1 the two are indistinguishable.
 *
 * <p>The geometry is one measured pair of linked vanilla portals, probed
 * block by block: overworld interior x-3855, z3260-3262, y-22..-17 (18 cells)
 * and the Nether portal vanilla linked it to at x-493, z401-402, y73-75 (6
 * cells). Both are Z-axis frames. The truncated interior columns are
 * (-3855, 3261) and (-493, 401), which is what the live projector logs.
 */
class PortalScaleDirectionTest {

    private static final RegistryKey<World> OVERWORLD =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
    private static final RegistryKey<World> THE_NETHER =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_nether"));

    /** A Z-axis interior: x fixed, z running `wide` cells, y running `tall`. */
    private static Set<BlockPos> interior(int x, int y, int z, int wide, int tall) {
        Set<BlockPos> out = new HashSet<>();
        for (int dz = 0; dz < wide; dz++) {
            for (int dy = 0; dy < tall; dy++) {
                out.add(new BlockPos(x, y + dy, z + dz));
            }
        }
        return out;
    }

    /** The measured overworld frame: 3 wide, 6 tall, column (-3855, 3261). */
    private static Set<BlockPos> overworldPortal() {
        return interior(-3855, -22, 3260, 3, 6);
    }

    /** The measured Nether frame it links to: 2 wide, 3 tall, column (-493, 401). */
    private static Set<BlockPos> netherPortal() {
        return interior(-493, 73, 401, 2, 3);
    }

    private static PortalDefinition netherDefinition() {
        PortalDefinition def = new PortalDefinition("the_nether", "minecraft:obsidian",
                "minecraft:flint_and_steel", "minecraft:the_nether", "#8844FF", 0);
        def.setScale(8.0);
        return def;
    }

    private static PortalHelper.PortalZone zone(Set<BlockPos> interior, PortalDefinition def,
            RegistryKey<World> source, RegistryKey<World> target) {
        return new PortalHelper.PortalZone(interior, def, Direction.Axis.Z, source, target);
    }

    // === the two directions =============================================

    @Test
    void enteringAScaledDimensionDivides() {
        // Source scale 1, target scale 8: -3855/8 and 3261/8.
        PortalDefinition def = netherDefinition();
        ProjectionVolume.TargetMapping mapping = ImmersiveProjector.mappingFor(
                zone(overworldPortal(), def, OVERWORLD, THE_NETHER), def);

        assertEquals(-482, mapping.arrivalX());
        assertEquals(408, mapping.arrivalZ());
    }

    @Test
    void leavingAScaledDimensionMultiplies() {
        // The Nether side of the same pair. PortalZone normalises a target
        // equal to its source to the overworld, so this zone runs
        // the_nether's own definition backwards: source scale 8, target 1.
        PortalDefinition def = netherDefinition();
        PortalHelper.PortalZone zone = zone(netherPortal(), def, THE_NETHER, THE_NETHER);
        assertEquals(World.OVERWORLD, zone.targetWorld, "the way back, not a loop");

        ProjectionVolume.TargetMapping mapping = ImmersiveProjector.mappingFor(zone, def);

        // -493*8 and 401*8. Dividing instead searches from (-62, 50) — some
        // 3,800 blocks from the portal being previewed, and vanilla's search
        // radius is 128.
        assertEquals(-3944, mapping.arrivalX());
        assertEquals(3208, mapping.arrivalZ());
    }

    @Test
    void theTwoDirectionsAreInverses() {
        PortalDefinition def = netherDefinition();
        ProjectionVolume.TargetMapping out = ImmersiveProjector.mappingFor(
                zone(overworldPortal(), def, OVERWORLD, THE_NETHER), def);
        ProjectionVolume.TargetMapping back = ImmersiveProjector.mappingFor(
                zone(interior(out.arrivalX(), 73, out.arrivalZ(), 2, 3), def,
                        THE_NETHER, THE_NETHER), def);

        // Rounding at the scaled column costs up to `scale` blocks on the way
        // back, the same loss vanilla takes. Anything larger is a direction
        // applied twice.
        assertTrue(Math.abs(back.arrivalX() - (-3855)) <= 8,
                "returned to x" + back.arrivalX() + ", not the column it left");
        assertTrue(Math.abs(back.arrivalZ() - 3261) <= 8,
                "returned to z" + back.arrivalZ() + ", not the column it left");
    }

    // === why it survived ================================================

    @Test
    void scaleOneIsIdenticalBothWays() {
        // A scale-1 dimension transforms to itself in either direction, so a
        // fixed-direction scale is invisible on every dimension but a scaled
        // one.
        PortalDefinition def = new PortalDefinition("the_lost_outpost", "minecraft:cobblestone",
                "minecraft:torch", "adventure:the_lost_outpost", "#8844FF", 0);
        def.setScale(1.0);
        RegistryKey<World> outpost =
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("adventure", "the_lost_outpost"));

        ProjectionVolume.TargetMapping out = ImmersiveProjector.mappingFor(
                zone(overworldPortal(), def, OVERWORLD, outpost), def);
        ProjectionVolume.TargetMapping back = ImmersiveProjector.mappingFor(
                zone(overworldPortal(), def, outpost, outpost), def);

        assertEquals(-3855, out.arrivalX());
        assertEquals(3261, out.arrivalZ());
        assertEquals(0, out.dx());
        assertEquals(0, out.dz());
        assertEquals(out.arrivalX(), back.arrivalX());
        assertEquals(out.arrivalZ(), back.arrivalZ());
    }

    // === the branches that must not be scaled ============================

    @Test
    void anAnchorWinsInEitherDirection() {
        PortalDefinition def = netherDefinition();
        def.setAnchorPos(new int[] {-1000, 70, 3000});

        ProjectionVolume.TargetMapping out = ImmersiveProjector.mappingFor(
                zone(overworldPortal(), def, OVERWORLD, THE_NETHER), def);
        ProjectionVolume.TargetMapping back = ImmersiveProjector.mappingFor(
                zone(netherPortal(), def, THE_NETHER, THE_NETHER), def);

        assertEquals(-1000, out.arrivalX());
        assertEquals(3000, out.arrivalZ());
        assertEquals(-1000, back.arrivalX());
        assertEquals(3000, back.arrivalZ());
    }

    @Test
    void aMalformedTargetDimensionFallsBackToTheConfig() {
        // getTargetKey parses the raw string, so a bad one throws. The
        // transform must survive it — an unscaled preview beats none.
        PortalDefinition def = netherDefinition();
        def.setTargetDimension("not a dimension!!");

        ProjectionVolume.TargetMapping mapping = assertDoesNotThrow(() ->
                ImmersiveProjector.mappingFor(
                        zone(overworldPortal(), def, OVERWORLD, THE_NETHER), def));
        assertEquals(-3855, mapping.arrivalX());
        assertEquals(3261, mapping.arrivalZ());
    }

    // === the search centre the preloader tickets ==========================

    @Test
    void thePreloadedChunkIsTheOneTheArrivalNeeds() {
        // ImmersivePreloader tickets `mapping.arrivalX() >> 4, arrivalZ() >> 4`
        // from this mapping, and the projector hands the same column to
        // VanillaLinkResolver as its search centre. A second copy of the
        // arithmetic ticketed the multiplied column from the overworld side,
        // some 30km away.
        PortalDefinition def = netherDefinition();
        ProjectionVolume.TargetMapping out = ImmersiveProjector.mappingFor(
                zone(overworldPortal(), def, OVERWORLD, THE_NETHER), def);
        ProjectionVolume.TargetMapping back = ImmersiveProjector.mappingFor(
                zone(netherPortal(), def, THE_NETHER, THE_NETHER), def);

        assertEquals(-31, out.arrivalX() >> 4);
        assertEquals(25, out.arrivalZ() >> 4);
        assertEquals(-247, back.arrivalX() >> 4);
        assertEquals(200, back.arrivalZ() >> 4);
    }

    // === the arithmetic underneath ========================================

    @Test
    void theTwoArgumentFormIsTheSourceScaleOneCase() {
        Set<BlockPos> interior = overworldPortal();
        assertEquals(ProjectionVolume.scaledMapping(interior, 8.0),
                ProjectionVolume.scaledMapping(interior, 1.0, 8.0));
    }

    @Test
    void equalScalesAreATranslationFreeMap() {
        // Two scale-8 dimensions are 1:1 with each other, not 64:1.
        ProjectionVolume.TargetMapping mapping =
                ProjectionVolume.scaledMapping(netherPortal(), 8.0, 8.0);
        assertEquals(0, mapping.dx());
        assertEquals(0, mapping.dz());
        assertEquals(-493, mapping.arrivalX());
        assertEquals(401, mapping.arrivalZ());
    }

    @Test
    void anUnconfiguredWorldScalesAtOne() {
        assertEquals(1.0, MultiverseConfig.getInstance().getScaleFor(null));
        assertEquals(1.0, MultiverseConfig.getInstance().getScaleFor(OVERWORLD));
        assertEquals(1.0, MultiverseConfig.getInstance().getScaleFor(
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("nosuchmod", "nowhere"))));
    }
}
