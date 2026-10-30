package com.customdimensions.core.firewall;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The start-up rules, free of the game: what the loaded game holds goes in,
 * the refusals and the structure sets to switch off come out.
 */
public final class Firewall {
    /** Where the refusal points the reader. */
    public static final String BRIDGES_DIR = "config/custom-dimensions/bridges/";

    /** One mixin config as the loader holds it: its mod, its name, the classes its mixins target. */
    public record MixinConfig(String mod, String config, Set<String> targets) {}

    /**
     * The loaded game. {@code registries} maps each kind to the ids of that
     * registry; {@code owners} maps a namespace to the mods that ship data or
     * classes under it; {@code ownNamespaces} adds the definitions' namespace to
     * {@link AllowList#NAMESPACES}.
     */
    public record Game(Set<String> mods, List<MixinConfig> mixins, Map<Finding.Kind, Set<String>> registries,
                       Map<String, Set<String>> owners, Set<String> ownNamespaces) {}

    /** What the audit found; {@code allowed} lists the allow-list entries that let a mixin through, as {@code mod (reason)}. */
    public record Verdict(List<Finding> refusals, Set<String> allowed, List<String> unreviewed) {
        public boolean refused() {
            return !refusals.isEmpty();
        }
    }

    private Firewall() {}

    public static Verdict evaluate(Game game, Map<String, Bridge> bridges) {
        List<Finding> out = new ArrayList<>();
        Set<String> allowed = new TreeSet<>();
        List<String> unreviewed = bridges.values().stream().filter(b -> !b.reviewed()).map(Bridge::mod).sorted().toList();
        for (String mod : new TreeSet<>(game.mods())) {
            if (AllowList.FORBIDDEN.contains(mod) && reviewed(bridges, mod).isEmpty()) {
                out.add(new Finding(mod, Finding.Kind.FRAMEWORK, "(installed)"));
            }
        }
        for (MixinConfig config : game.mixins()) {
            Set<String> hits = new TreeSet<>();
            for (String target : config.targets()) {
                if (WorldgenClasses.isWorldgen(target)) hits.add(target.replace('/', '.'));
            }
            if (hits.isEmpty()) continue;
            Optional<AllowList.Entry> entry = AllowList.find(config.mod());
            if (entry.isPresent()) {
                allowed.add(config.mod() + " (" + entry.get().reason() + ")");
                continue;
            }
            if (reviewed(bridges, config.mod()).map(b -> b.mixins().contains(config.config())).orElse(false)) continue;
            for (String hit : hits) out.add(new Finding(config.mod(), Finding.Kind.MIXIN, hit + " (" + config.config() + ")"));
        }
        Set<String> own = new TreeSet<>(AllowList.NAMESPACES);
        own.addAll(game.ownNamespaces());
        for (Map.Entry<Finding.Kind, Set<String>> registry : new TreeMap<>(game.registries()).entrySet()) {
            for (String id : new TreeSet<>(registry.getValue())) {
                String namespace = namespace(id);
                if (own.contains(namespace)) continue;
                Set<String> owners = game.owners().getOrDefault(namespace, Set.of());
                if (owners.stream().anyMatch(m -> reviewed(bridges, m).map(b -> b.registries().contains(id)).orElse(false))) continue;
                String mod = owners.isEmpty() ? "unknown (namespace " + namespace + ")" : String.join(", ", new TreeSet<>(owners));
                out.add(new Finding(mod, registry.getKey(), id));
            }
        }
        out.sort(Comparator.comparing(Finding::mod).thenComparing(Finding::kind).thenComparing(Finding::where));
        return new Verdict(List.copyOf(out), allowed, unreviewed);
    }

    /** The structure sets nobody adopted: outside the pack's namespaces and in no reviewed bridge's {@code structureSets}. */
    public static Set<String> switchedOff(Set<String> structureSets, Map<String, Bridge> bridges, Set<String> ownNamespaces) {
        Set<String> adopted = new TreeSet<>();
        bridges.values().stream().filter(Bridge::reviewed).forEach(b -> adopted.addAll(b.structureSets()));
        Set<String> off = new TreeSet<>();
        for (String id : structureSets) {
            String namespace = namespace(id);
            if (AllowList.NAMESPACES.contains(namespace) || ownNamespaces.contains(namespace) || adopted.contains(id)) continue;
            off.add(id);
        }
        return off;
    }

    /** The log text of a refusal: a headline, one line per finding, and what to do. */
    public static String message(List<Finding> refusals) {
        StringBuilder text = new StringBuilder("custom-dimensions refuses to start: ")
                .append(refusals.size()).append(refusals.size() == 1 ? " finding" : " findings")
                .append(" of another worldgen owner (mod: what, where)");
        for (Finding finding : refusals) text.append("\n  ").append(finding.line());
        text.append("\nRemove the mod, or adopt what it adds in a reviewed bridge in ").append(BRIDGES_DIR)
                .append(" (docs/design/rebuild-26/bridges.md).");
        return text.toString();
    }

    static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static Optional<Bridge> reviewed(Map<String, Bridge> bridges, String mod) {
        return Optional.ofNullable(bridges.get(mod)).filter(Bridge::reviewed);
    }
}
