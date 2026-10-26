package com.customdimensions.core.messages;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Player-facing strings from {@code config/messages.json}, with built-in
 * defaults for a key the file lacks or a missing file. Templates use the
 * file's {@code {variable}} syntax; an unknown variable stays as written.
 */
public final class Messages {
    public static final String PORTAL_NO_SITE = "portal.no_site";
    public static final String WIN_EARNED = "win.earned";

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(PORTAL_NO_SITE, "The portal cannot find anywhere safe to put you."),
            Map.entry(WIN_EARNED, "§6{player} §rearned §e{win}§r in {dimension}."),
            Map.entry("customdim.no_manager", "The level manager has not started."),
            Map.entry("customdim.unknown", "There is no dimension called {dimension}."),
            Map.entry("customdim.write_failed", "Could not write {path}: {reason}"),
            Map.entry("customdim.list.header", "{count} dimensions: {base} base, {lazy} lazy, {open} open."),
            Map.entry("customdim.list.line", "{dimension}: {state}, {players} players, {chunks} chunks loaded"),
            Map.entry("customdim.info.header", "{dimension} ({state})"),
            Map.entry("customdim.info.generator", "Generator: {generator}"),
            Map.entry("customdim.info.seed", "Seed: {seed}"),
            Map.entry("customdim.info.height", "Height: y {minY} to {maxY} ({height} blocks)"),
            Map.entry("customdim.info.difficulty", "Difficulty: mobs x{mobs}, player luck x{luck}, depth scaling {depth}, hostile spawning {hostile}"),
            Map.entry("customdim.info.peaceful", "Peaceful: {peaceful}"),
            Map.entry("customdim.info.portals", "Portals: {count} {portals}"),
            Map.entry("customdim.info.exits", "Exits: {exits}"),
            Map.entry("customdim.info.wins", "Wins: {wins}"),
            Map.entry("customdim.info.borders", "Borders: player {player}, generation {generation}"),
            Map.entry("customdim.info.bedrock", "Bedrock: {warning}"),
            Map.entry("customdim.tp.console", "Name a player to teleport from the console."),
            Map.entry("customdim.tp.done", "Teleported {player} to {dimension}."),
            Map.entry("customdim.activate.started", "Opening {dimension}..."),
            Map.entry("customdim.activate.done", "{dimension} is open ({millis} ms)."),
            Map.entry("customdim.activate.failed", "Could not open {dimension}: {reason}"),
            Map.entry("customdim.base_world", "{dimension} is a base world; it is always loaded."),
            Map.entry("customdim.unload.done", "{dimension} unloaded after {ticks} ticks."),
            Map.entry("customdim.unload.failed", "Could not unload {dimension}: {reason}"),
            Map.entry("customdim.delete.base", "{dimension} is a base world and cannot be deleted."),
            Map.entry("customdim.delete.preview", "This deletes {dimension}'s level folder {path} and forgets its portals; the definition stays. Run /customdim delete {dimension} confirm to do it."),
            Map.entry("customdim.delete.done", "{dimension} deleted."),
            Map.entry("customdim.delete.failed", "Could not delete {dimension}: {reason}"),
            Map.entry("customdim.create.invalid_slug", "{slug} is not a valid name: use 1 to 64 lower-case letters, digits and underscores."),
            Map.entry("customdim.create.exists", "A dimension called {slug} already exists."),
            Map.entry("customdim.create.unknown_template", "There is no definition called {template} to copy."),
            Map.entry("customdim.create.invalid", "The new definition for {slug} does not validate: {reason}"),
            Map.entry("customdim.create.done", "Wrote {path} from {template} with seed {seed} and portal colour {colour}. {slug} opens after the next restart."),
            Map.entry("customdim.audit.done", "Audit written to {path}: {stale} stale, {missing} missing, {removed} removed, {orphans} orphans, {links} broken portal links, {ids} unknown ids, {bedrock} Bedrock warnings, {warnings} compile warnings."),
            Map.entry("customdim.catalogue.done", "Catalogue written to {path}: {blocks} blocks, {items} items, {biomes} biomes, {structures} structures, {features} features, {entities} entity types, {tags} tags."),
            Map.entry("customdim.render.started", "Rendering {dimension} with seed {seed}: radius {radius}, step {step}, {side}x{side} pixels on {threads} threads."),
            Map.entry("customdim.render.progress", "Rendering {dimension}: {percent}%."),
            Map.entry("customdim.render.done", "Rendered {dimension} seed {seed} in {seconds} s ({rate} columns/s, {biomes} biomes) to {path}."),
            Map.entry("customdim.render.busy", "A render of {dimension} is running ({percent}%); one render runs at a time."),
            Map.entry("customdim.render.radius", "Radius {radius} is outside {min} to {max}."),
            Map.entry("customdim.render.step", "Step {step} is too fine for radius {radius}: the smallest is {min}, which keeps the image at {side} pixels a side or fewer."),
            Map.entry("customdim.render.failed", "Could not render {dimension}: {reason}"),
            Map.entry("customdim.roll.started", "Rolling {count} seeds of {dimension}: a {side}x{side} disc of radius {radius} (step {step}) and structure starts out to {structureRadius} blocks, on {threads} threads."),
            Map.entry("customdim.roll.progress", "Rolling {dimension}: {percent}%."),
            Map.entry("customdim.roll.done", "Rolled {count} seeds of {dimension} in {seconds} s ({rate} columns/s); the best is seed {seed} at {score}. Contact sheet: {path}"),
            Map.entry("customdim.roll.busy", "A render or roll of {dimension} is running ({percent}%); one runs at a time."),
            Map.entry("customdim.roll.count", "Count {count} is outside 1 to {max}."),
            Map.entry("customdim.roll.skip", "{dimension} sets roll.skip, so it is not rolled."),
            Map.entry("customdim.roll.has_chunks", "{dimension} already has generated chunks in {path}. A seed only applies before a dimension is first created (D2): reset it by deleting that folder with the server stopped, then roll or pick."),
            Map.entry("customdim.roll.failed", "Could not roll {dimension}: {reason}"),
            Map.entry("customdim.pick.done", "Wrote seed {seed} into {path}; {dimension} uses it after the next restart."),
            Map.entry("customdim.pick.no_rank", "The last roll of {dimension} has no rank {rank}; roll it first."),
            Map.entry("customdim.pick.no_file", "{dimension} has no definition file at {path} to write the seed into."));

    private final Map<String, String> entries;

    private Messages(Map<String, String> entries) {
        this.entries = entries;
    }

    /** Every key with a built-in default. */
    public static java.util.Set<String> defaultKeys() {
        return DEFAULTS.keySet();
    }

    public static Messages defaults() {
        return new Messages(Map.of());
    }

    /** Reads {@code file}; a missing or unreadable file gives the defaults. */
    public static Messages load(Path file) {
        if (!Files.isRegularFile(file)) return defaults();
        try {
            return parse(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            return defaults();
        }
    }

    public static Messages parse(String json) {
        Map<String, String> out = new HashMap<>();
        JsonElement root = JsonParser.parseString(json);
        if (root.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : ((JsonObject) root).entrySet()) {
                if (!e.getKey().startsWith("_") && e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
        }
        return new Messages(out);
    }

    /** The template for {@code key}: the file's, the built-in default, else the key itself. */
    public String template(String key) {
        String own = entries.get(key);
        if (own != null) return own;
        return DEFAULTS.getOrDefault(key, key);
    }

    public String format(String key, Map<String, String> variables) {
        String text = template(key);
        for (Map.Entry<String, String> v : variables.entrySet()) {
            text = text.replace("{" + v.getKey() + "}", v.getValue());
        }
        return text;
    }
}
