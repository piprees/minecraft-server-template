package com.customdimensions.command;

import com.customdimensions.facts.Measured;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /customdim e2e-state} promises an assertion: every fact is a
 * typed JSON value or an explicit absence, never a sentence.
 *
 * <p>The harness read a player's Z with {@code data get entity} and parsed
 * {@code "No entity was found"} as the coordinate. Nothing here can do that:
 * an offline player is {@code "online": false} with null facts, and the
 * records refuse to hold half of a player or half of a zone.
 */
class E2eStateTest {

    private static final String HEADER = "{\n \"kind\": \"e2e-state\",\n";

    private static E2eState.PlayerState pip() {
        return E2eState.PlayerState.online("pip", "0a1b-2c3d", "adventure:the_gauntlet",
                1.5, 64.0, -3.5, new E2eState.Pos(1, 64, -4), 90.0f, -12.0f, true,
                "minecraft:flint_and_steel", 0);
    }

    private static E2eState.ServerState server() {
        return new E2eState.ServerState(12345, Measured.of(45_000_000L),
                E2eState.tpsFrom(45_000_000L), "5.1.0");
    }

    private static E2eState.State stateWith(List<E2eState.PlayerState> players) {
        return new E2eState.State(server(), players, List.of(), "every loaded world", List.of());
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static JsonObject onlyPlayer(E2eState.PlayerState player) {
        JsonArray players = parse(E2eState.render(HEADER, stateWith(List.of(player))))
                .getAsJsonArray("players");
        assertEquals(1, players.size());
        return players.get(0).getAsJsonObject();
    }

    // ---------------------------------------------------------------
    // The defect: a player who is not there
    // ---------------------------------------------------------------

    @Test
    void anOfflinePlayerIsSaidToBeOffline() {
        assertFalse(onlyPlayer(E2eState.PlayerState.offline("ghost")).get("online").getAsBoolean());
    }

    @Test
    void anOfflinePlayersPositionIsPresentAndNull() {
        // Not an absent key: a test asking for .pos must get null, so that
        // reading it as a coordinate fails instead of yielding a sentence.
        JsonObject ghost = onlyPlayer(E2eState.PlayerState.offline("ghost"));
        assertTrue(ghost.has("pos"));
        assertTrue(ghost.get("pos").isJsonNull());
    }

    @Test
    void anOfflinePlayerNamesEveryFactItCannotGive() {
        JsonObject ghost = onlyPlayer(E2eState.PlayerState.offline("ghost"));
        for (String key : List.of("uuid", "dimension", "pos", "blockPos", "yaw", "pitch",
                "onGround", "mainHandItem", "portalCooldown")) {
            assertTrue(ghost.has(key), key + " is missing entirely");
            assertTrue(ghost.get(key).isJsonNull(), key + " should be null for an offline player");
        }
    }

    @Test
    void noFactIsEverAnErrorString() {
        // The exact failure this replaces: z came back as "No entity was found".
        JsonObject ghost = onlyPlayer(E2eState.PlayerState.offline("ghost"));
        assertFalse(ghost.get("pos").isJsonPrimitive());
    }

    @Test
    void anOnlinePlayersCoordinatesAreNumbers() {
        JsonArray pos = onlyPlayer(pip()).getAsJsonArray("pos");
        assertEquals(3, pos.size());
        assertEquals(-3.5, pos.get(2).getAsDouble());
        assertTrue(pos.get(2).getAsJsonPrimitive().isNumber());
    }

    @Test
    void anOnlinePlayersBlockPositionIsAThreeNumberArray() {
        JsonArray blockPos = onlyPlayer(pip()).getAsJsonArray("blockPos");
        assertEquals(List.of(1, 64, -4),
                List.of(blockPos.get(0).getAsInt(), blockPos.get(1).getAsInt(),
                        blockPos.get(2).getAsInt()));
    }

    @Test
    void anAngleIsWrittenAtItsOwnPrecisionNotADoublesViewOfIt() {
        // 90f widens cleanly; the server's real angles do not. Widening
        // -12.4f to a double writes -12.399999618530273, which is the error
        // of the widening rather than anything the server measured.
        assertEquals("-12.4", E2eState.angleJson(-12.4f));
        assertEquals("-12.0", onlyPlayer(pip()).get("pitch").getAsString());
    }

    @Test
    void aNonFiniteAngleIsRefusedRatherThanWrittenAsJson() {
        // NaN and Infinity are not JSON literals; writing one makes the whole
        // artefact unparseable, which is worse than failing the command.
        assertThrows(IllegalArgumentException.class, () -> E2eState.angleJson(Float.NaN));
    }

    @Test
    void anOnlinePlayerCarriesTheRestOfItsFacts() {
        JsonObject player = onlyPlayer(pip());
        assertEquals("adventure:the_gauntlet", player.get("dimension").getAsString());
        assertEquals("minecraft:flint_and_steel", player.get("mainHandItem").getAsString());
        assertEquals(0, player.get("portalCooldown").getAsInt());
        assertTrue(player.get("onGround").getAsBoolean());
    }

    @Test
    void aPlayerCannotBeOnlineWithFactsMissing() {
        assertThrows(IllegalArgumentException.class, () -> new E2eState.PlayerState(
                "pip", true, "0a1b", "adventure:x", 1.0, 2.0, null,
                new E2eState.Pos(1, 2, 3), 0.0f, 0.0f, true, "minecraft:air", 0));
    }

    @Test
    void aPlayerCannotBeOfflineAndStillCarryAPosition() {
        assertThrows(IllegalArgumentException.class, () -> new E2eState.PlayerState(
                "ghost", false, null, null, 1.0, 2.0, 3.0,
                null, null, null, null, null, null));
    }

    @Test
    void playersAreRenderedInNameOrderWhateverOrderTheyArriveIn() {
        E2eState.PlayerState a = E2eState.PlayerState.offline("aaron");
        E2eState.PlayerState z = E2eState.PlayerState.offline("zoe");

        String forwards = E2eState.render(HEADER, stateWith(List.of(a, z)));
        String backwards = E2eState.render(HEADER, stateWith(List.of(z, a)));

        assertEquals(forwards, backwards);
        assertEquals("aaron", parse(forwards).getAsJsonArray("players")
                .get(0).getAsJsonObject().get("name").getAsString());
    }

    // ---------------------------------------------------------------
    // Zones: "the frame is down" and "nobody can tell" are different
    // ---------------------------------------------------------------

    private static E2eState.ZoneState zone(boolean resident, Boolean frameStands) {
        return new E2eState.ZoneState("source", "minecraft:overworld", "adventure:the_gauntlet",
                "X", List.of(new E2eState.Pos(1, 64, -4)), resident, frameStands);
    }

    private static JsonObject onlyZone(E2eState.ZoneState zoneState) {
        JsonArray zones = parse(E2eState.render(HEADER, new E2eState.State(
                server(), List.of(), List.of(), "every loaded world", List.of(zoneState))))
                .getAsJsonArray("zones");
        assertEquals(1, zones.size());
        return zones.get(0).getAsJsonObject();
    }

    @Test
    void aColdZoneSaysItCouldNotTellRatherThanSayingNo() {
        JsonObject cold = onlyZone(zone(false, null));
        assertFalse(cold.get("resident").getAsBoolean());
        assertTrue(cold.get("frameStands").isJsonNull());
    }

    @Test
    void aResidentZoneWithABrokenFrameSaysSo() {
        JsonObject broken = onlyZone(zone(true, false));
        assertTrue(broken.get("resident").getAsBoolean());
        assertFalse(broken.get("frameStands").getAsBoolean());
    }

    @Test
    void aColdZoneCannotClaimAVerdictOnItsFrame() {
        assertThrows(IllegalArgumentException.class, () -> zone(false, true));
        assertThrows(IllegalArgumentException.class, () -> zone(false, false));
    }

    @Test
    void aResidentZoneMustCarryAVerdict() {
        assertThrows(IllegalArgumentException.class, () -> zone(true, null));
    }

    @Test
    void aZoneCarriesWhereItIsAndWhereItLeads() {
        JsonObject entry = onlyZone(zone(true, true));
        assertEquals("source", entry.get("kind").getAsString());
        assertEquals("minecraft:overworld", entry.get("world").getAsString());
        assertEquals("adventure:the_gauntlet", entry.get("targetWorld").getAsString());
        assertEquals("X", entry.get("axis").getAsString());
    }

    @Test
    void aZoneInteriorIsRenderedAsCoordinateTriples() {
        JsonArray interior = onlyZone(new E2eState.ZoneState("arrival", "adventure:x",
                "minecraft:overworld", "Z",
                List.of(new E2eState.Pos(4, 65, 7), new E2eState.Pos(4, 64, 7)), true, true))
                .getAsJsonArray("interior");

        // Sorted by (x, y, z): the lower cell first, whatever order it arrived in.
        assertEquals(64, interior.get(0).getAsJsonArray().get(1).getAsInt());
        assertEquals(65, interior.get(1).getAsJsonArray().get(1).getAsInt());
    }

    // ---------------------------------------------------------------
    // Chunk residency: which chunks the frame check would read
    // ---------------------------------------------------------------

    @Test
    void theChunksReadIncludeTheFrameRingNotJustTheInterior() {
        // Interior on the east edge of chunk 0: the ring reaches x=16, which
        // is chunk 1. Asking only the interior's chunk is how a frame check
        // ends up reading a cold chunk and blocking the tick.
        Set<ChunkPos> chunks = E2eState.chunksRead(
                Set.of(new BlockPos(15, 64, 0)), Direction.Axis.X);

        assertTrue(chunks.contains(new ChunkPos(0, 0)));
        assertTrue(chunks.contains(new ChunkPos(1, 0)));
    }

    @Test
    void theChunksReadFollowTheZonesOwnPlane() {
        // A Z-axis zone's ring runs north/south, so the neighbour it reaches
        // is across z, not across x.
        Set<ChunkPos> chunks = E2eState.chunksRead(
                Set.of(new BlockPos(0, 64, 15)), Direction.Axis.Z);

        assertTrue(chunks.contains(new ChunkPos(0, 1)));
        assertFalse(chunks.contains(new ChunkPos(1, 0)));
    }

    @Test
    void aHorizontalZoneReachesBothHorizontalNeighbours() {
        Set<ChunkPos> chunks = E2eState.chunksRead(
                Set.of(new BlockPos(15, 64, 15)), Direction.Axis.Y);

        assertTrue(chunks.contains(new ChunkPos(1, 0)));
        assertTrue(chunks.contains(new ChunkPos(0, 1)));
    }

    @Test
    void anEmptyInteriorReadsNoChunks() {
        assertTrue(E2eState.chunksRead(Set.of(), Direction.Axis.X).isEmpty());
    }

    // ---------------------------------------------------------------
    // Server facts
    // ---------------------------------------------------------------

    @Test
    void tpsIsCappedAtTwentyForAFastTick() {
        assertEquals(20.0, E2eState.tpsFrom(1_000_000L).orThrow());
    }

    @Test
    void tpsFallsWhenATickTakesLongerThanFiftyMilliseconds() {
        assertEquals(10.0, E2eState.tpsFrom(100_000_000L).orThrow());
    }

    @Test
    void aServerThatHasNotTimedATickReportsNoTpsRatherThanTwenty() {
        // Zero nanos is "not measured yet", and 1e9/0 would read as a
        // perfectly healthy server.
        assertFalse(E2eState.tpsFrom(0L).isPresent());
        assertFalse(E2eState.nanosFrom(0L).isPresent());
    }

    @Test
    void anAbsentServerFactRendersAsAnAbsenceNotANumber() {
        String json = E2eState.render(HEADER, new E2eState.State(
                new E2eState.ServerState(7, E2eState.nanosFrom(0L), E2eState.tpsFrom(0L), "5.1.0"),
                List.of(), List.of(), "every loaded world", List.of()));

        JsonObject serverJson = parse(json).getAsJsonObject("server");
        assertEquals(7, serverJson.get("tick").getAsInt());
        assertTrue(serverJson.getAsJsonObject("tps").has("absent"));
    }

    // ---------------------------------------------------------------
    // Dimensions
    // ---------------------------------------------------------------

    @Test
    void aDimensionSaysWhetherThisModManagesIt() {
        String json = E2eState.render(HEADER, new E2eState.State(server(), List.of(),
                List.of(new E2eState.DimensionState("adventure:the_gauntlet", true,
                                Measured.absent("nothing records a load tick")),
                        new E2eState.DimensionState("minecraft:overworld", false,
                                Measured.absent("nothing records a load tick"))),
                "every loaded world", List.of()));

        JsonArray dimensions = parse(json).getAsJsonArray("dimensions");
        assertEquals("adventure:the_gauntlet",
                dimensions.get(0).getAsJsonObject().get("id").getAsString());
        assertTrue(dimensions.get(0).getAsJsonObject().get("managed").getAsBoolean());
        assertFalse(dimensions.get(1).getAsJsonObject().get("managed").getAsBoolean());
    }

    @Test
    void anUnrecordedLoadTickRendersAsAnAbsenceWithAReason() {
        String json = E2eState.render(HEADER, new E2eState.State(server(), List.of(),
                List.of(new E2eState.DimensionState("adventure:x", true,
                        Measured.absent("nothing records a load tick"))),
                "every loaded world", List.of()));

        JsonObject loadedAtTick = parse(json).getAsJsonArray("dimensions").get(0)
                .getAsJsonObject().getAsJsonObject("loadedAtTick");
        assertEquals("nothing records a load tick", loadedAtTick.get("absent").getAsString());
    }

    // ---------------------------------------------------------------
    // The document
    // ---------------------------------------------------------------

    @Test
    void theDocumentIsWellFormedJsonWithTheHeaderInIt() {
        JsonObject doc = parse(E2eState.render(HEADER, stateWith(List.of(pip()))));
        assertEquals("e2e-state", doc.get("kind").getAsString());
        assertTrue(doc.has("server"));
        assertTrue(doc.has("players"));
        assertTrue(doc.has("dimensions"));
        assertTrue(doc.has("zones"));
    }

    @Test
    void anEmptyServerStillRendersEveryCollection() {
        // "no players online" must be an empty array a test can count, not a
        // missing key it has to guess about.
        JsonObject doc = parse(E2eState.render(HEADER, stateWith(List.of())));
        assertEquals(0, doc.getAsJsonArray("players").size());
        assertEquals(0, doc.getAsJsonArray("dimensions").size());
        assertEquals(0, doc.getAsJsonArray("zones").size());
    }

    @Test
    void theZoneScopeSaysWhichWorldsWereLookedAt() {
        // Zones are held per world; a test must not read an empty list as
        // "this server has no portals".
        assertEquals("every loaded world",
                parse(E2eState.render(HEADER, stateWith(List.of()))).get("zoneScope").getAsString());
    }

    // ---------------------------------------------------------------
    // Where it lands
    // ---------------------------------------------------------------

    @Test
    void theArtefactHasOneFixedNameTheHarnessCanReadWithoutParsingRcon() {
        assertEquals("e2e-state.json",
                E2eState.artefactPath(Path.of("/srv/.seed-rolling")).getFileName().toString());
    }

    @Test
    void theArtefactDoesNotLandAmongTheRollersCandidates() {
        assertNotEquals("candidates",
                E2eState.artefactPath(Path.of("/srv/.seed-rolling"))
                        .getParent().getFileName().toString());
    }
}
