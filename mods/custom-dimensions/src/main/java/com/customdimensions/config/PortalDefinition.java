package com.customdimensions.config;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public class PortalDefinition {
    private String id;
    private String frameBlock;
    private String igniterItem;
    private String targetDimension;
    private String color;
    private int lightLevel;
    private double scale = 1.0;
    private int cooldown = 40;
    private String particleType;
    private String igniteSound = "block.portal.trigger";
    private String enterSound = "block.portal.travel";
    private String exitSound = "block.portal.travel";

    public PortalDefinition() {
    }

    public PortalDefinition(String id, String frameBlock, String igniterItem, String targetDimension, String color, int lightLevel) {
        this.id = id;
        this.frameBlock = frameBlock;
        this.igniterItem = igniterItem;
        this.targetDimension = targetDimension;
        this.color = color;
        this.lightLevel = lightLevel;
        this.scale = 1.0;
        this.cooldown = 40;
    }

    public String getId() {
        return this.id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFrameBlock() {
        return this.frameBlock;
    }

    public void setFrameBlock(String frameBlock) {
        this.frameBlock = frameBlock;
    }

    public String getIgniterItem() {
        return this.igniterItem;
    }

    public void setIgniterItem(String igniterItem) {
        this.igniterItem = igniterItem;
    }

    public String getTargetDimension() {
        return this.targetDimension;
    }

    public void setTargetDimension(String targetDimension) {
        this.targetDimension = targetDimension;
    }

    public String getColor() {
        return this.color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int getLightLevel() {
        return this.lightLevel;
    }

    public void setLightLevel(int lightLevel) {
        this.lightLevel = lightLevel;
    }

    public double getScale() {
        return this.scale;
    }

    public void setScale(double scale) {
        this.scale = scale;
    }

    public int getCooldown() {
        return this.cooldown;
    }

    public void setCooldown(int cooldown) {
        this.cooldown = cooldown;
    }

    public String getParticleType() {
        return this.particleType;
    }

    public void setParticleType(String particleType) {
        this.particleType = particleType;
    }

    public String getIgniteSound() {
        return this.igniteSound != null ? this.igniteSound : "block.portal.trigger";
    }

    public void setIgniteSound(String igniteSound) {
        this.igniteSound = igniteSound;
    }

    public void setEnterSound(String enterSound) {
        this.enterSound = enterSound;
    }

    public void setExitSound(String exitSound) {
        this.exitSound = exitSound;
    }

    public String getEnterSound() {
        return this.enterSound != null ? this.enterSound : "block.portal.travel";
    }

    public String getExitSound() {
        return this.exitSound != null ? this.exitSound : "block.portal.travel";
    }

    public RegistryKey<World> getTargetKey() {
        Identifier id = this.targetDimension.contains(":")
                ? Identifier.of(this.targetDimension)
                : Identifier.of("minecraft", this.targetDimension);
        return RegistryKey.of(RegistryKeys.WORLD, id);
    }
}
