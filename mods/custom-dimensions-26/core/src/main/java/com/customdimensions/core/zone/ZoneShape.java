package com.customdimensions.core.zone;

/** The area a zone covers, measured as horizontal distance from its centre. */
public sealed interface ZoneShape {

    /** Everything within {@code radius} blocks. */
    record Circle(double radius) implements ZoneShape {
        public Circle {
            if (!(radius > 0)) {
                throw new IllegalArgumentException("circle radius must be positive, got " + radius);
            }
        }
    }

    /** Everything from {@code inner} to {@code outer} blocks; the hole keeps the base worldgen. */
    record Ring(double inner, double outer) implements ZoneShape {
        public Ring {
            if (!(inner >= 0) || !(outer > inner)) {
                throw new IllegalArgumentException("ring needs 0 <= inner < outer, got " + inner + ".." + outer);
            }
        }
    }
}
