package com.customdimensions.core.definition;

/** Sky, fog, light and time: written as environment attributes and a timeline. */
public record Atmosphere(String skyColor, String fogColor, Double ambientLight, Long fixedTime, String effects) {
}
