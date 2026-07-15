package com.customdimensions.mixin;

import java.util.Optional;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(StructurePlacement.class)
public interface StructurePlacementAccessor {
    @Accessor("locateOffset")
    Vec3i getLocateOffsetField();

    @Accessor("frequencyReductionMethod")
    StructurePlacement.FrequencyReductionMethod getFrequencyReductionMethodField();

    @Accessor("frequency")
    float getFrequencyField();

    @Accessor("salt")
    int getSaltField();

    @Accessor("exclusionZone")
    Optional<StructurePlacement.ExclusionZone> getExclusionZoneField();
}
