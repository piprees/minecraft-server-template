package com.customdimensions.core.portal;

import com.customdimensions.core.Id;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a portal frame ACCEPTS, as parsed config: a union of plain block ids
 * and #-prefixed block tags, built from the config's frameBlock forms (single
 * id, tag reference, explicit list, or colour-group sugar — see
 * DimensionConfig.Portal.getFrameAcceptForms()).
 *
 * Parsing is registry-free. Matching a block state, and tag membership, need
 * the block registry and live in the server module's FrameMatcher, which is
 * built from these forms. Malformed entries are collected, never fatal (an
 * empty set of forms just means ignition never succeeds, and
 * PortalSafetyValidator flags it at boot).
 *
 * Accepting is NOT placing: the concrete block the mod places when it builds
 * frames comes from framePlaceBlock — see PortalDefinition.getFramePlaceBlock().
 */
public final class FrameForms {

    /** Answers whether a block id is in a block tag; supplied by the server module. */
    @FunctionalInterface
    public interface TagMembership {
        boolean isInTag(String blockId, String tagId);
    }

    /** Knows no tags: every tag lookup answers false. */
    public static final TagMembership NO_TAGS = (blockId, tagId) -> false;

    private static volatile TagMembership tagMembership = NO_TAGS;

    private final List<String> blockIds = new ArrayList<>();
    private final List<String> tagIds = new ArrayList<>();
    private final List<String> malformed = new ArrayList<>();

    private FrameForms() {
    }

    /** Installs the tag lookup {@link #acceptsBlockId(String)} uses. */
    public static void setTagMembership(TagMembership membership) {
        tagMembership = membership != null ? membership : NO_TAGS;
    }

    /** Parse accept forms (block ids and "#ns:path" tags). */
    public static FrameForms of(List<String> forms) {
        FrameForms parsed = new FrameForms();
        for (String raw : forms != null ? forms : List.<String>of()) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String form = raw.trim();
            boolean isTag = form.startsWith("#");
            String idPart = isTag ? form.substring(1) : form;
            if (Id.tryParse(idPart) == null) {
                parsed.malformed.add(form);
                continue;
            }
            (isTag ? parsed.tagIds : parsed.blockIds).add(idPart);
        }
        return parsed;
    }

    /** True when no valid form was parsed — such a frame never matches. */
    public boolean isEmpty() {
        return this.blockIds.isEmpty() && this.tagIds.isEmpty();
    }

    /** Forms that failed to parse (for boot-time validation messages). */
    public List<String> getMalformed() {
        return List.copyOf(this.malformed);
    }

    /** Parsed plain block ids. */
    public List<String> getBlockIds() {
        return List.copyOf(this.blockIds);
    }

    /** Parsed tag ids, without the '#'. */
    public List<String> getTagIds() {
        return List.copyOf(this.tagIds);
    }

    /**
     * Whether a block id satisfies this frame (igniter candidate ordering),
     * resolving tags through the installed {@link TagMembership}.
     */
    public boolean acceptsBlockId(String blockId) {
        return this.acceptsBlockId(blockId, tagMembership);
    }

    /** The block a covered frame block decays into on a random tick, and the blocks that decay into it. */
    static final Map<String, List<String>> COVERED_DECAY = Map.of(
            "minecraft:netherrack", List.of("minecraft:crimson_nylium", "minecraft:warped_nylium"),
            "minecraft:dirt", List.of("minecraft:grass_block", "minecraft:mycelium"));

    /**
     * Whether a block keeps a lit frame standing: an accepted block, or what an
     * accepted block decays into once another frame block covers it. Ignition
     * stays strict ({@link #acceptsBlockId(String)}).
     */
    public boolean keepsStanding(String blockId) {
        if (this.acceptsBlockId(blockId)) {
            return true;
        }
        for (String from : COVERED_DECAY.getOrDefault(blockId, List.of())) {
            if (this.acceptsBlockId(from)) {
                return true;
            }
        }
        return false;
    }

    /** {@link #acceptsBlockId(String)} with an explicit tag lookup. */
    public boolean acceptsBlockId(String blockId, TagMembership membership) {
        if (blockId == null) {
            return false;
        }
        if (this.blockIds.contains(blockId)) {
            return true;
        }
        if (this.tagIds.isEmpty() || Id.tryParse(blockId) == null) {
            return false;
        }
        for (String tag : this.tagIds) {
            if (membership.isInTag(blockId, tag)) {
                return true;
            }
        }
        return false;
    }
}
