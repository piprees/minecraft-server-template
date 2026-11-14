package com.customdimensions.server.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class SavedLevelTest {
    @Test
    void aPlayerSavedInALazyLevelThatIsNotOpenHasItOpenedFirst() {
        assertEquals(SavedLevel.Placement.OPEN_FIRST, SavedLevel.decide(true, false, true));
    }

    @Test
    void anOpenLevelOrNoSavedLevelPlacesAsSaved() {
        assertEquals(SavedLevel.Placement.AS_SAVED, SavedLevel.decide(true, true, true));
        assertEquals(SavedLevel.Placement.AS_SAVED, SavedLevel.decide(true, true, false));
        assertEquals(SavedLevel.Placement.AS_SAVED, SavedLevel.decide(false, false, false));
    }

    @Test
    void aSavedLevelThatIsNeitherOpenNorLazyGoesToTheWorldSpawn() {
        assertEquals(SavedLevel.Placement.WORLD_SPAWN, SavedLevel.decide(true, false, false));
    }

    @Test
    void theSavedDimensionIsReadFromThePlayerData() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", "adventure:e2e_two");
        assertEquals("adventure:e2e_two", SavedLevel.savedDimension(Optional.of(tag)).orElseThrow().identifier().toString());
        assertTrue(SavedLevel.savedDimension(Optional.of(new CompoundTag())).isEmpty());
        assertTrue(SavedLevel.savedDimension(Optional.empty()).isEmpty());
    }
}
