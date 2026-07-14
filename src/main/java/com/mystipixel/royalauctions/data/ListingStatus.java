package com.mystipixel.royalauctions.data;

public enum ListingStatus {
    /** Live and buyable. */
    ACTIVE,
    /** Bought by someone. */
    SOLD,
    /** Ran past its duration and was returned to the seller's collection. */
    EXPIRED,
    /** Pulled by the seller before it sold. */
    CANCELLED
}
