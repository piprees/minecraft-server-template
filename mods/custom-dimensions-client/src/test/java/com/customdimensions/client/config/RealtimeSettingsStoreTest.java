package com.customdimensions.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealtimeSettingsStoreTest {

    @Test
    void afirstRunWritesTheDefaultsSoThereIsAFileToEdit(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("customdimensions-client.json");
        RealtimeSettingsStore store = new RealtimeSettingsStore(file);

        assertEquals(RealtimeSettings.DEFAULTS, store.load());
        assertTrue(Files.isRegularFile(file), "nothing was written for the player to edit");
        assertEquals(RealtimeSettings.DEFAULTS,
                RealtimeSettings.parse(Files.readString(file, StandardCharsets.UTF_8)));
    }

    @Test
    void whatWasSavedIsWhatComesBack(@TempDir Path dir) {
        Path file = dir.resolve("customdimensions-client.json");
        RealtimeSettingsStore store = new RealtimeSettingsStore(file);
        RealtimeSettings written = new RealtimeSettings(true, 24, false, false, false);

        store.save(written);

        assertEquals(written, new RealtimeSettingsStore(file).load());
    }

    @Test
    void aToggleChangesTheLiveValueAndReachesDisk(@TempDir Path dir) {
        Path file = dir.resolve("customdimensions-client.json");
        RealtimeSettingsStore store = new RealtimeSettingsStore(file);
        boolean loaded = store.load().renderClientSidePortals();

        assertEquals(!loaded, store.toggle().renderClientSidePortals());
        assertEquals(!loaded, store.current().renderClientSidePortals());
        assertEquals(!loaded, new RealtimeSettingsStore(file).load().renderClientSidePortals(),
                "the toggle did not survive a restart");

        assertEquals(loaded, store.toggle().renderClientSidePortals());
        assertEquals(loaded, new RealtimeSettingsStore(file).load().renderClientSidePortals());
    }

    @Test
    void aChangeNotifiesTheListenerWithTheNewValue(@TempDir Path dir) {
        Path file = dir.resolve("customdimensions-client.json");
        RealtimeSettingsStore store = new RealtimeSettingsStore(file);
        AtomicReference<RealtimeSettings> seen = new AtomicReference<>();
        store.onChange(seen::set);
        store.load();

        RealtimeSettings after = store.toggle();

        assertSame(after, seen.get(), "the handshake would not know the path had changed");
    }

    /**
     * An unwritable config directory is a warning, not a crash: the settings
     * still apply for the session.
     */
    @Test
    void anUnwritableFileStillLeavesTheSettingsUsable(@TempDir Path dir) throws IOException {
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        RealtimeSettingsStore store = new RealtimeSettingsStore(blocked.resolve("nested.json"));

        assertEquals(RealtimeSettings.DEFAULTS, store.load());
        boolean flipped = !RealtimeSettings.DEFAULTS.renderClientSidePortals();
        assertEquals(flipped, store.toggle().renderClientSidePortals());
        assertEquals(flipped, store.current().renderClientSidePortals());
    }

    @Test
    void currentAnswersTheDefaultsBeforeAnythingIsLoaded(@TempDir Path dir) {
        assertEquals(RealtimeSettings.DEFAULTS,
                new RealtimeSettingsStore(dir.resolve("unread.json")).current());
    }

    /**
     * Every player who ran the previous version has an unstamped file. Reading
     * one migrates it AND writes the stamp back, so a later opt-out is not
     * migrated over on the next boot.
     */
    @Test
    void anUnstampedFileIsMigratedAndRewritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("customdimensions-client.json");
        Files.writeString(file, "{\"enabled\":false,\"fallbackToSlab\":false}\n",
                StandardCharsets.UTF_8);

        RealtimeSettings migrated = new RealtimeSettingsStore(file).load();

        assertFalse(migrated.renderClientSidePortals(), "a deliberate opt-out was thrown away");
        assertTrue(migrated.renderServerSidePortals());
        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(RealtimeSettings.needsMigration(rewritten),
                "the stamp never reached the file, so this migrates again every boot");
        assertEquals(migrated, new RealtimeSettingsStore(file).load());
    }

    /** A file the player mangled is left alone rather than overwritten. */
    @Test
    void aMalformedFileIsNotRewritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("customdimensions-client.json");
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);

        assertEquals(RealtimeSettings.DEFAULTS, new RealtimeSettingsStore(file).load());
        assertEquals("{not json", Files.readString(file, StandardCharsets.UTF_8));
    }
}
