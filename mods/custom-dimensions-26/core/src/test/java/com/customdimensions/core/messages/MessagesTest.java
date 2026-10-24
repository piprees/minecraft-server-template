package com.customdimensions.core.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {

    @Test
    void theFileWinsOverTheDefaultAndVariablesAreFilled() {
        Messages m = Messages.parse("{\"_comment\": \"x\", \"win.earned\": \"{player} won {win} in {dimension}\"}");
        assertEquals("Ann won Summit in the_canvas",
                m.format("win.earned", Map.of("player", "Ann", "win", "Summit", "dimension", "the_canvas")));
    }

    @Test
    void aMissingKeyFallsBackToTheDefaultThenToTheKey() {
        Messages m = Messages.parse("{\"deploy.starting\": \"x\"}");
        assertEquals("The portal cannot find anywhere safe to put you.", m.template(Messages.PORTAL_NO_SITE));
        assertEquals("no.such.key", m.template("no.such.key"));
        assertEquals("x", m.template("deploy.starting"));
        assertEquals("_comment", m.template("_comment"));
    }

    @Test
    void anUnknownVariableStaysAsWritten() {
        assertEquals("{who} here", Messages.parse("{\"k\": \"{who} here\"}").format("k", Map.of("player", "Ann")));
    }

    @Test
    void aMissingOrBrokenFileGivesTheDefaults(@TempDir Path dir) throws Exception {
        assertEquals("The portal cannot find anywhere safe to put you.",
                Messages.load(dir.resolve("absent.json")).template(Messages.PORTAL_NO_SITE));
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{not json");
        assertEquals(Messages.defaults().template(Messages.WIN_EARNED), Messages.load(broken).template(Messages.WIN_EARNED));
    }

    @Test
    void theRepositoryMessagesFileCarriesTheModKeys() {
        Messages m = Messages.load(Path.of("../../../config/messages.json"));
        assertEquals(Messages.defaults().template(Messages.PORTAL_NO_SITE), m.template(Messages.PORTAL_NO_SITE));
        assertEquals(Messages.defaults().template(Messages.WIN_EARNED), m.template(Messages.WIN_EARNED));
        for (String key : Messages.defaultKeys()) {
            assertEquals(Messages.defaults().template(key), m.template(key), key);
        }
    }
}
