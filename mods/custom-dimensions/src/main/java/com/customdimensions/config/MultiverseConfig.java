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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class MultiverseConfig {
    private static final MultiverseConfig INSTANCE = new MultiverseConfig();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "multiverse_config.json";

    private final transient ReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<DimensionDefinition> dimensions = new ArrayList<>();
    private final List<PortalDefinition> portals = new ArrayList<>();
    private transient Path configPath;
    private transient MinecraftServer server;
    private transient boolean dirty = false;

    private String frameOverworld = "minecraft:crying_obsidian";
    private String frameNether = "minecraft:obsidian";
    private String frameEnd = "minecraft:iron_block";

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
        this.lock.writeLock().lock();
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
            }
            this.dirty = false;
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to load config", e);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public void save() {
        if (this.configPath == null) {
            return;
        }
        this.lock.writeLock().lock();
        try {
            Files.createDirectories(this.configPath.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(this.configPath)) {
                GSON.toJson(this, writer);
                this.dirty = false;
            }
        } catch (IOException e) {
            MultiverseServer.LOGGER.error("Failed to save config", e);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public DimensionDefinition getDimension(String name) {
        this.lock.readLock().lock();
        try {
            return this.dimensions.stream()
                    .filter(d -> d.getName().equals(name))
                    .findFirst()
                    .orElse(null);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public List<DimensionDefinition> getDimensions() {
        this.lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(this.dimensions));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public List<String> getDimensionNames() {
        this.lock.readLock().lock();
        try {
            return this.dimensions.stream().map(DimensionDefinition::getName).toList();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void addDimension(DimensionDefinition def) {
        this.lock.writeLock().lock();
        try {
            this.dimensions.removeIf(d -> d.getName().equals(def.getName()));
            this.dimensions.add(def);
            this.dirty = true;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public boolean removeDimension(String name) {
        this.lock.writeLock().lock();
        try {
            boolean removed = this.dimensions.removeIf(d -> d.getName().equals(name));
            if (removed) {
                this.dirty = true;
            }
            return removed;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public PortalDefinition getPortal(String id) {
        this.lock.readLock().lock();
        try {
            return this.portals.stream()
                    .filter(p -> p.getId().equals(id))
                    .findFirst()
                    .orElse(null);
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public List<PortalDefinition> getPortals() {
        this.lock.readLock().lock();
        try {
            return Collections.unmodifiableList(new ArrayList<>(this.portals));
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public List<String> getPortalIds() {
        this.lock.readLock().lock();
        try {
            return this.portals.stream().map(PortalDefinition::getId).toList();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public void addPortal(PortalDefinition def) {
        this.lock.writeLock().lock();
        try {
            this.portals.removeIf(p -> p.getId().equals(def.getId()));
            this.portals.add(def);
            this.dirty = true;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public boolean removePortal(String id) {
        this.lock.writeLock().lock();
        try {
            boolean removed = this.portals.removeIf(p -> p.getId().equals(id));
            if (removed) {
                this.dirty = true;
            }
            return removed;
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    public Optional<PortalDefinition> getPortalByIgniter(String itemId) {
        this.lock.readLock().lock();
        try {
            return this.portals.stream()
                    .filter(p -> p.getIgniterItem().equals(itemId))
                    .findFirst();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    public PortalDefinition getDefaultPortalForFrameBlock(String blockId) {
        this.lock.readLock().lock();
        try {
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
        } finally {
            this.lock.readLock().unlock();
        }
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

    public boolean isDirty() {
        return this.dirty;
    }

    public void markDirty() {
        this.dirty = true;
    }
}
