package com.customdimensions.client;

import com.customdimensions.client.config.RealtimeSettings;

/**
 * Whether this client is still standing behind its own portal render, and what
 * that makes the view it declares.
 *
 * <p>A client that cannot stand a destination up, or whose render pass has
 * stood itself down, declares client-side off; the player's own server-side
 * setting then takes effect on its own and the slab resumes. Their stored
 * preference is never written — they did not choose this.
 *
 * <p>Session-wide, because {@code portal-view/v1} carries one flag per player:
 * one refused destination puts this client back on the slab for EVERY portal
 * until the player changes a setting or reconnects.
 *
 * <p>Latched. Re-declaring changes what the server sends, which changes what
 * this client holds, so a client that refuses twice declares exactly once.
 */
public final class PortalViewDeclaration {

    /** Grepped in the client log when this client stands down to the slab. */
    public static final String REFUSAL_MARKER = "companion-client:client-side-refused";

    private static volatile boolean refused;
    private static volatile String reason = "";

    private PortalViewDeclaration() {}

    /** True while this client has stood down from rendering the far side. */
    public static boolean refused() {
        return refused;
    }

    /** Why it stood down, for the log line and the dev bridge. */
    public static String reason() {
        return reason;
    }

    /** A destination this client has no world for. True when a declaration is owed. */
    public static boolean destinationRefused(boolean refusedNow) {
        return refusedNow && refuse("destination-refused");
    }

    /** The local pass after it disabled itself. True when a declaration is owed. */
    public static boolean renderPassDisabled(boolean disabledNow) {
        return disabledNow && refuse("render-pass-disabled");
    }

    /** Re-arms: a new connection, a new world, or a setting the player changed. */
    public static void clear() {
        refused = false;
        reason = "";
    }

    /** The view to send, client-side forced off while this client has refused. */
    public static RealtimeSettings declared(RealtimeSettings settings) {
        return refused ? settings.withRenderClientSidePortals(false) : settings;
    }

    private static boolean refuse(String because) {
        if (refused) {
            return false;
        }
        refused = true;
        reason = because;
        return true;
    }
}
