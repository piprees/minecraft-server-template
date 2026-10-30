package com.customdimensions.server.command;

import java.nio.file.Path;

/** The definitions folder {@code create} and {@code pick} write to, the self-test's override of it, and pick's writer. */
public final class RollCommandAccess {
    private RollCommandAccess() {}

    public static Path definitionsDir() {
        return CreateCommand.definitionsDir();
    }

    /** Writes {@code seed} into a definition file as {@code pick} does. */
    public static void writeSeed(Path file, long seed) throws java.io.IOException {
        RollCommand.writeSeed(file, seed);
    }

    public static void override(Path dir) {
        CreateCommand.definitionsOverride = dir;
    }
}
