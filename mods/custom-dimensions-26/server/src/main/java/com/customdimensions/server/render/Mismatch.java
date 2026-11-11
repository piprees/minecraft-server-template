package com.customdimensions.server.render;

/**
 * The class of one block where a generated chunk's solidity differs from the
 * noise column the sampler reads, for the roll agreement check's classifier.
 */
final class Mismatch {
    private Mismatch() {}

    /**
     * {@code removed}: solid in the noise, air in the chunk. {@code flooded}: solid
     * in the noise, fluid in the chunk. {@code structure}: inside a structure piece.
     * {@code beard}: the block's structure terrain adaptation (vanilla's
     * {@code Beardifier}, which the sampler's noise column leaves out) adds density
     * there. {@code unexplained}: a block the noise itself places, with nothing
     * above to account for it. {@code filled}: a block the noise never places over
     * a noise fluid. {@code added}: one over noise air. {@code null}: no difference.
     */
    static String kind(boolean noiseSolid, boolean realSolid, boolean realFluid, boolean noiseFluid, boolean inPiece,
                       float beard, boolean noiseBlock) {
        if (noiseSolid && !realSolid) return realFluid ? "flooded" : "removed";
        if (noiseSolid || !realSolid) return null;
        if (inPiece) return "structure";
        if (beard > 0) return "beard";
        if (noiseBlock) return "unexplained";
        return noiseFluid ? "filled" : "added";
    }
}
