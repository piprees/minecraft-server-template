package com.customdimensions.config;

import com.customdimensions.portal.FrameMatcher;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.List;
import java.util.Map;

public class PortalDefinition {
    private String id;
    /**
     * Primary frame form: a plain block id for simple configs (today's
     * shape, unchanged in zone records), or the first accept form
     * ("#ns:tag" included) for tag/list/colour-group configs.
     */
    private String frameBlock;
    /**
     * Full accept forms (block ids and "#ns:path" tags) when the frame
     * accepts more than the single frameBlock. Null for simple configs —
     * getFrameAccepts() falls back to [frameBlock], so legacy zone records
     * behave exactly as before.
     */
    private List<String> frameAccepts;
    /**
     * Concrete block the mod PLACES when it builds frames (arrival
     * portals, exit portals). Null when frameBlock is itself a plain id.
     * Accepting is not placing — a tag describes what's accepted, this
     * field describes what's built.
     */
    private String framePlaceBlock;
    /**
     * "vertical" (X/Z) | "horizontal" (Y) | "vertical_x" | "vertical_z" |
     * "any". Null defers to the shape's implied orientation (door/doorway
     * vertical, end_exit horizontal), else "any" — today's behaviour.
     */
    private String orientation;
    /**
     * Named shape preset: "standard" (free-form flood-fill, the default) |
     * "door" (1x2) | "doorway" (2x3) | "end_exit" (horizontal ring). Null =
     * "standard". Persisted into zone records as a plain string (unknown
     * fields are ignored by older jars — downgrade-safe).
     */
    private String shape;
    /**
     * Block id placed at the interior's centre on end_exit ignition (a
     * pedestal). Null = nothing placed. Plain parseable id only.
     */
    private String centreBlock;
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
    private transient FrameMatcher frameMatcher;

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
        this.frameMatcher = null;
    }

    /** Accept forms for frame checks; [frameBlock] when none were stored. */
    public List<String> getFrameAccepts() {
        if (this.frameAccepts != null && !this.frameAccepts.isEmpty()) {
            return this.frameAccepts;
        }
        return this.frameBlock != null ? List.of(this.frameBlock) : List.of();
    }

    public void setFrameAccepts(List<String> frameAccepts) {
        this.frameAccepts = frameAccepts != null && !frameAccepts.isEmpty() ? frameAccepts : null;
        this.frameMatcher = null;
    }

    /** Lazily built (and cached) matcher over the accept forms. */
    public FrameMatcher resolveFrameMatcher() {
        if (this.frameMatcher == null) {
            this.frameMatcher = FrameMatcher.of(this.getFrameAccepts());
        }
        return this.frameMatcher;
    }

    /**
     * Concrete block id the mod places when it BUILDS frames (arrival and
     * exit portals): explicit framePlaceBlock, else frameBlock when it is a
     * plain id. Null when the config is tag-only without a place block —
     * callers fall back to their existing defaults and
     * PortalSafetyValidator warns at boot.
     */
    public String getFramePlaceBlock() {
        if (this.framePlaceBlock != null && !this.framePlaceBlock.isBlank()) {
            return this.framePlaceBlock;
        }
        return this.frameBlock != null && !this.frameBlock.startsWith("#") ? this.frameBlock : null;
    }

    public void setFramePlaceBlock(String framePlaceBlock) {
        this.framePlaceBlock = framePlaceBlock;
    }

    /**
     * Effective orientation: the explicit field always wins; otherwise the
     * shape's implication (door/doorway → "vertical", end_exit →
     * "horizontal"); otherwise "any" (today's behaviour).
     */
    public String getOrientation() {
        if (this.orientation != null && !this.orientation.isBlank()) {
            return this.orientation;
        }
        String implied = com.customdimensions.portal.PortalShape.impliedOrientation(this.shape);
        return implied != null ? implied : "any";
    }

    public void setOrientation(String orientation) {
        this.orientation = orientation;
    }

    /** Normalised shape preset name; "standard" when unset. */
    public String getShape() {
        return com.customdimensions.portal.PortalShape.normalise(this.shape);
    }

    public void setShape(String shape) {
        this.shape = shape;
    }

    /** Centre pedestal block id for end_exit shapes; null = none. */
    public String getCentreBlock() {
        return this.centreBlock != null && !this.centreBlock.isBlank() ? this.centreBlock : null;
    }

    public void setCentreBlock(String centreBlock) {
        this.centreBlock = centreBlock;
    }

    /**
     * Whether ignition may consider this axis. Unknown orientation values
     * behave as "any" (validator warns; never crash, never auto-fix).
     */
    public boolean allowsAxis(Direction.Axis axis) {
        switch (this.getOrientation()) {
            case "vertical":
                return axis != Direction.Axis.Y;
            case "horizontal":
                return axis == Direction.Axis.Y;
            case "vertical_x":
                return axis == Direction.Axis.X;
            case "vertical_z":
                return axis == Direction.Axis.Z;
            default:
                return true;
        }
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
