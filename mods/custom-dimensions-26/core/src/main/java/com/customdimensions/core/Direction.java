package com.customdimensions.core;

/** The six block faces and their three axes. */
public enum Direction {
    DOWN(Axis.Y), UP(Axis.Y), NORTH(Axis.Z), SOUTH(Axis.Z), WEST(Axis.X), EAST(Axis.X);

    private final Axis axis;

    Direction(Axis axis) {
        this.axis = axis;
    }

    public Axis getAxis() {
        return this.axis;
    }

    public enum Axis { X, Y, Z }
}
