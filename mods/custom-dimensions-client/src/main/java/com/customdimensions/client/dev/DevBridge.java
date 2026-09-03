package com.customdimensions.client.dev;

import com.customdimensions.client.CustomDimensionsClient;
import com.customdimensions.client.mixin.MinecraftClientInvoker;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The client half of the dev control surface: a tick counter, the held inputs,
 * and the primitives {@link DevServer} calls. Every method here runs on the
 * render thread.
 *
 * <p>Held keys are driven through the {@link KeyBinding} the game reads —
 * {@code KeyboardInput.tick} re-reads {@code forwardKey.isPressed()} every
 * tick, so a written {@code Input} field would be overwritten before it moved
 * anything.
 */
public final class DevBridge {

    private static final AtomicInteger TICK = new AtomicInteger();

    private static volatile Walk walk;
    private static volatile Hold hold;

    private DevBridge() {}

    /** Off unless a port is named. Nothing is registered when it is not. */
    public static void start() {
        int port = DevPort.resolve();
        if (port <= 0) {
            CustomDimensionsClient.LOGGER.info(
                    "Dev control surface disabled (-D{} and ${} unset)",
                    DevPort.PROPERTY, DevPort.ENVIRONMENT);
            return;
        }
        ClientTickEvents.END_CLIENT_TICK.register(DevBridge::tick);
        DevServer.start(port);
    }

    public static int tick() {
        return TICK.get();
    }

    private static void tick(MinecraftClient client) {
        int now = TICK.incrementAndGet();
        Walk running = walk;
        if (running != null) {
            running.tick(client, now);
        }
        Hold held = hold;
        if (held != null) {
            held.tick(now);
        }
    }

    // -------------------------------------------------------------- actions

    static CompletableFuture<WalkTracker> startWalk(MinecraftClient client, double blocks,
                                                    int stallTicks, int timeoutTicks) {
        if (walk != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("a walk is already running"));
        }
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("no player in the world"));
        }
        Vec3d pos = player.getPos();
        Walk started = new Walk(new WalkTracker(pos.x, pos.y, pos.z, TICK.get(),
                blocks, stallTicks, timeoutTicks), client.options.forwardKey);
        walk = started;
        return started.done;
    }

    static CompletableFuture<Void> startHold(MinecraftClient client, String key, int ticks) {
        if (hold != null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("a key is already held"));
        }
        KeyBinding binding = binding(client, key);
        if (binding == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("no key binding named " + key));
        }
        Hold started = new Hold(binding, TICK.get() + Math.max(1, ticks));
        binding.setPressed(true);
        hold = started;
        return started.done;
    }

    static void look(MinecraftClient client, double yaw, double pitch) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            throw new IllegalStateException("no player in the world");
        }
        player.setYaw((float) yaw);
        player.setPitch((float) Math.max(-90.0, Math.min(90.0, pitch)));
    }

    static void use(MinecraftClient client) {
        if (client.player == null) {
            throw new IllegalStateException("no player in the world");
        }
        ((MinecraftClientInvoker) client).customdimensionsclient$doItemUse();
    }

    /**
     * Escape is not a key binding — the game handles it in {@code Keyboard.onKey}
     * by closing the open screen or opening the game menu, and that is the path
     * taken here. Everything else is a tap on the binding the game reads.
     */
    static void tap(MinecraftClient client, String key) {
        if ("escape".equalsIgnoreCase(key)) {
            escape(client);
            return;
        }
        KeyBinding binding = binding(client, key);
        if (binding == null) {
            throw new IllegalStateException("no key binding named " + key);
        }
        if (binding.isUnbound()) {
            throw new IllegalStateException(key + " is not bound to anything");
        }
        if (hold != null) {
            throw new IllegalStateException("a key is already held");
        }
        // wasPressed() consumers read timesPressed, which setPressed never
        // touches; isPressed() consumers need the one-tick hold.
        KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey()));
        startHold(client, key, 1);
    }

    private static void escape(MinecraftClient client) {
        if (client.currentScreen == null) {
            client.openGameMenu(false);
            return;
        }
        if (!client.currentScreen.shouldCloseOnEsc()) {
            throw new IllegalStateException(client.currentScreen.getClass().getSimpleName()
                    + " does not close on escape");
        }
        client.currentScreen.close();
    }

    static KeyBinding binding(MinecraftClient client, String key) {
        if (key == null) {
            return null;
        }
        return switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "forward" -> client.options.forwardKey;
            case "back" -> client.options.backKey;
            case "left" -> client.options.leftKey;
            case "right" -> client.options.rightKey;
            case "jump" -> client.options.jumpKey;
            case "sneak" -> client.options.sneakKey;
            case "sprint" -> client.options.sprintKey;
            case "attack" -> client.options.attackKey;
            case "use" -> client.options.useKey;
            case "inventory" -> client.options.inventoryKey;
            case "chat" -> client.options.chatKey;
            case "command" -> client.options.commandKey;
            case "drop" -> client.options.dropKey;
            case "swaphands" -> client.options.swapHandsKey;
            case "pickitem" -> client.options.pickItemKey;
            case "playerlist" -> client.options.playerListKey;
            case "advancements" -> client.options.advancementsKey;
            case "perspective" -> client.options.togglePerspectiveKey;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- state

    private static final class Walk {

        private final WalkTracker tracker;
        private final KeyBinding forward;
        private final CompletableFuture<WalkTracker> done = new CompletableFuture<>();

        Walk(WalkTracker tracker, KeyBinding forward) {
            this.tracker = tracker;
            this.forward = forward;
        }

        void tick(MinecraftClient client, int now) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                release();
                this.done.completeExceptionally(
                        new IllegalStateException("player left the world mid-walk"));
                return;
            }
            // MinecraftClient.setScreen calls KeyBinding.unpressAll, so the hold
            // is re-asserted every tick rather than set once.
            this.forward.setPressed(true);
            Vec3d pos = player.getPos();
            if (this.tracker.accept(pos.x, pos.y, pos.z, now) != WalkTracker.Verdict.CONTINUE) {
                release();
                this.done.complete(this.tracker);
            }
        }

        private void release() {
            this.forward.setPressed(false);
            walk = null;
        }
    }

    private static final class Hold {

        private final KeyBinding binding;
        private final int releaseAt;
        private final CompletableFuture<Void> done = new CompletableFuture<>();

        Hold(KeyBinding binding, int releaseAt) {
            this.binding = binding;
            this.releaseAt = releaseAt;
        }

        void tick(int now) {
            if (now < this.releaseAt) {
                this.binding.setPressed(true);
                return;
            }
            this.binding.setPressed(false);
            hold = null;
            this.done.complete(null);
        }
    }
}
