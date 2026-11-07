package com.customdimensions.compiler.datapack;

import com.customdimensions.core.definition.Definition;
import com.customdimensions.core.definition.DefinitionReader;

import java.nio.file.Path;

/** Paths Gradle passes to the compiler tests, and small builders for test definitions. */
final class Fixtures {

    static final String NAMESPACE = "adventure";
    private static VanillaData vanilla;

    private Fixtures() {
    }

    /** The vanilla data {@code :compiler:vanillaData} extracted ({@code -Dcustomdimensions.vanilla}). */
    static synchronized VanillaData vanilla() {
        if (vanilla == null) {
            vanilla = new VanillaData(Path.of(property("customdimensions.vanilla")));
        }
        return vanilla;
    }

    /** The shipped v2 definitions ({@code -Dcustomdimensions.definitions}). */
    static Path definitions() {
        return Path.of(property("customdimensions.definitions"));
    }

    /** A definition from the body of its JSON object; {@code schemaVersion} is added. */
    static Definition definition(String body) {
        return DefinitionReader.read("{\"schemaVersion\": 2, " + body + "}", "test.json");
    }

    static DatapackCompiler.Output compile(String slug, String body) {
        return compile(slug, body, VersionTable.V26_3);
    }

    static DatapackCompiler.Output compile(String slug, String body, VersionTable.Version version) {
        return DatapackCompiler.compileOne(NAMESPACE, slug, definition(body), vanilla(), version);
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("-D" + name + " is not set; run the tests through Gradle");
        }
        return value;
    }
}
