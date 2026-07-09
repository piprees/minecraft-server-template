package com.customdimensions.config;

import com.customdimensions.MultiverseServer;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
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
    private transient Path configPath;
    private transient MinecraftServer server;
    private transient boolean dirty = false;

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
            this.save();
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(this.configPath)) {
            MultiverseConfig loaded = GSON.fromJson(reader, MultiverseConfig.class);
            if (loaded != null) {
                this.dimensions.clear();
                this.dimensions.addAll(loaded.dimensions);
                this.portals.clear();
                this.portals.addAll(loaded.portals);
                this.frameOverworld = loaded.frameOverworld != null ? loaded.frameOverworld : this.frameOverworld;
                this.frameNether = loaded.frameNether != null ? loaded.frameNether : this.frameNether;
                this.frameEnd = loaded.frameEnd != null ? loaded.frameEnd : this.frameEnd;
                this.idleUnloadMinutes = loaded.idleUnloadMinutes > 0 ? loaded.idleUnloadMinutes : this.idleUnloadMinutes;
            }
            this.dirty = false;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to load config", e);
        }
    }

    public void save() {
        if (this.configPath == null) {
            return;
        }
        try {
            Files.createDirectories(this.configPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(this.configPath)) {
                GSON.toJson(this, writer);
                this.dirty = false;
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to save config", e);
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

    public void addDimension(DimensionDefinition def) {
        this.dimensions.removeIf(d -> d.getName().equals(def.getName()));
        this.dimensions.add(def);
        this.dirty = true;
    }

    public boolean removeDimension(String name) {
        boolean removed = this.dimensions.removeIf(d -> d.getName().equals(name));
        if (removed) {
            this.dirty = true;
        }
        return removed;
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

    public void addPortal(PortalDefinition def) {
        this.portals.removeIf(p -> p.getId().equals(def.getId()));
        this.portals.add(def);
        this.dirty = true;
    }

    public boolean removePortal(String id) {
        boolean removed = this.portals.removeIf(p -> p.getId().equals(id));
        if (removed) {
            this.dirty = true;
        }
        return removed;
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

    public boolean isDirty() {
        return this.dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }
}
