package com.customdimensions.portal;

/**
 * Why an ignition attempt was declined. One constant per gate on the path,
 * so a refusal is never silent.
 *
 * <p>Declaration order is how FAR an attempt got before it was turned away.
 * A single click sweeps six neighbours and a 7x7x7 box on three axes, so the
 * first refusal is almost never the one worth telling anyone about — the
 * closest miss is. {@link #furthest} is how a sweep keeps it.
 */
public enum IgnitionRefusal {

    /** The use ran on the client; ignition happens on the server. */
    CLIENT_WORLD("the click was handled on the client, where portals are not lit"),

    /** The world is not a server world, so there is no ignition path on it. */
    NOT_SERVER_WORLD("the world is not a server world"),

    /** The held item lights nothing, and the clicked block is no default frame. */
    NO_IGNITER_MATCH("no portal is lit by this item, and this block is no portal's default frame"),

    /** The definition declares no frame material at all. */
    NO_FRAME_MATERIAL("this portal declares no frame material, so nothing can be its frame"),

    /** Nothing within reach of the click was an empty cell a fill could start from. */
    NO_CANDIDATE_CELL("no empty cell within reach of the click could be the inside of a portal"),

    /** The fill escaped through a block that is neither fillable nor frame. */
    OPENING_NOT_ENCLOSED("the opening is not closed by frame"),

    /** The fill was still growing past the cell cap. */
    OPENING_TOO_LARGE("the opening is bigger than a portal may be"),

    /** A position beside the opening is not frame material. */
    FRAME_INCOMPLETE("the frame around the opening has a gap"),

    /** The opening's geometry is not what the definition's shape preset allows. */
    SHAPE_MISMATCH("the opening is the wrong shape for this portal"),

    /** The opening does not lie under the definition's pattern template. */
    PATTERN_MISMATCH("the opening does not match this portal's pattern"),

    /** A frame position is the wrong material for the part of the ring it sits in. */
    FRAME_PART_MISMATCH("a frame block is the wrong material for its part of the ring"),

    /** The frame is right in every other way, but this portal may not stand this way up. */
    AXIS_NOT_ALLOWED("this portal may not stand in this orientation");

    private final String sentence;

    IgnitionRefusal(String sentence) {
        this.sentence = sentence;
    }

    /** A sentence a player can act on, with no full stop — it sits mid-line. */
    public String sentence() {
        return this.sentence;
    }

    /** How far the attempt got. Higher beat a lower one to the frame. */
    public int progress() {
        return this.ordinal();
    }

    /** Whichever of two refusals came closer to a portal; nulls lose. */
    public static IgnitionRefusal furthest(IgnitionRefusal a, IgnitionRefusal b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.progress() >= b.progress() ? a : b;
    }
}
