package com.customdimensions.server.portal;

import com.customdimensions.core.Id;
import com.customdimensions.core.config.DimensionConfig;
import com.customdimensions.core.config.MultiverseConfig;
import com.customdimensions.core.config.PortalDefinition;
import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.portal.Aura;
import com.customdimensions.core.portal.AuraPolicy;
import com.customdimensions.core.portal.AuraState;
import com.customdimensions.core.portal.PortalShape;
import com.customdimensions.server.CustomDimensions;
import com.customdimensions.server.level.LevelIndex;
import com.customdimensions.server.level.LevelManager;
import com.google.gson.JsonElement;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Portal auras on the server ({@link Aura} holds the rules). A link samples
 * both ends once: the source zone takes on the far side's nature, and an
 * aura site at the far end takes on the source's. Passes run from the
 * level's own tick, so a lazy level that is not ticking runs none and an aura
 * never holds a level open.
 * <p>
 * The policy is resolved live from the dimension whose nature is leaking (the
 * link's target, at both ends); the snapshot in the record is used only when
 * that dimension is no longer configured.
 */
public final class PortalAuras {
    private PortalAuras() {
    }

    /**
     * Records both ends' palettes the first time a source links to an arrival.
     * An anchor arrival shared by many sources keeps the first sample.
     */
    static void onLink(ServerLevel source, PortalZone zone, ServerLevel target, Set<BlockPos> arrivalInterior) {
        PortalDefinition def = zone.definition;
        if (def == null || !def.isAuraEnabled() || arrivalInterior.isEmpty()) {
            return;
        }
        PortalDefinition.AuraSettings settings = def.getAura();
        PortalRegistry registry = PortalRegistry.get();
        Set<com.customdimensions.core.BlockPos> arrival = core(arrivalInterior);
        boolean changed = false;
        if (zone.aura == null && settings.affectsSource()) {
            zone.aura = settings.palette != null ? AuraState.explicit(settings)
                    : Aura.sample(new LevelAuraWorld(target), PortalShape.centreOf(arrival), Aura.exclusion(arrival),
                            Aura.frameMaterials(def), fallbackBlock(target.dimension()));
            changed = true;
        }
        BlockPos key = Pos.mc(Aura.minCorner(arrival));
        if (registry.auraSite(target.dimension(), key) == null && settings.affectsTarget()) {
            PortalDefinition.AuraSettings emission = emissionOverrideFor(source.dimension());
            Set<com.customdimensions.core.BlockPos> near = zone.coreInterior();
            AuraState state = emission != null ? AuraState.explicit(emission)
                    : Aura.sample(new LevelAuraWorld(source), PortalShape.centreOf(near), Aura.exclusion(near),
                            Aura.frameMaterials(def), fallbackBlock(source.dimension()));
            registry.putAuraSite(target.dimension(), new PortalRegistry.AuraSite(arrivalInterior, state, settings));
            changed = true;
        }
        if (changed) {
            registry.save();
        }
    }

    static void tick(ServerLevel level) {
        long time = level.getGameTime();
        PortalRegistry registry = PortalRegistry.get();
        boolean save = Aura.saveDue(time);
        boolean dirty = false;
        for (PortalZone zone : registry.zones(level.dimension())) {
            PortalDefinition def = zone.definition;
            if (zone.arrival || zone.aura == null || def == null || !def.isAuraEnabled() || !registry.contains(zone)) {
                continue;
            }
            PortalDefinition.AuraSettings settings = def.getAura();
            if (!settings.affectsSource() || !Aura.due(time, settings)) {
                continue;
            }
            if (runSource(level, zone, settings, random(zone.interior, time)) > 0) {
                dirty = true;
                save |= Aura.exhausted(settings.getBudget(), zone.aura.spent);
            }
        }
        for (Map.Entry<BlockPos, PortalRegistry.AuraSite> entry : registry.auraSites(level.dimension()).entrySet()) {
            PortalRegistry.AuraSite site = entry.getValue();
            PortalDefinition.AuraSettings settings = site.settings() != null ? site.settings()
                    : PortalDefinition.AuraSettings.DEFAULTS;
            if (!settings.affectsTarget() || !Aura.due(time, settings)
                    || registry.zoneAt(level.dimension(), entry.getKey()) == null) {
                continue;
            }
            if (runSite(level, site, settings, random(site.interior(), time)) > 0) {
                dirty = true;
                save |= Aura.exhausted(settings.getBudget(), site.state().spent);
            }
        }
        if (dirty && save) {
            registry.save();
        }
    }

    /** One pass at a source zone within its lifetime budget; returns the units spent. */
    static int runSource(ServerLevel level, PortalZone zone, PortalDefinition.AuraSettings settings, Random random) {
        return run(level, zone.coreInterior(), zone.aura, settings, subsumeFor(zone.target, settings), random);
    }

    /** One pass at an aura site within its lifetime budget; returns the units spent. */
    static int runSite(ServerLevel level, PortalRegistry.AuraSite site, PortalDefinition.AuraSettings settings,
                       Random random) {
        return run(level, core(site.interior()), site.state(), settings, subsumeFor(level.dimension(), settings), random);
    }

    private static int run(ServerLevel level, Set<com.customdimensions.core.BlockPos> interior, AuraState state,
                           PortalDefinition.AuraSettings settings, String subsume, Random random) {
        int budget = settings.getBudget();
        if (Aura.exhausted(budget, state.spent)) {
            return 0;
        }
        com.customdimensions.core.BlockPos centre = PortalShape.centreOf(interior);
        Aura.Pass pass = Aura.pass(new LevelAuraWorld(level), centre, Aura.exclusion(interior), state, settings,
                subsume, Aura.remaining(budget, state.spent), random);
        state.spent += pass.spent();
        if (pass.claimed() > 0) {
            CustomDimensions.LOG.debug("aura: pass near {} in {} spent {}, {} of {} candidates vetoed by a claim",
                    centre, level.dimension().identifier(), pass.spent(), pass.claimed(), settings.getBlocksPerPass());
        }
        return pass.spent();
    }

    /** The live {@code subsume} of the dimension whose nature leaks, else the record's snapshot. */
    static String subsumeFor(ResourceKey<Level> dimension, PortalDefinition.AuraSettings snapshot) {
        String live = MultiverseConfig.getInstance().getAuraSubsumeFor(Id.parse(dimension.identifier().toString()));
        if (live != null) {
            return live;
        }
        return snapshot != null ? snapshot.getSubsume() : AuraPolicy.NATURAL;
    }

    /** The aura settings of the dimension {@code world} belongs to when they name an emission palette, else null. */
    static PortalDefinition.AuraSettings emissionOverrideFor(ResourceKey<Level> world) {
        DimensionConfig config = PortalCatalogue.dimension(world);
        if (config == null || config.getPortal() == null) {
            return null;
        }
        PortalDefinition def = config.toPortalDefinition();
        return def != null && def.getAura().palette != null ? def.getAura() : null;
    }

    /** {@code materials.defaultBlock} of the definition behind {@code world}, or null. */
    static String fallbackBlock(ResourceKey<Level> world) {
        Definition definition = LevelManager.get().map(m -> definitionOf(m.index(), world)).orElse(null);
        if (definition == null || definition.materials() == null) {
            return null;
        }
        JsonElement block = definition.materials().get("defaultBlock");
        return block != null && block.isJsonPrimitive() ? block.getAsString() : null;
    }

    private static Definition definitionOf(LevelIndex index, ResourceKey<Level> world) {
        for (LevelIndex.BaseWorld base : index.baseWorlds()) {
            if (base.key().equals(world)) {
                return base.definition();
            }
        }
        for (LevelIndex.Entry entry : index.lazy().values()) {
            if (entry.key().equals(world)) {
                return entry.definition();
            }
        }
        return null;
    }

    private static Random random(Set<BlockPos> interior, long time) {
        return new Random(Pos.mc(Aura.minCorner(core(interior))).asLong() ^ time * 0x9E3779B97F4A7C15L);
    }

    static Set<com.customdimensions.core.BlockPos> core(Set<BlockPos> cells) {
        Set<com.customdimensions.core.BlockPos> out = new java.util.LinkedHashSet<>();
        cells.forEach(c -> out.add(Pos.core(c)));
        return out;
    }
}
