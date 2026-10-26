package com.customdimensions.server.command;

import java.nio.file.Path;

/** The definitions folder {@code create} and {@code pick} write to, and the self-test's override of it. */
public final class RollCommandAccess {
    private RollCommandAccess() {}

    public static Path definitionsDir() {
        return CreateCommand.definitionsDir();
    }

    public static void override(Path dir) {
        CreateCommand.definitionsOverride = dir;
    }
}
