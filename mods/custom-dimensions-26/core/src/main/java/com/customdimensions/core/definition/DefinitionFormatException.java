package com.customdimensions.core.definition;

/** A definition file that is not valid schema v2. The message names the file and the field. */
public final class DefinitionFormatException extends RuntimeException {

    public DefinitionFormatException(String message) {
        super(message);
    }

    public DefinitionFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
