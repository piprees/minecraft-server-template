package com.customdimensions.server;

import com.customdimensions.core.messages.Messages;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** {@code config/messages.json} under the server's directory, read once; the platform seeds it. */
public final class MessageFile {
    private static Messages messages;

    private MessageFile() {
    }

    public static synchronized Messages get() {
        if (messages == null) {
            messages = Messages.load(path());
        }
        return messages;
    }

    public static Path path() {
        return FabricLoader.getInstance().getGameDir().resolve("config").resolve("messages.json");
    }
}
