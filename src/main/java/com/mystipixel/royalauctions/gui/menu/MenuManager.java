package com.mystipixel.royalauctions.gui.menu;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

/** Loads (and, on first run, writes out) every gui/*.yml menu template. Reloadable. */
public final class MenuManager {

    private final JavaPlugin plugin;

    private MenuTemplate browse;
    private MenuTemplate create;
    private MenuTemplate duration;
    private MenuTemplate confirmPurchase;
    private MenuTemplate confirmAuction;
    private MenuTemplate bid;
    private MenuTemplate collection;
    private MenuTemplate manage;

    public MenuManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.browse = load("browse.yml", "&8Auction House", 6);
        this.create = load("create.yml", "&8Create Auction", 5);
        this.duration = load("duration.yml", "&8Auction Duration", 3);
        this.confirmPurchase = load("confirm-purchase.yml", "&8Confirm Purchase", 3);
        this.confirmAuction = load("confirm-auction.yml", "&8Confirm Auction", 3);
        this.bid = load("bid.yml", "&8Place Bid", 3);
        this.collection = load("collection.yml", "&8Collection", 6);
        this.manage = load("manage.yml", "&8Manage Auctions", 6);
    }

    private MenuTemplate load(String fileName, String defaultTitle, int defaultRows) {
        File file = new File(plugin.getDataFolder(), "gui/" + fileName);
        if (!file.exists()) {
            plugin.saveResource("gui/" + fileName, false);
        }
        return MenuTemplate.load(file, defaultTitle, defaultRows);
    }

    public MenuTemplate browse() {
        return browse;
    }

    public MenuTemplate create() {
        return create;
    }

    public MenuTemplate duration() {
        return duration;
    }

    public MenuTemplate confirmPurchase() {
        return confirmPurchase;
    }

    public MenuTemplate confirmAuction() {
        return confirmAuction;
    }

    public MenuTemplate bid() {
        return bid;
    }

    public MenuTemplate collection() {
        return collection;
    }

    public MenuTemplate manage() {
        return manage;
    }
}
