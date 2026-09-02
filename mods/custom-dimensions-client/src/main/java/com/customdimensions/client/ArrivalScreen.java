package com.customdimensions.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Stands in for DownloadingTerrainScreen on a preloaded managed traversal.
 *
 * It paints nothing and closes on its first tick. A screen rather than null
 * because joinWorld calls reset() BEFORE assigning the new world, so the one
 * forced render() inside reset would otherwise draw the world being torn down.
 * Holding a no-op screen for that single frame leaves the previous frame on
 * screen instead, which is what makes the crossing stop reading as a load.
 */
public class ArrivalScreen extends Screen {
    public ArrivalScreen() {
        super(Text.empty());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Deliberately empty: no widgets, no background, no blur.
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        // Deliberately empty — Screen's default blurs and darkens.
    }

    @Override
    public void tick() {
        this.close();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
