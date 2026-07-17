package com.customdimensions.config;

import com.customdimensions.MultiverseServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class MultiverseConfig {
    private static final MultiverseConfig INSTANCE = new MultiverseConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "multiverse_config.json";

    private final List<DimensionDefinition> dimensions = new ArrayList<>();
    private final List<PortalDefinition> portals = new ArrayList<>();
    private final List<WorldSeedDefinition> worlds = new ArrayList<>();
    private transient Path configPath;
    private transient MinecraftServer server;

    private String namespace;
    private Long worldSeed;
    private String frameOverworld = "minecraft:crying_obsidian";
    private String frameNether = "minecraft:obsidian";
    private String frameEnd = "minecraft:iron_block";
    private int idleUnloadMinutes = 5;

    public static MultiverseConfig getInstance() {
        return INSTANCE;
    }

    public void setServer(MinecraftServer server) {
        this.server = server;
        this.configPath = server.getRunDirectory().resolve("config").resolve(FILE_NAME);
    }

    public void load() {
        if (this.configPath == null) {
            return;
        }
        if (!Files.exists(this.configPath)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(this.configPath)) {
            MultiverseConfig loaded = GSON.fromJson(reader, MultiverseConfig.class);
            if (loaded != null) {
                this.dimensions.clear();
                this.dimensions.addAll(loaded.dimensions);
                this.portals.clear();
                this.portals.addAll(loaded.portals);
                this.worlds.clear();
                this.worlds.addAll(loaded.worlds);
                this.namespace = loaded.namespace;
                this.worldSeed = loaded.worldSeed;
                this.frameOverworld = loaded.frameOverworld != null ? loaded.frameOverworld : this.frameOverworld;
                this.frameNether = loaded.frameNether != null ? loaded.frameNether : this.frameNether;
                this.frameEnd = loaded.frameEnd != null ? loaded.frameEnd : this.frameEnd;
                this.idleUnloadMinutes = loaded.idleUnloadMinutes > 0 ? loaded.idleUnloadMinutes : this.idleUnloadMinutes;
            }
            DimensionDefinition.setNamespace(this.namespace != null ? this.namespace : "adventure");
        } catch (IOException | com.google.gson.JsonParseException e) {
            MultiverseServer.LOGGER.error("Failed to load config — file left for inspection: {}", this.configPath, e);
        }
    }

    public DimensionDefinition getDimension(String name) {
        return this.dimensions.stream()
                .filter(d -> d.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public List<DimensionDefinition> getDimensions() {
        return Collections.unmodifiableList(new ArrayList<>(this.dimensions));
    }

    public List<String> getDimensionNames() {
        return this.dimensions.stream().map(DimensionDefinition::getName).toList();
    }

    public PortalDefinition getPortal(String id) {
        return this.portals.stream()
                .filter(p -> p.getId().equals(id))
                .findFirst()
                .orElse(null);
    }

    public List<PortalDefinition> getPortals() {
        return Collections.unmodifiableList(new ArrayList<>(this.portals));
    }

    public List<String> getPortalIds() {
        return this.portals.stream().map(PortalDefinition::getId).toList();
    }

    public Optional<PortalDefinition> getPortalByIgniter(String itemId) {
        return this.portals.stream()
                .filter(p -> p.getIgniterItem().equals(itemId))
                .findFirst();
    }

    public PortalDefinition getDefaultPortalForFrameBlock(String blockId) {
        if (blockId.equals(this.frameOverworld)) {
            return new PortalDefinition("default_overworld", this.frameOverworld, "", "minecraft:overworld", "#00AAAA", 0);
        }
        if (blockId.equals(this.frameNether)) {
            return new PortalDefinition("default_nether", this.frameNether, "", "minecraft:the_nether", "#AA0000", 0);
        }
        if (blockId.equals(this.frameEnd)) {
            return new PortalDefinition("default_end", this.frameEnd, "", "minecraft:the_end", "#00AA00", 0);
        }
        return null;
    }

    public String getFrameOverworld() {
        return this.frameOverworld;
    }

    public String getFrameNether() {
        return this.frameNether;
    }

    public String getFrameEnd() {
        return this.frameEnd;
    }

    public int getIdleUnloadMinutes() {
        return this.idleUnloadMinutes;
    }

    /**
     * Seed override for a static world, by full dimension id. The
     * overworld is driven by the top-level "worldSeed" (config-driven
     * multiverse — the .env SEED is only a legacy fallback that seeds
     * level.dat); other worlds by the "seed" on their worlds[] entry.
     * The config loads at createWorlds HEAD, so the override is active
     * from the overworld's very first chunk.
     */
    public Long getWorldSeedOverride(String dimensionId) {
        if ("minecraft:overworld".equals(dimensionId)) {
            return this.worldSeed;
        }
        return this.worlds.stream()
                .filter(w -> dimensionId.equals(w.getDimensionId()))
                .map(WorldSeedDefinition::getSeed)
                .filter(s -> s != null)
                .findFirst()
                .orElse(null);
    }

    /** The worlds[] entry for a given name (e.g. "overworld"), or null. */
    public WorldSeedDefinition getWorld(String name) {
        return this.worlds.stream()
                .filter(w -> name.equals(w.getName()))
                .findFirst()
                .orElse(null);
    }

}
