package com.mystipixel.royalauctions.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.InventoryHolder;

/** Guards our menus (no item theft), routes clicks, and cleans up create sessions on close/quit. */
public final class AuctionGuiListener implements Listener {

    private final GuiManager manager;

    public AuctionGuiListener(GuiManager manager) {
        this.manager = manager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof AuctionGui gui)) {
            return;
        }
        // Cancel every interaction while one of our menus is on top so items can't be moved out or in.
        // We still forward the click (including player-inventory clicks) so the create screen can
        // handle depositing an item; each menu decides what to act on via isTopClick().
        event.setCancelled(true);
        gui.onClick(event);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AuctionGui) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCloseMenu(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof AuctionGui gui) {
            gui.onClose(event);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }
}
