package com.customdimensions.client.realtime;

import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;

/**
 * A camera standing in the destination at the viewer's corresponding position.
 *
 * <p>{@code Camera.setPos} is protected, which is the only reason this class
 * exists. {@code update} keeps the player as the focused entity — an arrival
 * inherits the source axis and there is no rotation, so the player's own yaw
 * and pitch are already the ones wanted — and the position is then overwritten
 * with the translated one. A null focused entity is an NPE inside
 * {@code update}, not a no-op.
 */
public final class SpectatorCamera extends Camera {

    /** Places the camera in {@code area} at the translated eye position. */
    public void standIn(BlockView area, Entity focus, float tickDelta,
            double x, double y, double z) {
        update(area, focus, false, false, tickDelta);
        setPos(x, y, z);
    }
}
