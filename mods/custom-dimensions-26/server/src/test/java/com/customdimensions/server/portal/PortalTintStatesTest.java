package com.customdimensions.server.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.core.portal.PortalTints;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

/** The tint switch, and the pack the jar carries against the 26.3 tripwire block. */
class PortalTintStatesTest {
    private static final Path VANILLA = Path.of(System.getProperty("customdimensions.vanilla"));
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000a11e");

    private static Optional<MinecraftServer.ServerResourcePackInfo> offered(String hash) {
        return Optional.of(new MinecraftServer.ServerResourcePackInfo(ID, "http://pack.example/server-pack/portals.zip",
                hash, false, null));
    }

    @Test
    void tintsArmOnlyForThisJarsPack() {
        assertEquals(ID, PortalTintStates.armedFor(offered("ab12"), "ab12"));
        assertEquals(ID, PortalTintStates.armedFor(offered("AB12"), "ab12"));
        assertNull(PortalTintStates.armedFor(offered("cd34"), "ab12"));
        assertNull(PortalTintStates.armedFor(offered(""), "ab12"));
        assertNull(PortalTintStates.armedFor(offered(null), "ab12"));
        assertNull(PortalTintStates.armedFor(offered("ab12"), null));
        assertNull(PortalTintStates.armedFor(Optional.empty(), "ab12"));
    }

    @Test
    void theJarCarriesThePackAndItHashes() throws IOException {
        InputStream in = PortalTintStatesTest.class.getClassLoader().getResourceAsStream(PortalTints.PACK_RESOURCE);
        assertNotNull(in, PortalTints.PACK_RESOURCE);
        String sha1 = PortalTintStates.sha1(in);
        assertNotNull(sha1);
        assertEquals(40, sha1.length());
    }

    /** Every variant key names each of the 26.3 tripwire's properties with a value it has. */
    @Test
    void thePacksTripwireKeysAreThe26_3BlocksStates() throws IOException {
        JsonObject report = JsonParser.parseString(Files.readString(VANILLA.resolve("generated/reports/blocks.json")))
                .getAsJsonObject().getAsJsonObject("minecraft:tripwire").getAsJsonObject("properties");
        Map<String, Set<String>> properties = new TreeMap<>();
        report.entrySet().forEach(e -> {
            Set<String> values = new HashSet<>();
            e.getValue().getAsJsonArray().forEach(v -> values.add(v.getAsString()));
            properties.put(e.getKey(), values);
        });
        assertEquals(Set.of("attached", "disarmed", "east", "north", "powered", "south", "west"), properties.keySet());
        JsonObject variants = JsonParser.parseString(new String(packEntry("assets/minecraft/blockstates/tripwire.json"),
                StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("variants");
        assertEquals(128, variants.size());
        for (String key : variants.keySet()) {
            Map<String, String> parsed = new TreeMap<>();
            for (String pair : key.split(",")) {
                String[] kv = pair.split("=");
                parsed.put(kv[0], kv[1]);
            }
            assertEquals(properties.keySet(), parsed.keySet(), key);
            parsed.forEach((k, v) -> assertTrue(properties.get(k).contains(v), key));
        }
        for (PortalTints.Plane plane : PortalTints.Plane.values()) {
            for (int tint = 0; tint < PortalTints.COUNT; tint++) {
                assertEquals(PortalTints.model(plane, tint), variants.getAsJsonObject(PortalTints.state(plane, tint).variant())
                        .get("model").getAsString());
            }
        }
    }

    @Test
    void axesMapToTheirPlanes() {
        assertEquals(PortalTints.Plane.X, PortalTintStates.plane(net.minecraft.core.Direction.Axis.X));
        assertEquals(PortalTints.Plane.Z, PortalTintStates.plane(net.minecraft.core.Direction.Axis.Z));
        assertEquals(PortalTints.Plane.Y, PortalTintStates.plane(net.minecraft.core.Direction.Axis.Y));
    }

    private static byte[] packEntry(String name) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(
                PortalTintStatesTest.class.getClassLoader().getResourceAsStream(PortalTints.PACK_RESOURCE))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().equals(name)) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IOException("the pack has no " + name);
    }
}
