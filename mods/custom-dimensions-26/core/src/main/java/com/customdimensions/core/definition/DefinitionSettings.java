package com.customdimensions.core.definition;

import com.google.gson.JsonObject;

/**
 * Schema v2 {@code settings.json}: the namespace, idle unload, vanilla frame
 * blocks, the defaults merged under every definition, and the global
 * suppress lists. {@code defaults} and {@code suppress} are empty when absent.
 */
public record DefinitionSettings(int schemaVersion, String namespace, Integer idleUnloadMinutes,
                                 JsonObject frames, JsonObject defaults, JsonObject suppress) {
}
