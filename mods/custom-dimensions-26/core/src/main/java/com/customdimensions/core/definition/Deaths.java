package com.customdimensions.core.definition;

/**
 * A definition's {@code deaths} block (decisions.md RB-31): what a player who
 * dies in the dimension keeps. A null rule leaves the {@code keepInventory}
 * game rule to decide.
 */
public record Deaths(Rule inventory, Rule experience) {

    public enum Rule {
        KEEP, DROP;

        public static Rule parse(String value) {
            return switch (value) {
                case "keep" -> KEEP;
                case "drop" -> DROP;
                default -> null;
            };
        }
    }

    /** Whether the player keeps it, given the {@code keepInventory} game rule. */
    public static boolean keeps(Rule rule, boolean keepInventoryRule) {
        return rule == null ? keepInventoryRule : rule == Rule.KEEP;
    }
}
