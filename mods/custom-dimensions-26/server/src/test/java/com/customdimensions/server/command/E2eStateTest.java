package com.customdimensions.server.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.BlockPos;
import com.customdimensions.server.portal.PortalJournal;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class E2eStateTest {
    private static final BlockPos LANDING = new BlockPos(4, -60, 4);

    private static E2eState.Player rig() {
        return new E2eState.Player("Rig", true, "00000000-0000-0000-0000-000000000001", "adventure:e2e_one",
                new double[] {5.5, -60.0, 1.25}, new BlockPos(5, -60, 1), 0.1f, 8f, true, "minecraft:flint_and_steel", 0,
                true, new E2eState.View(true, false, 12, 48), 31L,
                new E2eState.Feed(LANDING, "adventure:e2e_one", "adventure:e2e_two", LANDING, 25,
                        List.of(new E2eState.SeenEntity(9, "minecraft:arrow", 4.5, -59, 6),
                                new E2eState.SeenEntity(3, "minecraft:zombie", 3.5, -60, 7)), 400), true);
    }

    private static E2eState.Zone litSource() {
        return new E2eState.Zone("source", "adventure:e2e_one", "adventure:e2e_two", "e2e_two", "X",
                List.of(new BlockPos(5, -59, 4), LANDING), true, true, true,
                List.of(new E2eState.Cell(LANDING, "Block{customdimensions:portal}[axis=x]",
                        "Block{minecraft:nether_portal}[axis=x]",
                        "Block{minecraft:tripwire}[attached=false,disarmed=true,east=true,north=false,powered=true,south=false,west=true]",
                        "#8932B8")),
                null, null, new E2eState.Aura(true, 12L, 64, new PortalJournal.AuraPasses(3, 12, 1, 380)),
                new PortalJournal.Particles(7, 390, "minecraft:dust", 0x8844FF, Set.of("VanillaGate", "Rig")));
    }

    private static E2eState.State state(List<E2eState.Player> players, List<E2eState.Zone> zones) {
        PortalJournal.Ignition lit = new PortalJournal.Ignition("Rig", "e2e_two", "adventure:e2e_one",
                "adventure:e2e_two", "X", LANDING, 6, 120);
        PortalJournal.Traversal crossed = new PortalJournal.Traversal("Rig", "adventure:e2e_one", "adventure:e2e_two",
                LANDING, new BlockPos(0, 70, 0), false, true, 200);
        PortalJournal.Title title = new PortalJournal.Title("Rig", "adventure:e2e_one", LANDING, "adventure:e2e_two",
                "E2e Two", 110);
        return new E2eState.State("2026-11-10T12:00:00.123456Z", new E2eState.Server(4000, 12.5, 20.0, "0.1.0", "2f9e67550b1b7d916b447bf40253a28a92eac15a"),
                players, List.of(new E2eState.Dimension("e2e_two", "adventure:e2e_two", true, "active", true, 0, 9),
                        new E2eState.Dimension("overworld", "minecraft:overworld", false, "base", true, 1, 400)),
                zones, List.of(lit), List.of(crossed), List.of(title), List.of(lit), List.of(crossed));
    }

    private static JsonObject parse(E2eState.State s) {
        return JsonParser.parseString(E2eState.render(s)).getAsJsonObject();
    }

    @Test
    void theTopLevelCarriesEveryKeyTheHarnessReads() {
        JsonObject o = parse(state(List.of(rig()), List.of(litSource())));
        for (String key : List.of("kind", "generatedAt", "server", "players", "dimensions", "zoneScope", "zones",
                "ignitions", "traversals")) {
            assertTrue(o.has(key), key);
        }
        assertEquals("e2e-state", o.get("kind").getAsString());
        assertEquals("4000", o.getAsJsonObject("server").get("tick").toString());
        assertEquals("12.5", o.getAsJsonObject("server").get("msPerTick").toString());
        assertEquals("20", o.getAsJsonObject("server").get("tps").toString());
        JsonObject first = o.getAsJsonArray("dimensions").get(0).getAsJsonObject();
        assertEquals("adventure:e2e_two", first.get("id").getAsString());
        assertTrue(first.get("managed").getAsBoolean());
        assertTrue(first.get("open").getAsBoolean());
    }

    @Test
    void anOnlinePlayerCarriesTheOneTwentyOneKeysAndThePortalFacts() {
        JsonObject p = parse(state(List.of(rig()), List.of())).getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals("adventure:e2e_one", p.get("dimension").getAsString());
        assertEquals("[5.5,-60,1.25]", p.get("pos").toString());
        assertEquals("[5,-60,1]", p.get("blockPos").toString());
        assertEquals("0.1", p.get("yaw").toString());
        assertEquals("8", p.get("pitch").toString());
        assertEquals("minecraft:flint_and_steel", p.get("mainHandItem").getAsString());
        assertTrue(p.get("companion").getAsBoolean());
        assertTrue(p.get("serverPack").getAsBoolean());
        assertEquals("31", p.get("companionPayloads").toString());
        assertEquals("48", p.getAsJsonObject("view").get("viewDepth").toString());
        JsonObject feed = p.getAsJsonObject("feed");
        assertEquals("adventure:e2e_two", feed.get("destination").getAsString());
        assertEquals("25", feed.get("chunksSent").toString());
        JsonArray entities = feed.getAsJsonArray("entities");
        assertEquals("minecraft:zombie", entities.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("minecraft:arrow", entities.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals("e2e_two", p.getAsJsonObject("lastIgnition").get("definition").getAsString());
        assertTrue(p.getAsJsonObject("lastTraversal").get("preloadedSent").getAsBoolean());
        assertEquals("adventure:e2e_two", p.getAsJsonObject("lastTraversal").get("to").getAsString());
        assertEquals("E2e Two", p.getAsJsonObject("title").get("text").getAsString());
    }

    @Test
    void anOfflinePlayerIsEveryKeyWithNullValues() {
        JsonObject online = parse(state(List.of(rig()), List.of())).getAsJsonArray("players").get(0).getAsJsonObject();
        JsonObject p = parse(state(List.of(E2eState.Player.offline("Nobody")), List.of()))
                .getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals(online.keySet(), p.keySet());
        assertFalse(p.get("online").getAsBoolean());
        for (String key : p.keySet()) {
            if (!key.equals("name") && !key.equals("online")) {
                assertTrue(p.get(key).isJsonNull(), key);
            }
        }
    }

    @Test
    void aHalfFilledPlayerCannotBeBuilt() {
        assertThrows(IllegalArgumentException.class, () -> new E2eState.Player("Half", true, "u", "w", null, null,
                null, null, null, null, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new E2eState.Player("Half", false, "u", null, null, null,
                null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void aResidentZoneCarriesItsCellsAndPresentation() {
        JsonObject z = parse(state(List.of(), List.of(litSource()))).getAsJsonArray("zones").get(0).getAsJsonObject();
        assertEquals("source", z.get("kind").getAsString());
        assertEquals("adventure:e2e_two", z.get("targetWorld").getAsString());
        assertTrue(z.get("frameStands").getAsBoolean());
        assertTrue(z.get("lit").getAsBoolean());
        assertEquals("[[4,-60,4],[5,-59,4]]", z.get("interior").toString());
        JsonObject cell = z.getAsJsonArray("cells").get(0).getAsJsonObject();
        assertEquals("Block{minecraft:nether_portal}[axis=x]", cell.get("clientSees").getAsString());
        assertEquals("Block{minecraft:tripwire}[attached=false,disarmed=true,east=true,north=false,powered=true,south=false,west=true]",
                cell.get("packSees").getAsString());
        assertEquals("#8932B8", cell.get("tint").getAsString());
        JsonObject particles = z.getAsJsonObject("particles");
        assertEquals("#8844FF", particles.get("color").getAsString());
        assertEquals("[\"Rig\",\"VanillaGate\"]", particles.get("recipients").toString());
        assertEquals("3", z.getAsJsonObject("aura").get("passes").toString());
        assertTrue(z.get("returnTo").isJsonNull());
    }

    @Test
    void aColdZoneHasNoVerdictsAndAVerdictNeedsResidency() {
        E2eState.Zone cold = new E2eState.Zone("arrival", "adventure:e2e_two", "adventure:e2e_one", "e2e_two", "X",
                List.of(LANDING), false, null, null, null, null,
                new E2eState.ReturnTo("adventure:e2e_one", null, 4, 4), null, null);
        JsonObject z = parse(state(List.of(), List.of(cold))).getAsJsonArray("zones").get(0).getAsJsonObject();
        assertTrue(z.get("frameStands").isJsonNull());
        assertTrue(z.get("lit").isJsonNull());
        assertTrue(z.get("cells").isJsonNull());
        assertEquals("[4,4]", z.getAsJsonObject("returnTo").get("sourceColumn").toString());
        assertThrows(IllegalArgumentException.class, () -> new E2eState.Zone("source", "w", "t", null, "X",
                List.of(LANDING), false, true, null, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new E2eState.Zone("source", "w", "t", null, "X",
                List.of(LANDING), true, true, true, null, null, null, null, null));
    }

    @Test
    void twoRendersOfOneStateAreIdenticalWhateverTheInputOrder() {
        E2eState.Zone other = new E2eState.Zone("arrival", "adventure:e2e_one", "adventure:e2e_two", null, "Y",
                List.of(new BlockPos(-3, 70, -3)), false, null, null, null, null, null, null, null);
        String a = E2eState.render(state(List.of(rig(), E2eState.Player.offline("Ann")), List.of(litSource(), other)));
        String b = E2eState.render(state(List.of(E2eState.Player.offline("Ann"), rig()), List.of(other, litSource())));
        assertEquals(a, b);
        JsonObject o = JsonParser.parseString(a).getAsJsonObject();
        assertEquals("Ann", o.getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("arrival", o.getAsJsonArray("zones").get(0).getAsJsonObject().get("kind").getAsString());
    }

    @Test
    void numbersCarryNoTrailingZeroAndRefuseNonFinite() {
        assertEquals("130", E2eState.num(130.0).toString());
        assertEquals("-0.5", E2eState.num(-0.5).toString());
        assertEquals("0", E2eState.num(-0.0001).toString());
        assertEquals("1.235", E2eState.num(1.23456).toString());
        assertEquals("1000000", E2eState.num(1.0e6).toString());
        assertThrows(IllegalArgumentException.class, () -> E2eState.num(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> E2eState.angle(Float.POSITIVE_INFINITY));
    }
}
