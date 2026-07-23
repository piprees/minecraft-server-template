package com.customdimensions.config;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DimensionConfigTest {
    private static final Gson GSON = new Gson();

    private DimensionConfig parse(String slug, String json) {
        DimensionConfig config = GSON.fromJson(json, DimensionConfig.class);
        config.setName(slug);
        return config;
    }

    @Test
    void emptyFileYieldsSaneDefaults() {
        DimensionConfig config = parse("the_test", "{}");
        assertEquals("the_test", config.getName());
        assertNull(config.getType());
        assertNull(config.getSeed());
        assertNull(config.getSpawn());
        assertNull(config.getBiome());
        assertNull(config.getNoiseSettings());
        assertNull(config.getStructureDensity());
        assertTrue(config.isHostileSpawningEnabled());
        assertFalse(config.hasPortal());
        assertEquals(8192, config.getPlayerBorderRadius());
        assertEquals(8192, config.getGenerationBorderRadius());
        assertEquals(9192, config.getLocateCap());
        assertEquals("adventure:the_test", config.getDimensionId());
    }

    @Test
    void numericSeedDeserialisesAsExactLong() {
        DimensionConfig config = parse("d", "{\"seed\": -4254781042587868201}");
        assertEquals(-4254781042587868201L, config.getEffectiveSeed(null));
    }

    @Test
    void envSeedSentinelReadsEnvironmentValue() {
        DimensionConfig config = parse("overworld", "{\"seed\": \"env\"}");
        assertEquals(123456789L, config.getEffectiveSeed("123456789"));
        assertNull(config.getEffectiveSeed(null));
        assertNull(config.getEffectiveSeed(""));
        // Non-numeric env seeds hash exactly like vanilla.
        assertEquals((long) "glacier".hashCode(), config.getEffectiveSeed("glacier"));
    }

    @Test
    void numericStringSeedParses() {
        DimensionConfig config = parse("d", "{\"seed\": \"42\"}");
        assertEquals(42L, config.getEffectiveSeed(null));
    }

    @Test
    void baseWorldsKeepVanillaIds() {
        assertEquals("minecraft:overworld", parse("overworld", "{}").getDimensionId());
        assertEquals("minecraft:the_nether", parse("the_nether", "{}").getDimensionId());
        assertEquals("minecraft:the_end", parse("the_end", "{}").getDimensionId());
        assertEquals("paradise_lost:paradise_lost", parse("paradise_lost", "{}").getDimensionId());
        assertTrue(parse("overworld", "{}").isBaseWorld());
        assertFalse(parse("the_claymarsh", "{}").isBaseWorld());
    }

    @Test
    void dimensionIdUsesGivenNamespace() {
        DimensionConfig config = parse("the_claymarsh", "{}");
        assertEquals("mybrand:the_claymarsh", config.getDimensionId("mybrand"));
        config.setNamespace("elfydd");
        assertEquals("elfydd:the_claymarsh", config.getDimensionId());
    }

    @Test
    void legacyExplicitDimensionIdWins() {
        DimensionConfig config = parse("the_claymarsh", "{\"dimensionId\":\"Adventure:The_Claymarsh\"}");
        assertEquals("adventure:the_claymarsh", config.getDimensionId());
    }

    @Test
    void biomesArrayJoinsToCommaString() {
        DimensionConfig config = parse("d",
                "{\"biomes\":[\"minecraft:swamp\",\"natures_spirit:marsh\"]}");
        assertEquals("minecraft:swamp,natures_spirit:marsh", config.getBiome());
    }

    @Test
    void legacyBiomeStringPassesThrough() {
        DimensionConfig config = parse("d", "{\"biome\":\"minecraft:swamp,minecraft:plains\"}");
        assertEquals("minecraft:swamp,minecraft:plains", config.getBiome());
    }

    @Test
    void hostileSpawningResolutionOrder() {
        assertFalse(parse("d", "{\"difficulty\":{\"hostileSpawning\":false}}").isHostileSpawningEnabled());
        assertFalse(parse("d", "{\"hostileSpawning\":false}").isHostileSpawningEnabled());
        // difficulty block wins over the legacy flag
        assertTrue(parse("d", "{\"hostileSpawning\":false,\"difficulty\":{\"hostileSpawning\":true}}")
                .isHostileSpawningEnabled());
    }

    @Test
    void locateCapPrefersExplicitSeedRollValue() {
        DimensionConfig config = parse("d",
                "{\"borders\":{\"generation\":2048},\"seedRoll\":{\"locateCap\":5000}}");
        assertEquals(5000, config.getLocateCap());
        DimensionConfig derived = parse("d", "{\"borders\":{\"generation\":2048}}");
        assertEquals(3048, derived.getLocateCap());
    }

    @Test
    void spawnRequiresThreeComponents() {
        assertNull(parse("d", "{\"spawn\":[1,2]}").getSpawn());
        assertArrayEquals(new int[]{128, 64, -45}, parse("d", "{\"spawn\":[128,64,-45]}").getSpawn());
    }

    @Test
    void portalViewCarriesAllFields() {
        DimensionConfig config = parse("the_claymarsh", """
                {"portal":{"frameBlock":"minecraft:clay","igniterItem":"minecraft:amethyst_shard",
                 "color":"9B8B7A","lightLevel":11,"scale":8.0,"cooldown":40,
                 "sounds":{"ignite":"block.portal.trigger","enter":"a.b","exit":"c.d"}}}
                """);
        assertTrue(config.hasPortal());
        PortalDefinition def = config.toPortalDefinition();
        assertEquals("the_claymarsh", def.getId());
        assertEquals("minecraft:clay", def.getFrameBlock());
        assertEquals("minecraft:amethyst_shard", def.getIgniterItem());
        assertEquals("adventure:the_claymarsh", def.getTargetDimension());
        assertEquals("9B8B7A", def.getColor());
        assertEquals(11, def.getLightLevel());
        assertEquals(8.0, def.getScale());
        assertEquals(40, def.getCooldown());
        assertEquals("a.b", def.getEnterSound());
        assertEquals("c.d", def.getExitSound());
    }

    @Test
    void legacyFlatPortalSoundsStillResolve() {
        DimensionConfig config = parse("d", """
                {"portal":{"frameBlock":"minecraft:clay","enterSound":"x.y"}}
                """);
        PortalDefinition def = config.toPortalDefinition();
        assertEquals("x.y", def.getEnterSound());
        assertEquals("block.portal.trigger", def.getIgniteSound());
        assertEquals(1.0, def.getScale());
        assertEquals(40, def.getCooldown());
    }

    @Test
    void portalScaleFeedsScaleGetterForCustomDims() {
        DimensionConfig config = parse("d", "{\"portal\":{\"frameBlock\":\"b\",\"scale\":8.0}}");
        assertEquals(8.0, config.getScale());
        DimensionConfig world = parse("the_nether", "{\"scale\":8.0}");
        assertEquals(8.0, world.getScale());
        assertEquals(1.0, parse("d", "{}").getScale());
    }

    @Test
    void seedRollBlockDeserialises() {
        DimensionConfig config = parse("d", """
                {"seedRoll":{"mood":"serene","spawnFilter":["minecraft:swamp"],"water":"high",
                 "wants":{"swamp_ruin":"spread"},"shuns":["village"],"description":"quiet"}}
                """);
        assertNotNull(config.getSeedRoll());
        assertEquals("serene", config.getSeedRoll().mood);
        assertEquals(1, config.getSeedRoll().spawnFilter.size());
        assertEquals("high", config.getSeedRoll().water);
        assertTrue(config.getSeedRoll().wants.has("swamp_ruin"));
        assertEquals("quiet", config.getSeedRoll().description);
    }

    @Test
    void environmentBlockDeserialises() {
        DimensionConfig config = parse("d", """
                {"environment":{"skyColor":"#7BA4FF","ambientLight":0.5,"fixedTime":18000,
                 "hasCeiling":false,"minY":-64,"height":384}}
                """);
        assertEquals("#7BA4FF", config.getEnvironment().skyColor);
        assertEquals(0.5, config.getEnvironment().ambientLight);
        assertEquals(18000L, config.getEnvironment().fixedTime);
        assertEquals(false, config.getEnvironment().hasCeiling);
        assertEquals(-64, config.getEnvironment().minY);
        assertEquals(384, config.getEnvironment().height);
    }

    @Test
    void difficultyBlockDeserialisesWithDefaults() {
        DimensionConfig config = parse("d", """
                {"difficulty":{"mobMultiplier":1.8,"playerLuck":0.8,
                 "attributes":{"health":true,"speed":false},
                 "depthScaling":{"enabled":true,"startY":64,"endY":-64,
                                 "minMultiplier":1.0,"maxMultiplier":1.5}}}
                """);
        assertEquals(1.8, config.getDifficulty().getMobMultiplier());
        assertEquals(0.8, config.getDifficulty().getPlayerLuck());
        assertEquals(true, config.getDifficulty().attributes.health);
        assertEquals(false, config.getDifficulty().attributes.speed);
        assertEquals(true, config.getDifficulty().depthScaling.enabled);
        // Defaults when the block is absent entirely.
        DimensionConfig bare = parse("d", "{\"difficulty\":{}}");
        assertEquals(1.0, bare.getDifficulty().getMobMultiplier());
        assertEquals(1.0, bare.getDifficulty().getPlayerLuck());
    }

    @Test
    void anchorBlockPlumbsIntoPortalDefinition() {
        DimensionConfig config = parse("the_starwell", """
                {"portal":{"frameBlock":"minecraft:crying_obsidian",
                  "anchor":{"pos":[12,70,-8],"exit":"bed"}}}
                """);
        PortalDefinition def = config.toPortalDefinition();
        assertTrue(def.hasAnchor());
        assertArrayEquals(new int[]{12, 70, -8}, def.getAnchorPos());
        assertEquals("bed", def.getAnchorExit());
    }

    @Test
    void anchorSpawnSentinelUsesDimensionSpawnThenBorderCentre() {
        DimensionConfig withSpawn = parse("d", """
                {"spawn":[100,64,200],
                 "portal":{"frameBlock":"b","anchor":{"pos":"spawn"}}}
                """);
        assertArrayEquals(new int[]{100, 64, 200}, withSpawn.toPortalDefinition().getAnchorPos());
        DimensionConfig withoutSpawn = parse("d", """
                {"portal":{"frameBlock":"b","anchor":{}}}
                """);
        PortalDefinition def = withoutSpawn.toPortalDefinition();
        assertArrayEquals(new int[]{0, 64, 0}, def.getAnchorPos());
        assertEquals("origin", def.getAnchorExit());
    }

    @Test
    void noAnchorBlockMeansNoAnchor() {
        DimensionConfig config = parse("d", "{\"portal\":{\"frameBlock\":\"b\"}}");
        assertFalse(config.toPortalDefinition().hasAnchor());
    }

    @Test
    void singleUseBlockPlumbsIntoPortalDefinition() {
        DimensionConfig config = parse("d", """
                {"portal":{"frameBlock":"b",
                  "singleUse":{"enabled":true,"delaySeconds":30,"breakMode":"partial",
                    "decayMap":{"minecraft:obsidian":"minecraft:blackstone"}}}}
                """);
        PortalDefinition def = config.toPortalDefinition();
        assertTrue(def.isSingleUse());
        assertEquals(600, def.getSingleUseDelayTicks());
        assertEquals("partial", def.getSingleUseBreakMode());
        assertEquals("minecraft:blackstone", def.getSingleUseDecayMap().get("minecraft:obsidian"));
    }

    @Test
    void singleUseDefaultsAndDisabledState() {
        DimensionConfig bare = parse("d", """
                {"portal":{"frameBlock":"b","singleUse":{"enabled":true}}}
                """);
        PortalDefinition def = bare.toPortalDefinition();
        assertEquals(200, def.getSingleUseDelayTicks());
        assertEquals("decay", def.getSingleUseBreakMode());
        DimensionConfig off = parse("d", """
                {"portal":{"frameBlock":"b","singleUse":{"enabled":false,"delaySeconds":30}}}
                """);
        assertFalse(off.toPortalDefinition().isSingleUse());
        assertFalse(parse("d", "{\"portal\":{\"frameBlock\":\"b\"}}").toPortalDefinition().isSingleUse());
    }

    @Test
    void exitPortalBlockDeserialisesWithDefaults() {
        DimensionConfig config = parse("d", """
                {"exitPortal":{"enabled":true,"pos":[5,64,5],"target":"worldSpawn"}}
                """);
        assertTrue(config.hasExitPortal());
        assertArrayEquals(new int[]{5, 64, 5}, config.getExitPortal().getExplicitPos());
        assertEquals("worldSpawn", config.getExitPortal().getTargetMode());
        DimensionConfig defaults = parse("d", "{\"exitPortal\":{\"enabled\":true}}");
        assertNull(defaults.getExitPortal().getExplicitPos());
        assertEquals("bed", defaults.getExitPortal().getTargetMode());
        assertFalse(parse("d", "{}").hasExitPortal());
        assertFalse(parse("d", "{\"exitPortal\":{\"enabled\":false}}").hasExitPortal());
    }

    @Test
    void checkerboardScaleDeserialises() {
        DimensionConfig config = parse("d",
                "{\"type\":\"checkerboard\",\"biomes\":[\"minecraft:plains\",\"minecraft:desert\"],\"checkerboardScale\":4}");
        assertEquals("checkerboard", config.getType());
        assertEquals(4, config.getCheckerboardScale());
        assertEquals("minecraft:plains,minecraft:desert", config.getBiome());
        // Unset stays null (use-site defaults to vanilla's 2).
        assertNull(parse("d", "{\"type\":\"checkerboard\"}").getCheckerboardScale());
    }

    @Test
    void superflatLayersAndFlatBiomeDeserialise() {
        DimensionConfig config = parse("d", """
                {"type":"superflat","flatBiome":"minecraft:desert",
                 "layers":[{"block":"minecraft:bedrock","height":1},
                           {"block":"minecraft:sandstone","height":10},
                           {"block":"minecraft:sand","height":3}]}
                """);
        assertEquals("minecraft:desert", config.getFlatBiome());
        assertEquals(3, config.getLayers().size());
        assertEquals("minecraft:sandstone", config.getLayers().get(1).block);
        assertEquals(10, config.getLayers().get(1).height);
        assertEquals("1*minecraft:bedrock,10*minecraft:sandstone,3*minecraft:sand",
                config.getLayersFingerprint());
        // Unset layers/flatBiome: null fingerprint, null getters.
        DimensionConfig bare = parse("d", "{\"type\":\"superflat\"}");
        assertNull(bare.getLayers());
        assertNull(bare.getFlatBiome());
        assertNull(bare.getLayersFingerprint());
    }

    @Test
    void seedRollSkipDeserialises() {
        DimensionConfig config = parse("d", "{\"seedRoll\":{\"skip\":true}}");
        assertEquals(true, config.getSeedRoll().skip);
        assertNull(parse("d", "{\"seedRoll\":{\"mood\":\"serene\"}}").getSeedRoll().skip);
    }

    @Test
    void settingsOverridesDeserialise() {
        DimensionConfig config = parse("d", """
                {"settingsOverrides":{"seaLevel":100,"defaultBlock":"minecraft:netherrack",
                 "defaultFluid":"minecraft:lava","disableMobGeneration":true}}
                """);
        assertEquals(100, config.getSettingsOverrides().seaLevel);
        assertEquals("minecraft:netherrack", config.getSettingsOverrides().defaultBlock);
        assertEquals("minecraft:lava", config.getSettingsOverrides().defaultFluid);
        assertEquals(true, config.getSettingsOverrides().disableMobGeneration);
        assertEquals("seaLevel=100,defaultBlock=minecraft:netherrack,defaultFluid=minecraft:lava,disableMobGeneration=true",
                config.getSettingsOverridesFingerprint());
        // Partial block fingerprints only the set fields, in fixed order.
        assertEquals("seaLevel=40",
                parse("d", "{\"settingsOverrides\":{\"seaLevel\":40}}").getSettingsOverridesFingerprint());
        // Absent block: null getter, null fingerprint; empty block: null fingerprint.
        assertNull(parse("d", "{}").getSettingsOverrides());
        assertNull(parse("d", "{}").getSettingsOverridesFingerprint());
        assertNull(parse("d", "{\"settingsOverrides\":{}}").getSettingsOverridesFingerprint());
    }

    @Test
    void structureSpacingOverridesDeserialise() {
        DimensionConfig config = parse("d", """
                {"structures":{"spacing":{"minecraft:villages":{"spacing":8,"separation":4},
                                          "dungeons_plus:cold_dungeon":{"spacing":12}}}}
                """);
        assertEquals(8, config.getStructures().spacing.get("minecraft:villages").spacing);
        assertEquals(4, config.getStructures().spacing.get("minecraft:villages").separation);
        assertEquals(12, config.getStructures().spacing.get("dungeons_plus:cold_dungeon").spacing);
        assertNull(config.getStructures().spacing.get("dungeons_plus:cold_dungeon").separation);
        // spacing coexists with wants/shuns (roller-only) untouched
        assertNull(config.getStructures().wants);
    }

    @Test
    void biomesObjectEntriesCarryParameters() {
        DimensionConfig config = parse("d", """
                {"biomes":["minecraft:plains",
                           {"id":"minecraft:cherry_grove",
                            "parameters":{"temperature":[-0.5,0.2],"continentalness":0.3,"offset":0.1}},
                           "minecraft:desert"]}
                """);
        // Both entry forms contribute ids, in order.
        assertEquals("minecraft:plains,minecraft:cherry_grove,minecraft:desert", config.getBiome());
        assertEquals(3, config.getBiomes().size());
        // Only object entries with a parameters object land in the map.
        assertEquals(1, config.getBiomeParameters().size());
        assertTrue(config.getBiomeParameters().containsKey("minecraft:cherry_grove"));
        assertEquals(0.3, config.getBiomeParameters().get("minecraft:cherry_grove")
                .get("continentalness").getAsDouble());
        assertNotNull(config.getBiomeParametersFingerprint());
        // Plain string arrays keep the old behaviour exactly.
        DimensionConfig plain = parse("d", "{\"biomes\":[\"minecraft:swamp\",\"natures_spirit:marsh\"]}");
        assertEquals("minecraft:swamp,natures_spirit:marsh", plain.getBiome());
        assertTrue(plain.getBiomeParameters().isEmpty());
        assertNull(plain.getBiomeParametersFingerprint());
    }

    @Test
    void biomePatchesDeserialise() {
        DimensionConfig config = parse("d", """
                {"biomePatches":[{"biome":"minecraft:cherry_grove","x":0,"z":0,"radius":96},
                                 {"biome":"terralith:moonlight_grove","x":1500,"z":-800,"radius":200}]}
                """);
        assertEquals(2, config.getBiomePatches().size());
        assertEquals("minecraft:cherry_grove", config.getBiomePatches().get(0).biome);
        assertEquals(96, config.getBiomePatches().get(0).radius);
        assertEquals(-800, config.getBiomePatches().get(1).z);
        assertEquals("minecraft:cherry_grove@0,0,96;terralith:moonlight_grove@1500,-800,200",
                config.getBiomePatchesFingerprint());
        assertNull(parse("d", "{}").getBiomePatches());
        assertNull(parse("d", "{}").getBiomePatchesFingerprint());
        // Swap mode: "replace" carries through and joins the fingerprint.
        DimensionConfig swap = parse("d", """
                {"biomePatches":[{"biome":"minecraft:cherry_grove","x":0,"z":0,"radius":400,
                                  "replace":"minecraft:desert"}]}
                """);
        assertEquals("minecraft:desert", swap.getBiomePatches().get(0).replace);
        assertEquals("minecraft:cherry_grove@0,0,400>minecraft:desert",
                swap.getBiomePatchesFingerprint());
        // Global scope, square shape, and blend all carry through + fingerprint.
        DimensionConfig full = parse("d", """
                {"biomePatches":[{"biome":"minecraft:river","x":10,"z":20,"radius":48,
                                  "scope":"global","shape":"square","blend":0}]}
                """);
        assertEquals("global", full.getBiomePatches().get(0).scope);
        assertEquals("square", full.getBiomePatches().get(0).shape);
        assertEquals(0, full.getBiomePatches().get(0).blend);
        assertEquals("minecraft:river@10,20,48!global#square~0",
                full.getBiomePatchesFingerprint());
    }

    @Test
    void exitsBlockDeserialises() {
        DimensionConfig config = parse("d", """
                {"exits":{
                  "void":       {"action":"teleport","target":"bed"},
                  "death":      {"target":"worldSpawn"},
                  "death:lava": {"action":"respawnAt",
                                 "target":{"dimension":"adventure:the_furnace_halls","arrival":"spawn"}},
                  "fallFrom":   {"minHeight":120,"target":"origin"},
                  "enderPearl": {"target":{"dimension":"adventure:the_starwell","arrival":[0,80,0]}}}}
                """);
        assertEquals(5, config.getExits().size());
        assertEquals("teleport", config.getExits().get("void").getAction());
        assertEquals("teleport", config.getExits().get("death").getAction());  // default
        assertEquals("respawnAt", config.getExits().get("death:lava").getAction());
        assertEquals(120, config.getExits().get("fallFrom").getMinHeight());
        assertEquals(100, config.getExits().get("void").getMinHeight());       // default
        assertEquals("bed", config.getExits().get("void").target.getAsString());
        assertEquals("adventure:the_starwell", config.getExits().get("enderPearl")
                .target.getAsJsonObject().get("dimension").getAsString());
        // absent -> empty map, never null
        assertTrue(parse("d", "{}").getExits().isEmpty());
    }

    @Test
    void exitShrinesBlockDeserialises() {
        DimensionConfig config = parse("d", """
                {"exitShrines":{"enabled":true,
                 "target":{"dimension":"adventure:the_starwell","arrival":"spawn"}}}
                """);
        assertTrue(config.hasExitShrines());
        assertEquals("dim!adventure:the_starwell!spawn", config.getExitShrines().getTargetMode());
        // default target is bed; absent/disabled block means no shrines
        assertEquals("bed", parse("d", "{\"exitShrines\":{\"enabled\":true}}")
                .getExitShrines().getTargetMode());
        assertFalse(parse("d", "{}").hasExitShrines());
        assertFalse(parse("d", "{\"exitShrines\":{\"enabled\":false}}").hasExitShrines());
    }

    @Test
    void structuresBlockDeserialises() {
        DimensionConfig config = parse("d", """
                {"structures":{"wants":{"swamp_ruin":{"min":0,"max":2000}},
                 "shuns":{"village":{"minDistance":4000}},
                 "endgame":{"allow":false,"safeRadius":1228}}}
                """);
        assertEquals(0, config.getStructures().wants.get("swamp_ruin").min);
        assertEquals(2000, config.getStructures().wants.get("swamp_ruin").max);
        assertEquals(4000, config.getStructures().shuns.get("village").minDistance);
        assertEquals(false, config.getStructures().endgame.allow);
        assertEquals(1228, config.getStructures().endgame.safeRadius);
    }

    // --- frame material generalisation (further-portal-customisations Tier 1) ---

    @Test
    void frameBlockAcceptsAllFourForms() {
        assertEquals(java.util.List.of("minecraft:clay"),
                parse("d", "{\"portal\":{\"frameBlock\":\"minecraft:clay\"}}")
                        .getPortal().getFrameAcceptForms());
        assertEquals(java.util.List.of("#minecraft:logs"),
                parse("d", "{\"portal\":{\"frameBlock\":\"#minecraft:logs\"}}")
                        .getPortal().getFrameAcceptForms());
        assertEquals(java.util.List.of("minecraft:oak_planks", "#minecraft:logs"),
                parse("d", "{\"portal\":{\"frameBlock\":[\"minecraft:oak_planks\",\"#minecraft:logs\"]}}")
                        .getPortal().getFrameAcceptForms());
        assertEquals(java.util.List.of("#adventure:red_blocks"),
                parse("d", "{\"portal\":{\"frameBlock\":{\"colorGroup\":\"red\"}}}")
                        .getPortal().getFrameAcceptForms());
        // every form counts as "has a portal"
        assertTrue(parse("d", "{\"portal\":{\"frameBlock\":\"#minecraft:logs\"}}").hasPortal());
        assertTrue(parse("d", "{\"portal\":{\"frameBlock\":{\"colorGroup\":\"red\"}}}").hasPortal());
        assertFalse(parse("d", "{\"portal\":{}}").hasPortal());
    }

    @Test
    void placementBlockResolutionOrder() {
        // explicit framePlaceBlock wins
        assertEquals("minecraft:oak_log",
                parse("d", "{\"portal\":{\"frameBlock\":\"#minecraft:logs\","
                        + "\"framePlaceBlock\":\"minecraft:oak_log\"}}")
                        .getPortal().resolvePlacementBlockId());
        // plain frameBlock is its own place block
        assertEquals("minecraft:clay",
                parse("d", "{\"portal\":{\"frameBlock\":\"minecraft:clay\"}}")
                        .getPortal().resolvePlacementBlockId());
        // lists fall back to their first plain id
        assertEquals("minecraft:oak_planks",
                parse("d", "{\"portal\":{\"frameBlock\":[\"#minecraft:logs\",\"minecraft:oak_planks\"]}}")
                        .getPortal().resolvePlacementBlockId());
        // colour groups default to the colour's wool
        assertEquals("minecraft:red_wool",
                parse("d", "{\"portal\":{\"frameBlock\":{\"colorGroup\":\"red\"}}}")
                        .getPortal().resolvePlacementBlockId());
        // tag-only with no place block: null (callers fall back, validator warns)
        assertNull(parse("d", "{\"portal\":{\"frameBlock\":\"#minecraft:logs\"}}")
                .getPortal().resolvePlacementBlockId());
    }

    @Test
    void toPortalDefinitionCarriesFrameTier1Fields() {
        // Simple config: definition (and its persisted zone records) look
        // exactly like before — no accepts, no place block, no orientation.
        PortalDefinition plain = parse("d",
                "{\"portal\":{\"frameBlock\":\"minecraft:clay\",\"igniterItem\":\"minecraft:stick\"}}")
                .toPortalDefinition();
        assertEquals("minecraft:clay", plain.getFrameBlock());
        assertEquals(java.util.List.of("minecraft:clay"), plain.getFrameAccepts());
        assertEquals("minecraft:clay", plain.getFramePlaceBlock());
        assertEquals("any", plain.getOrientation());

        PortalDefinition rich = parse("d", """
                {"portal":{"frameBlock":["#minecraft:logs","minecraft:oak_planks"],
                 "framePlaceBlock":"minecraft:oak_log",
                 "orientation":"vertical_x",
                 "igniterItem":"minecraft:stick"}}
                """).toPortalDefinition();
        // Primary is ALWAYS a plain parseable id (the placement block) —
        // '#' in a persisted frameBlock crash-loops older jars, which
        // Identifier.of() it in an uncaught world-tick path.
        assertEquals("minecraft:oak_log", rich.getFrameBlock());
        assertEquals(java.util.List.of("#minecraft:logs", "minecraft:oak_planks"),
                rich.getFrameAccepts());
        assertEquals("minecraft:oak_log", rich.getFramePlaceBlock());
        assertEquals("vertical_x", rich.getOrientation());

        PortalDefinition colour = parse("d",
                "{\"portal\":{\"frameBlock\":{\"colorGroup\":\"lime\"}}}").toPortalDefinition();
        assertEquals("minecraft:lime_wool", colour.getFrameBlock());
        assertEquals(java.util.List.of("#adventure:lime_blocks"), colour.getFrameAccepts());
        assertEquals("minecraft:lime_wool", colour.getFramePlaceBlock());

        // Tag-only with no place block: primary falls to obsidian (the
        // documented build fallback) but the matcher still accepts the tag.
        PortalDefinition tagOnly = parse("d",
                "{\"portal\":{\"frameBlock\":\"#minecraft:logs\"}}").toPortalDefinition();
        assertEquals("minecraft:obsidian", tagOnly.getFrameBlock());
        assertEquals(java.util.List.of("#minecraft:logs"), tagOnly.getFrameAccepts());
        // no '#' ever reaches a persisted frameBlock
        assertFalse(tagOnly.getFrameBlock().startsWith("#"));
    }
}
