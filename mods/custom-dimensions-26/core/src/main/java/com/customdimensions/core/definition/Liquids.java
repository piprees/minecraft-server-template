package com.customdimensions.core.definition;

/** Sea level and the fluid that fills below it. Null fields keep the generator's own. */
public record Liquids(Integer seaLevel, String defaultFluid) {
}
