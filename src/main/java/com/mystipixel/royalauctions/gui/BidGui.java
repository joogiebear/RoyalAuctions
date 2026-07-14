package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Place Bid — layout from gui/bid.yml. */
public final class BidGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Listing listing;
    private final String category;
    private final String search;
    private final SortOrder sort;
    private final int page;
    private final double minBid;

    public BidGui(GuiManager manager, Listing listing, String category, String search, SortOrder sort, int page) {
        this.manager = manager;
        this.template = manager.menus().bid();
        this.listing = listing;
        this.category = category;
        this.search = search;
        this.sort = sort;
        this.page = page;
        this.minBid = listing.nextMinBid(manager.config().bidIncrementFor(listing.currentBid()));
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
        render();
    }

    private void render() {
        template.applyStatic(inventory, Map.of(
                "min_bid", manager.vault().format(minBid),
                "current_bid", manager.vault().format(listing.displayPrice()),
                "seller", listing.sellerName()));

        int itemSlot = template.slotOf("item");
        if (itemSlot >= 0) {
            long remaining = listing.expiresAt() - System.currentTimeMillis();
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Seller: &f" + listing.sellerName());
            if (listing.hasBids()) {
                lore.add("&7Current bid: &a" + manager.vault().format(listing.currentBid()));
                lore.add("&7Top bidder: &f" + listing.topBidderName());
                lore.add("&7Bids: &f" + listing.bidCount());
            } else {
                lore.add("&7Starting bid: &a" + manager.vault().format(listing.price()));
                lore.add("&7No bids yet");
            }
            lore.add("&7Ends in: &f" + GuiUtil.timeLeft(remaining));
            inventory.setItem(itemSlot, GuiUtil.appendLore(listing.item(), lore));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case BID_MIN -> manager.confirmBid(player, listing, minBid, category, search, sort, page);
            case BID_CUSTOM -> manager.beginBidInput(player, listing, category, search, sort, page);
            case OPEN_BROWSE, BACK -> manager.openBrowse(player, category, search, sort, page);
            default -> {
                // decorative
            }
        }
    }
}
