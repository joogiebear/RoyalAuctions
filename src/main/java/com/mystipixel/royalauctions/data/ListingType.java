package com.mystipixel.royalauctions.data;

public enum ListingType {
    /** Fixed price, instant purchase. */
    BIN,
    /** Timed auction: players bid, the highest bid wins when the timer ends. */
    AUCTION;

    public static ListingType fromString(String name, ListingType fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
