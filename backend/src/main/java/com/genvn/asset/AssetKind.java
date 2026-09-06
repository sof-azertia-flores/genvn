package com.genvn.asset;

public enum AssetKind {
    /** A landscape stage background for one location (and time/state variant). */
    BACKGROUND,
    /** Transparent stage sprite: the reference every variant and card is built from. */
    PORTRAIT,
    /** An expression/state variant of a base portrait; depends on it. */
    PORTRAIT_VARIANT,
    /** One framed, illustrated card per character, shared by every stage pose. */
    CHARACTER_CARD;

    public boolean transparentSprite() {
        return this == PORTRAIT || this == PORTRAIT_VARIANT;
    }
}
