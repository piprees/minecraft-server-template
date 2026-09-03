package com.customdimensions.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.lwjgl.opengl.GL11;

/**
 * The layer that opens the portal.
 *
 * <p>A quad drawn at the far end of the cone through the opening, with the
 * depth test forced to always pass and depth writing on. It does two jobs at
 * once: it paints the destination's own sky and fog colour behind whatever
 * geometry follows, and it resets the depth buffer inside the opening so the
 * source world's blocks BEHIND the frame — a portal cut into a hillside is
 * looking at solid stone — stop occluding the destination.
 *
 * <p>The consequence, stated plainly: anything real between the camera and the
 * quad is overdrawn where the quad lands. The renderer cuts this quad against
 * the aperture tunnel first, so the frame's own block is respected — but a
 * block standing anywhere else between the camera and the opening is still
 * overdrawn, which no depth test here can prevent.
 */
public final class PortalRenderLayers {

    private PortalRenderLayers() {}

    public static final RenderLayer BACKDROP = new RenderLayer(
            "customdimensions_portal_backdrop",
            VertexFormats.POSITION_COLOR,
            VertexFormat.DrawMode.QUADS,
            1536,
            false,
            false,
            () -> {
                RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                RenderSystem.disableBlend();
                RenderSystem.disableCull();
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
                RenderSystem.depthFunc(GL11.GL_ALWAYS);
            },
            () -> {
                RenderSystem.depthFunc(GL11.GL_LEQUAL);
                RenderSystem.enableCull();
            }) {};
}
