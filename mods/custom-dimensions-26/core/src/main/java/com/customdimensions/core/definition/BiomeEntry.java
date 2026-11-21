package com.customdimensions.core.definition;

import com.google.gson.JsonObject;

/** One entry of {@code biomes}: an id, with a climate override when the file gives one. */
public record BiomeEntry(String id, JsonObject climate) {
}
