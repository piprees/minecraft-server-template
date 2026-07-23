package com.customdimensions.portal;

import com.customdimensions.MultiverseServer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What a portal frame ACCEPTS: a union of plain block ids and #-prefixed
 * block tags, built from the config's frameBlock forms (single id, tag
 * reference, explicit list, or colour-group sugar — see DimensionConfig
 * .Portal.getFrameAcceptForms()).
 *
 * Form PARSING is registry-free (unit-testable); block/tag RESOLUTION is
 * lazy, on first use, because block tags load with datapacks after our
 * config. Malformed entries are warned once and skipped — never a crash
 * (an empty matcher just means ignition never succeeds, and
 * PortalSafetyValidator flags it at boot). A tag that is well-formed but
 * doesn't exist simply never matches.
 *
 * Accepting is NOT placing: this class answers "does this block satisfy
 * the frame?"; the concrete block the mod places when it builds frames
 * (arrival portals, exit portals) comes from framePlaceBlock — see
 * PortalDefinition.getFramePlaceBlock().
 */
public final class FrameMatcher {

    private final List<String> blockIds = new ArrayList<>();
    private final List<String> tagIds = new ArrayList<>();
    private final List<String> malformed = new ArrayList<>();

    private transient List<Block> blocks;
    private transient List<TagKey<Block>> tags;
    private transient boolean warned;

    private FrameMatcher() {
    }

    /** Parse accept forms (block ids and "#ns:path" tags). Registry-free. */
    public static FrameMatcher of(List<String> forms) {
        FrameMatcher matcher = new FrameMatcher();
        for (String raw : forms != null ? forms : List.<String>of()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String form = raw.trim();
            boolean isTag = form.startsWith("#");
            String idPart = isTag ? form.substring(1) : form;
            if (Identifier.tryParse(idPart) == null) {
                matcher.malformed.add(form);
                continue;
            }
            (isTag ? matcher.tagIds : matcher.blockIds).add(idPart);
        }
        return matcher;
    }

    /** True when no valid form was parsed — such a matcher never matches. */
    public boolean isEmpty() {
        return this.blockIds.isEmpty() && this.tagIds.isEmpty();
    }

    /** Forms that failed to parse (for boot-time validation messages). */
    public List<String> getMalformed() {
        return List.copyOf(this.malformed);
    }

    /** Parsed plain block ids (registry-free view, for tests/validation). */
    public List<String> getBlockIds() {
        return List.copyOf(this.blockIds);
    }

    /** Parsed tag ids, without the '#' (registry-free view). */
    public List<String> getTagIds() {
        return List.copyOf(this.tagIds);
    }

    public boolean matches(BlockState state) {
        this.resolve();
        for (Block block : this.blocks) {
            if (state.getBlock() == block) {
                return true;
            }
        }
        for (TagKey<Block> tag : this.tags) {
            if (state.isIn(tag)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a block id satisfies this frame (igniter candidate ordering).
     * Plain-id comparison is registry-free; tag membership resolves.
     */
    public boolean acceptsBlockId(String blockId) {
        if (blockId == null) {
            return false;
        }
        if (this.blockIds.contains(blockId)) {
            return true;
        }
        if (this.tagIds.isEmpty()) {
            return false;
        }
        Identifier id = Identifier.tryParse(blockId);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            return false;
        }
        return this.matches(Registries.BLOCK.get(id).getDefaultState());
    }

    private void resolve() {
        if (this.blocks != null) {
            return;
        }
        List<Block> resolvedBlocks = new ArrayList<>();
        List<TagKey<Block>> resolvedTags = new ArrayList<>();
        for (String idString : this.blockIds) {
            Identifier id = Identifier.tryParse(idString);
            Block block = id != null && Registries.BLOCK.containsId(id) ? Registries.BLOCK.get(id) : null;
            if (block != null && block != Blocks.AIR) {
                resolvedBlocks.add(block);
            } else if (!this.warned) {
                MultiverseServer.LOGGER.warn(
                        "Frame block '{}' is not a known block — this form never matches", idString);
            }
        }
        for (String tagString : this.tagIds) {
            Identifier id = Identifier.tryParse(tagString);
            if (id != null) {
                resolvedTags.add(TagKey.of(RegistryKeys.BLOCK, id));
            }
        }
        if (!this.warned && !this.malformed.isEmpty()) {
            MultiverseServer.LOGGER.warn(
                    "Ignoring malformed frame block form(s): {}", this.malformed);
        }
        this.warned = true;
        this.tags = resolvedTags;
        this.blocks = resolvedBlocks;
    }
}
