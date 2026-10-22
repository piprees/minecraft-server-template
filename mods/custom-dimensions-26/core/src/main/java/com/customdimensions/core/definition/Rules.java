package com.customdimensions.core.definition;

/** What works in the dimension. Null keeps the base type's behaviour. */
public record Rules(Boolean bedsWork, Boolean respawnAnchorsWork, Boolean piglinsZombify,
                    Boolean ultraWarm, Boolean natural, Boolean raids) {
}
