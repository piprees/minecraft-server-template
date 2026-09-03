package com.customdimensions.client.dev;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gate. Three sources, first hit wins, and every value that is not a
 * positive port resolves to 0 — the only state in which no listener is created.
 *
 * <p>The distinction that matters most is between an ABSENT file, which is the
 * normal state on every player's client and must be silent, and a file that
 * exists and cannot be used, which must say so once and still start nothing.
 */
class DevPortTest {

    private static final String FILE = "{\"port\":8766}";

    // ------------------------------------------------------------- precedence

    @Test
    void thePropertyWinsOverEverything() {
        DevPort.Resolved resolved = DevPort.resolve("8766", "9999", "{\"port\":7777}");
        assertEquals(8766, resolved.port());
        assertEquals("property", resolved.source());
    }

    @Test
    void theEnvironmentWinsOverTheFile() {
        DevPort.Resolved resolved = DevPort.resolve(null, "9999", FILE);
        assertEquals(9999, resolved.port());
        assertEquals("environment", resolved.source());
    }

    @Test
    void theFileIsUsedWhenNeitherOtherSourceNamesAPort() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, FILE);
        assertEquals(8766, resolved.port());
        assertEquals("file", resolved.source());
    }

    @Test
    void aBlankPropertyFallsThroughToTheEnvironment() {
        assertEquals(9999, DevPort.resolve("   ", "9999", null).port());
    }

    @Test
    void aBlankEnvironmentFallsThroughToTheFile() {
        assertEquals(8766, DevPort.resolve(null, "  ", FILE).port());
    }

    // ------------------------------------------------------- no source at all

    @Test
    void nothingSetIsDisabled() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, null);
        assertEquals(0, resolved.port());
        assertEquals("none", resolved.source());
    }

    /** An absent file is the normal state on a player's client. It says nothing. */
    @Test
    void anAbsentFileIsSilent() {
        assertNull(DevPort.resolve(null, null, null).warning());
    }

    @Test
    void aBlankPropertyAndEnvironmentWithNoFileAreSilent() {
        assertNull(DevPort.resolve("", "  ", null).warning());
    }

    // ------------------------------------------------------------ good values

    @Test
    void surroundingWhitespaceIsTrimmedFromThePropertyAndEnvironment() {
        assertEquals(8766, DevPort.resolve(" 8766 ", null, null).port());
        assertEquals(8766, DevPort.resolve(null, " 8766 ", null).port());
    }

    @Test
    void whitespaceAroundTheFileContentsIsTolerated() {
        assertEquals(8766, DevPort.resolve(null, null, "\n  {\"port\": 8766}  \n").port());
    }

    /**
     * The exact bytes {@code ./dev launch --dev-bridge} writes:
     * {@code printf '{"port": %s}\n'}. This is a contract between two processes,
     * so the fixture is copied from the writer rather than composed here.
     */
    @Test
    void whatTheLaunchScriptWritesIsRead() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, "{\"port\": 8766}\n");
        assertEquals(8766, resolved.port());
        assertEquals("file", resolved.source());
        assertNull(resolved.warning());
    }

    @Test
    void theTopOfTheLegalRangeIsAccepted() {
        assertEquals(65535, DevPort.resolve("65535", null, null).port());
    }

    @Test
    void aGoodValueCarriesNoWarning() {
        assertNull(DevPort.resolve("8766", null, null).warning());
        assertNull(DevPort.resolve(null, "8766", null).warning());
        assertNull(DevPort.resolve(null, null, FILE).warning());
    }

    // ------------------------------------------- bad property and environment

    @Test
    void aNonNumericPropertyIsDisabledAndSaysSo() {
        DevPort.Resolved resolved = DevPort.resolve("yes", null, null);
        assertEquals(0, resolved.port());
        assertTrue(resolved.warning().contains(DevPort.PROPERTY), resolved.warning());
    }

    @Test
    void aNonNumericEnvironmentValueIsDisabledAndSaysSo() {
        DevPort.Resolved resolved = DevPort.resolve(null, "true", null);
        assertEquals(0, resolved.port());
        assertTrue(resolved.warning().contains(DevPort.ENVIRONMENT), resolved.warning());
    }

    @Test
    void zeroIsDisabled() {
        assertEquals(0, DevPort.resolve("0", null, null).port());
    }

    @Test
    void negativeIsDisabled() {
        assertEquals(0, DevPort.resolve("-1", null, null).port());
    }

    @Test
    void aPortAboveTheLegalRangeIsDisabled() {
        assertEquals(0, DevPort.resolve("65536", null, null).port());
    }

    /** A source that names an unusable port stops there; it never tries the next one. */
    @Test
    void aRejectedPropertyDoesNotFallThroughToTheEnvironment() {
        assertEquals(0, DevPort.resolve("nonsense", "8766", null).port());
    }

    @Test
    void aRejectedPropertyDoesNotFallThroughToTheFile() {
        assertEquals(0, DevPort.resolve("nonsense", null, FILE).port());
    }

    @Test
    void aRejectedEnvironmentValueDoesNotFallThroughToTheFile() {
        assertEquals(0, DevPort.resolve(null, "nonsense", FILE).port());
    }

    // ------------------------------------------------------------ bad file

    @Test
    void aFileThatIsNotJsonIsDisabledWithAWarning() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, "port=8766");
        assertEquals(0, resolved.port());
        assertEquals("none", resolved.source());
        assertNotNull(resolved.warning());
    }

    @Test
    void anEmptyFileIsDisabledWithAWarning() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, "");
        assertEquals(0, resolved.port());
        assertNotNull(resolved.warning());
    }

    @Test
    void aFileWithNoPortFieldIsDisabledWithAWarning() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, "{}");
        assertEquals(0, resolved.port());
        assertNotNull(resolved.warning());
    }

    @Test
    void aFilePortOfZeroIsDisabledWithAWarning() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, "{\"port\":0}");
        assertEquals(0, resolved.port());
        assertNotNull(resolved.warning());
    }

    @Test
    void aFilePortAboveTheLegalRangeIsDisabledWithAWarning() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, "{\"port\":70000}");
        assertEquals(0, resolved.port());
        assertNotNull(resolved.warning());
    }

    @Test
    void aFilePortWrittenAsAStringIsDisabledWithAWarning() {
        DevPort.Resolved resolved = DevPort.resolve(null, null, "{\"port\":\"8766\"}");
        assertEquals(0, resolved.port());
        assertNotNull(resolved.warning());
    }

    /** The warning has to name the file, or nobody will know which one to delete. */
    @Test
    void aFileWarningNamesTheFile() {
        String warning = DevPort.resolve(null, null, "{}").warning();
        assertTrue(warning.contains(DevPort.FILE_NAME), warning);
    }

    // ----------------------------------------------------------------- naming

    @Test
    void theFileNameIsTheOneTheToolingWrites() {
        assertEquals("customdimensionsclient-dev.json", DevPort.FILE_NAME);
    }

    @Test
    void thePropertyAndEnvironmentNamesAreTheDocumentedOnes() {
        assertEquals("customdimensions.devPort", DevPort.PROPERTY);
        assertEquals("CUSTOMDIMENSIONS_DEV_PORT", DevPort.ENVIRONMENT);
    }
}
