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
}
