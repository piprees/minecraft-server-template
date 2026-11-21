package com.customdimensions.client.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.customdimensions.client.CompanionPayloads;
import java.util.List;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FeedTest {
    static final Identifier ASHGROVE = Identifier.fromNamespaceAndPath("adventure", "the_ashgrove");
    static final BlockPos ONE = new BlockPos(10, 64, 0);
    static final BlockPos TWO = new BlockPos(40, 64, 0);

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @BeforeEach
    void empty() {
        Feed.reset();
    }

    static CompanionPayloads.PortalFrame frame(BlockPos origin) {
        return new CompanionPayloads.PortalFrame(ASHGROVE, ASHGROVE, origin, List.of(origin), 0, 3, 0, 0, 0, -1, -1);
    }

    static ClientboundAddEntityPacket zombie(int id, double x) {
        return new ClientboundAddEntityPacket(id, new UUID(id, id), x, 70, 5, 0f, 0f, EntityTypes.ZOMBIE, 0, Vec3.ZERO, 0);
    }

    @Test
    void framesAreKeyedByOriginAndAReframeReplaces() {
        Feed.frame(frame(ONE));
        Feed.frame(frame(ONE));
        Feed.frame(frame(TWO));
        assertEquals(2, Feed.frames().size());
    }

    @Test
    void snapshotsAddMoveAndDropEntities() {
        Feed.entities(new CompanionPayloads.DestinationEntities(ASHGROVE, List.of(zombie(1, 0), zombie(2, 3)), List.of(),
                new int[0]));
        Feed.entities(new CompanionPayloads.DestinationEntities(ASHGROVE, List.of(zombie(2, 9)), List.of(), new int[] {1}));
        Feed.Destination d = Feed.destination(ASHGROVE);
        assertEquals(1, d.entities.size());
        assertEquals(9, d.entities.get(2).x());
        assertEquals("minecraft:zombie", d.entities.get(2).type().toString());
        Feed.handover(new CompanionPayloads.EntityHandover(ASHGROVE, 77, 5L, zombie(3, 1), List.of()));
        assertEquals(2, Feed.entitiesHeld());
    }

    @Test
    void eachEntityKeepsItsSpawnPacketAndItsOwnSynchedData() {
        ClientboundAddEntityPacket arrow = new ClientboundAddEntityPacket(5, new UUID(5, 5), 1, 2, 3, 11.25f, 22.5f,
                EntityTypes.ARROW, 0, new Vec3(1.5, 0.25, -0.5), 0);
        List<SynchedEntityData.DataValue<?>> noGravity =
                List.of(SynchedEntityData.DataValue.create(new EntityDataAccessor<>(5, EntityDataSerializers.BOOLEAN), true));
        Feed.entities(new CompanionPayloads.DestinationEntities(ASHGROVE, List.of(arrow, zombie(6, 0)),
                List.of(new ClientboundSetEntityDataPacket(5, noGravity)), new int[0]));
        Feed.Shown shown = Feed.destination(ASHGROVE).entities.get(5);
        assertEquals(new Vec3(1.5, 0.25, -0.5), shown.packet().getMovement(), "the velocity rides in the packet");
        assertEquals(22.5f, shown.yRot(), "angles ride as bytes");
        assertEquals(11.25f, shown.xRot());
        assertEquals(1, shown.data().size());
        assertTrue(Feed.destination(ASHGROVE).entities.get(6).data().isEmpty(), "data is matched by id");
        Feed.handover(new CompanionPayloads.EntityHandover(ASHGROVE, 77, 5L, zombie(8, 1),
                List.of(new ClientboundSetEntityDataPacket(8, noGravity))));
        assertEquals(1, Feed.destination(ASHGROVE).entities.get(8).data().size());
    }

    @Test
    void clearingTheLastFrameToADestinationDropsItsData() {
        Feed.frame(frame(ONE));
        Feed.frame(frame(TWO));
        Feed.entities(new CompanionPayloads.DestinationEntities(ASHGROVE, List.of(zombie(1, 0)), List.of(), new int[0]));
        Feed.clear(new CompanionPayloads.ProjectionClear(ONE));
        assertNotNull(Feed.destination(ASHGROVE), "another opening still leads there");
        Feed.clear(new CompanionPayloads.ProjectionClear(TWO));
        assertNull(Feed.destination(ASHGROVE));
        assertTrue(Feed.frames().isEmpty());
        Feed.clear(new CompanionPayloads.ProjectionClear(new BlockPos(1, 2, 3)));
        assertTrue(Feed.frames().isEmpty(), "a clear for an unknown opening is harmless");
    }
}
