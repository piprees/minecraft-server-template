package com.customdimensions.client.dev;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The player, as facts. Held separately from the client that supplies them so
 * the shaping is testable without a {@link net.minecraft.client.MinecraftClient}
 * — this record carries no Minecraft types.
 *
 * <p>Every field is present on every call. An empty hotbar slot is a null
 * element rather than a missing one, and a durability that does not apply is
 * null rather than a substituted zero, so an index or a key means the same
 * thing whatever the player is holding.
 */
public record PlayerFacts(
        String dimension,
        double x, double y, double z,
        int blockX, int blockY, int blockZ,
        Rotation rotation,
        Vitals vitals,
        Held held,
        Status status) {

    /** Named rather than positional: a typo would otherwise be a silent false. */
    public enum Flag {
        SNEAKING, SPRINTING, SWIMMING, CRAWLING, GLIDING, SLEEPING, RIDING,
        ON_FIRE, IN_LAVA, IN_WATER, SUBMERGED, CLIMBING, BLOCKING, SPECTATOR
    }

    /** {@code damage} and {@code maxDamage} are null for anything that cannot wear. */
    public record Item(String id, int count, Integer damage, Integer maxDamage) {}

    public record Rotation(double yaw, double pitch, double headYaw, double bodyYaw,
                           String facing) {}

    public record Vitals(double health, double maxHealth, int food, double saturation,
                         int air, int maxAir, int xpLevel, double xpProgress) {}

    /** {@code hotbar} is padded to nine; a null element is an empty slot. */
    public record Held(Item mainHand, Item offHand, int selectedSlot, List<Item> hotbar) {}

    public record Status(String pose, Set<Flag> flags, boolean onGround, double fallDistance) {}

    private static final int HOTBAR_SLOTS = 9;

    /** {@code ON_FIRE} to {@code onFire}. */
    public static String flagName(Flag flag) {
        String[] words = flag.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder out = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            out.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return out.toString();
    }

    /** Off the ground having already dropped; a jump only counts once it descends. */
    public static boolean falling(double fallDistance, boolean onGround) {
        return fallDistance > 0 && !onGround;
    }

    /** Air is spent, which is when the damage starts. */
    public static boolean drowning(int air) {
        return air <= 0;
    }

    /** A field the render thread could not answer, and why. */
    public static String absent(String why) {
        return Json.obj().str("absent", why).toString();
    }

    public String json() {
        return Json.obj()
                .str("dimension", this.dimension)
                .raw("pos", Json.numbers(this.x, this.y, this.z))
                .raw("blockPos", Json.numbers(this.blockX, this.blockY, this.blockZ))
                .raw("rotation", rotationJson())
                .raw("vitals", vitalsJson())
                .raw("held", heldJson())
                .raw("status", statusJson())
                .toString();
    }

    private String rotationJson() {
        return Json.obj()
                .num("yaw", this.rotation.yaw())
                .num("pitch", this.rotation.pitch())
                .num("headYaw", this.rotation.headYaw())
                .num("bodyYaw", this.rotation.bodyYaw())
                .str("facing", this.rotation.facing())
                .toString();
    }

    private String vitalsJson() {
        return Json.obj()
                .num("health", this.vitals.health())
                .num("maxHealth", this.vitals.maxHealth())
                .num("food", this.vitals.food())
                .num("saturation", this.vitals.saturation())
                .num("air", this.vitals.air())
                .num("maxAir", this.vitals.maxAir())
                .num("xpLevel", this.vitals.xpLevel())
                .num("xpProgress", this.vitals.xpProgress())
                .toString();
    }

    private String heldJson() {
        StringBuilder hotbar = new StringBuilder("[");
        List<Item> slots = this.held.hotbar();
        for (int slot = 0; slot < HOTBAR_SLOTS; slot++) {
            Item item = slot < slots.size() ? slots.get(slot) : null;
            hotbar.append(slot > 0 ? "," : "").append(itemJson(item));
        }
        return Json.obj()
                .raw("mainHand", itemJson(this.held.mainHand()))
                .raw("offHand", itemJson(this.held.offHand()))
                .num("selectedSlot", this.held.selectedSlot())
                .raw("hotbar", hotbar.append(']').toString())
                .toString();
    }

    private static String itemJson(Item item) {
        if (item == null) {
            return "null";
        }
        return Json.obj()
                .str("id", item.id())
                .num("count", item.count())
                .raw("damage", item.damage() == null ? null : Json.number(item.damage()))
                .raw("maxDamage", item.maxDamage() == null ? null : Json.number(item.maxDamage()))
                .toString();
    }

    private String statusJson() {
        Json.Obj out = Json.obj()
                .str("pose", this.status.pose())
                .bool("onGround", this.status.onGround())
                .num("fallDistance", this.status.fallDistance())
                .bool("falling", falling(this.status.fallDistance(), this.status.onGround()))
                .bool("drowning", drowning(this.vitals.air()));
        for (Flag flag : Flag.values()) {
            out.bool(flagName(flag), this.status.flags().contains(flag));
        }
        return out.toString();
    }
}
