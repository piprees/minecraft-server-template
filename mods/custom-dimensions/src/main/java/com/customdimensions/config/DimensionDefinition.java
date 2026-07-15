package com.customdimensions.config;

import net.minecraft.util.Identifier;

public class DimensionDefinition {
    private static String namespace = "adventure";

    public static String getNamespace() {
        return namespace;
    }

    public static void setNamespace(String ns) {
        namespace = ns;
    }

    private String name;
    private String type;
    private String dimensionId;
    private Long seed;
    private String biome;
    private Boolean hostileSpawning;
    private String noiseSettings;

    public DimensionDefinition() {
    }

    public DimensionDefinition(String name, String type, String dimensionId) {
        this.name = name;
        this.type = type;
        this.dimensionId = dimensionId.toLowerCase();
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getDimensionId() {
        return this.dimensionId;
    }

    public void setDimensionId(String dimensionId) {
        this.dimensionId = dimensionId;
    }

    public Long getSeed() {
        return this.seed;
    }

    public void setSeed(Long seed) {
        this.seed = seed;
    }

    public String getBiome() {
        return this.biome;
    }

    public void setBiome(String biome) {
        this.biome = biome;
    }

    public boolean isHostileSpawningEnabled() {
        return this.hostileSpawning != null ? this.hostileSpawning : true;
    }

    public void setHostileSpawning(Boolean hostileSpawning) {
        this.hostileSpawning = hostileSpawning;
    }

    public String getNoiseSettings() {
        return this.noiseSettings;
    }

    public void setNoiseSettings(String noiseSettings) {
        this.noiseSettings = noiseSettings;
    }

    public Identifier getDimensionIdentifier() {
        String id = this.dimensionId.toLowerCase();
        return id.contains(":") ? Identifier.of(id) : Identifier.of(getNamespace(), id);
    }
}
