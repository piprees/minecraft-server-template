package com.customdimensions.client.config;

import com.customdimensions.client.CustomDimensionsClient;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;

/**
 * The player's way in: a config file under {@code config/}, and a key that
 * flips the client-side portal view without a relaunch. Server-side is set in
 * the file — a key that flipped both would be flipping a setting the first one
 * already overrides.
 *
 * <p>The Minecraft-facing shim over {@link RealtimeSettingsStore}, which holds
 * the rules and is where they are tested. Nothing here decides anything.
 */
public final class RealtimeControls {

    /** Grepped in the client log to prove the key reached the store. */
    public static final String TOGGLE_MARKER = "companion-client:realtime-toggle";

    static final String FILE_NAME = "customdimensions-client.json";

    private static final String TOGGLE_KEY = "key.customdimensionsclient.toggle_realtime";
    private static final String CATEGORY = "key.categories.customdimensionsclient";

    private static RealtimeSettingsStore store;
    private static KeyBinding toggleKey;

    private RealtimeControls() {}

    /** The live settings; the defaults until {@link #register} has run. */
    public static RealtimeSettings settings() {
        RealtimeSettingsStore held = store;
        return held == null ? RealtimeSettings.DEFAULTS : held.current();
    }

    public static RealtimeSettingsStore store() {
        if (store == null) {
            store = new RealtimeSettingsStore(configFile());
        }
        return store;
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static void register() {
        store().load();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                TOGGLE_KEY, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(RealtimeControls::pollToggleKey);
    }

    private static void pollToggleKey(MinecraftClient client) {
        if (toggleKey == null) {
            return;
        }
        // wasPressed drains one press per call, so a tick that saw two taps
        // toggles twice rather than swallowing one.
        while (toggleKey.wasPressed()) {
            RealtimeSettings now = store().toggle();
            CustomDimensionsClient.LOGGER.info("{} renderClientSidePortals={} effectiveServerSide={}",
                    TOGGLE_MARKER, now.renderClientSidePortals(), now.effectiveServerSide());
            if (client.player != null) {
                client.player.sendMessage(Text.translatable(messageKey(now)), true);
            }
        }
    }

    /** The three states the key can leave the player in, in the order they rank. */
    static String messageKey(RealtimeSettings settings) {
        if (settings.renderClientSidePortals()) {
            return "message.customdimensionsclient.portal_view_client";
        }
        return settings.renderServerSidePortals()
                ? "message.customdimensionsclient.portal_view_server"
                : "message.customdimensionsclient.portal_view_none";
    }
}
