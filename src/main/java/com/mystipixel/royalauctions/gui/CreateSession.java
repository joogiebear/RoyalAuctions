package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.ListingType;
import org.bukkit.inventory.ItemStack;

/**
 * Per-player state for the GUI create-auction flow. The item lives here (in escrow) from the
 * moment it is deposited until the listing is confirmed or the flow is cancelled, so it is never
 * left in a menu that could be closed and lost.
 */
public final class CreateSession {

    private ItemStack item;
    private double price;
    private int durationHours;
    private ListingType type;
    private boolean awaitingPrice;

    public CreateSession(int durationHours, ListingType type) {
        this.durationHours = durationHours;
        this.type = type;
    }

    /** Returns a copy so GUI rendering (which appends lore) can't mutate the stored item. */
    public ItemStack item() {
        return item == null ? null : item.clone();
    }

    public void item(ItemStack item) {
        this.item = item == null ? null : item.clone();
    }

    public boolean hasItem() {
        return item != null && !item.getType().isAir();
    }

    public double price() {
        return price;
    }

    public void price(double price) {
        this.price = price;
    }

    public boolean hasPrice() {
        return price > 0;
    }

    public int durationHours() {
        return durationHours;
    }

    public void durationHours(int durationHours) {
        this.durationHours = durationHours;
    }

    public ListingType type() {
        return type;
    }

    public void type(ListingType type) {
        this.type = type;
    }

    public void toggleType() {
        this.type = type == ListingType.BIN ? ListingType.AUCTION : ListingType.BIN;
    }

    public boolean awaitingPrice() {
        return awaitingPrice;
    }

    public void awaitingPrice(boolean awaitingPrice) {
        this.awaitingPrice = awaitingPrice;
    }

    public boolean isReady() {
        return hasItem() && hasPrice();
    }
}
