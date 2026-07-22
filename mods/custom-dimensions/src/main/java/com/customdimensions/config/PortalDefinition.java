package com.customdimensions.config;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Map;

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
    // Anchor + singleUse ride along in portal_links.json zone records (this
    // whole definition is serialised per zone), so persisted zones keep the
    // behaviour they were ignited with across restarts.
    private int[] anchorPos;
    private String anchorExit;
    private boolean singleUse;
    private int singleUseDelayTicks = 200;
    private String singleUseBreakMode = "decay";
    private Map<String, String> singleUseDecayMap;

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

    public boolean hasAnchor() {
        return this.anchorPos != null && this.anchorPos.length == 3;
    }

    public int[] getAnchorPos() {
        return this.anchorPos;
    }

    public void setAnchorPos(int[] anchorPos) {
        this.anchorPos = anchorPos;
    }

    /** Anchor arrival exit mode: "origin" | "bed" | "worldSpawn". */
    public String getAnchorExit() {
        return this.anchorExit != null ? this.anchorExit : "origin";
    }

    public void setAnchorExit(String anchorExit) {
        this.anchorExit = anchorExit;
    }

    public boolean isSingleUse() {
        return this.singleUse;
    }

    public void setSingleUse(boolean singleUse) {
        this.singleUse = singleUse;
    }

    public int getSingleUseDelayTicks() {
        return this.singleUseDelayTicks > 0 ? this.singleUseDelayTicks : 200;
    }

    public void setSingleUseDelayTicks(int singleUseDelayTicks) {
        this.singleUseDelayTicks = singleUseDelayTicks;
    }

    /** "destroy" | "decay" | "partial". */
    public String getSingleUseBreakMode() {
        return this.singleUseBreakMode != null ? this.singleUseBreakMode : "decay";
    }

    public void setSingleUseBreakMode(String singleUseBreakMode) {
        this.singleUseBreakMode = singleUseBreakMode;
    }

    public Map<String, String> getSingleUseDecayMap() {
        return this.singleUseDecayMap;
    }

    public void setSingleUseDecayMap(Map<String, String> singleUseDecayMap) {
        this.singleUseDecayMap = singleUseDecayMap;
    }

    public RegistryKey<World> getTargetKey() {
        Identifier id = this.targetDimension.contains(":")
                ? Identifier.of(this.targetDimension)
                : Identifier.of("minecraft", this.targetDimension);
        return RegistryKey.of(RegistryKeys.WORLD, id);
    }
}
