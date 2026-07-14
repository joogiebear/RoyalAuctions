package com.mystipixel.royalauctions.data;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * An item waiting in a player's collection: a returned/expired/cancelled listing,
 * or a purchase that couldn't fit in the buyer's inventory.
 */
public final class CollectionItem {

    /** Why the item is in the collection (shown as lore). */
    public enum Reason {
        PURCHASE,
        EXPIRED,
        CANCELLED
    }

    private final UUID id;
    private final UUID ownerId;
    private final byte[] itemData;
    private final Reason reason;
    private final long createdAt;

    private transient ItemStack cachedItem;

    public CollectionItem(UUID id, UUID ownerId, byte[] itemData, Reason reason, long createdAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.itemData = itemData;
        this.reason = reason;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public byte[] itemData() {
        return itemData;
    }

    public Reason reason() {
        return reason;
    }

    public long createdAt() {
        return createdAt;
    }

    public ItemStack item() {
        if (cachedItem == null) {
            cachedItem = ItemSerialization.deserialize(itemData);
        }
        return cachedItem.clone();
    }
}
