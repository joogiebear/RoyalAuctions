package com.mystipixel.royalauctions.data;

/**
 * One auction-house event that happened to a player while they were offline — a sale, an outbid, a
 * won auction, expired listings — queued in {@code ra_events} and replayed as a short summary on
 * their next join. For {@code EXPIRED} events {@code amount} carries the listing count, not money.
 */
public record OfflineEvent(String type, String item, double amount, long createdAt) {

    public static final String SOLD = "SOLD";
    public static final String OUTBID = "OUTBID";
    public static final String WON = "WON";
    public static final String EXPIRED = "EXPIRED";
}
