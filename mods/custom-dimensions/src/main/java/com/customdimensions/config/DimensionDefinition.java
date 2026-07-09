package com.customdimensions.config;

import net.minecraft.util.Identifier;

public class DimensionDefinition {
    private String name;
    private String type;
    private String dimensionId;

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

    public Identifier getDimensionIdentifier() {
        String id = this.dimensionId.toLowerCase();
        return id.contains(":") ? Identifier.of(id) : Identifier.of("minecraft", id);
    }
}
