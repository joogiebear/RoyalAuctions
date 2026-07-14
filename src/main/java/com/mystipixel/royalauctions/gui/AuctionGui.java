package com.mystipixel.royalauctions.gui;

import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

/**
 * Base for every RoyalAuctions menu. Using the GUI object itself as the {@link InventoryHolder}
 * lets the single listener recognise our menus and route clicks without tracking open inventories.
 */
public abstract class AuctionGui implements InventoryHolder {

    protected Inventory inventory;

    @NotNull
    @Override
    public Inventory getInventory() {
        return inventory;
    }

    /** Handle a click. The event is already cancelled; most menus only act on top-inventory clicks. */
    public abstract void onClick(InventoryClickEvent event);

    public void onClose(InventoryCloseEvent event) {
    }

    /** True if the click landed in this menu (the top inventory) rather than the player's inventory. */
    protected boolean isTopClick(InventoryClickEvent event) {
        return inventory.equals(event.getClickedInventory());
    }
}
