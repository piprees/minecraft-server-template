package com.customdimensions.core.definition;

import com.google.gson.JsonElement;

/**
 * The dimension-type fields a definition sets; null keeps the base type's value.
 * {@code monsterSpawnLightLevel} is an int or an int provider object.
 */
public record DimensionTypeFields(Integer minY, Integer height, Integer logicalHeight,
                                  Boolean hasSkylight, Boolean hasCeiling, String infiniburn,
                                  JsonElement monsterSpawnLightLevel, Integer monsterSpawnBlockLightLimit) {
}
