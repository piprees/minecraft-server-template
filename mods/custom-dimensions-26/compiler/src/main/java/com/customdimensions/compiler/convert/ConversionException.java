package com.customdimensions.compiler.convert;

/** A v1 file the converter cannot carry into schema v2; the message names the file and the field. */
public final class ConversionException extends RuntimeException {

    public ConversionException(String message) {
        super(message);
    }
}
