package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.category.Category;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuAction;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Auction Browser — layout comes from gui/browse.yml; listings fill content-slots, tabs fill category-slots. */
public final class BrowseGui extends AuctionGui {

    private final GuiManager manager;
    private final MenuTemplate template;
    private final Player player;

    private String category;
    private String search;
    private SortOrder sort;
    private int page;

    private List<Listing> allActive = new ArrayList<>();
    private final Map<Integer, Listing> slotToListing = new HashMap<>();
    private final Map<Integer, String> slotToCategory = new HashMap<>();
    private final List<Category> tabs;

    public BrowseGui(GuiManager manager, Player player, String category, String search, SortOrder sort) {
        this.manager = manager;
        this.template = manager.menus().browse();
        this.player = player;
        this.category = category;
        this.search = search;
        this.sort = sort;
        this.tabs = manager.categories().categories();
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
    }

    public void populate(List<Listing> listings, int page) {
        this.allActive = listings;
        this.page = Math.max(0, page);
        render();
    }

    private List<Listing> filtered() {
        String query = search == null ? null : search.toLowerCase(Locale.ROOT);
        List<Listing> out = new ArrayList<>();
        for (Listing l : allActive) {
            if (category != null && !category.equalsIgnoreCase(l.category())) {
                continue;
            }
            if (query != null && !l.displayName().toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            out.add(l);
        }
        out.sort(sort.comparator());
        return out;
    }

    private void render() {
        inventory.clear();
        slotToListing.clear();
        slotToCategory.clear();

        List<Integer> contentSlots = template.slots("content-slots");
        int perPage = Math.max(1, contentSlots.size());
        List<Listing> results = filtered();
        int pages = Math.max(1, (int) Math.ceil(results.size() / (double) perPage));
        if (page >= pages) {
            page = pages - 1;
        }

        Map<String, String> placeholders = Map.of(
                "category", category == null ? "All" : displayNameOf(category),
                "count", String.valueOf(results.size()),
                "page", String.valueOf(page + 1),
                "pages", String.valueOf(pages),
                "search", search == null ? "None" : search,
                "sort", prettySort());
        template.applyStatic(inventory, placeholders);

        int from = page * perPage;
        for (int i = 0; i < perPage && from + i < results.size(); i++) {
            int slot = contentSlots.get(i);
            Listing listing = results.get(from + i);
            inventory.setItem(slot, listingIcon(listing));
            slotToListing.put(slot, listing);
        }

        renderTabs();
    }

    private ItemStack listingIcon(Listing listing) {
        // Keep the item's own lore (Hypixel-style) and append the auction info below it.
        ItemStack icon = listing.item();
        boolean own = listing.sellerId().equals(player.getUniqueId());

        Map<String, String> ph = new HashMap<>();
        ph.put("seller", listing.sellerName());
        ph.put("price", GuiUtil.comma(listing.displayPrice()));
        ph.put("starting_price", GuiUtil.comma(listing.price()));
        ph.put("bids", String.valueOf(listing.bidCount()));
        ph.put("top_bidder", listing.topBidderName() == null ? "None" : listing.topBidderName());
        ph.put("ends_in", GuiUtil.timeLeft(listing.expiresAt() - System.currentTimeMillis()));
        ph.put("bid_label", listing.hasBids() ? "Current bid" : "Starting bid");
        ph.put("click_hint", own ? "&cThis is your own listing"
                : (listing.isAuction() ? "&eClick to bid!" : "&eClick to purchase!"));

        String path = listing.isAuction() ? "listing-lore.auction" : "listing-lore.buy-it-now";
        List<String> lines = template.lore(path, ph);
        if (lines.isEmpty()) {
            lines = defaultListingLore(listing, ph);
        }
        return GuiUtil.appendLore(icon, lines);
    }

    /** Fallback tooltip used when browse.yml has no listing-lore section. */
    private List<String> defaultListingLore(Listing listing, Map<String, String> ph) {
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add("&7Seller: &f" + ph.get("seller"));
        if (listing.isAuction()) {
            lore.add("&6" + ph.get("bid_label") + ": &e" + ph.get("price") + " coins");
            lore.add("&7Top bidder: &f" + ph.get("top_bidder"));
            lore.add("&7Bids: &f" + ph.get("bids"));
        } else {
            lore.add("&6Buy it now: &e" + ph.get("price") + " coins");
        }
        lore.add("");
        lore.add("&7Ends in: &f" + ph.get("ends_in"));
        lore.add("");
        lore.add(ph.get("click_hint"));
        return lore;
    }

    private void renderTabs() {
        List<Integer> catSlots = template.slots("category-slots");
        for (int i = 0; i < tabs.size() && i < catSlots.size(); i++) {
            Category cat = tabs.get(i);
            int slot = catSlots.get(i);
            boolean selected = cat.id().equalsIgnoreCase(category);
            ItemStack tab = GuiUtil.button(cat.icon(), cat.displayName(),
                    selected ? "&aCurrently viewing" : "&7Click to view");
            if (selected) {
                ItemMeta meta = tab.getItemMeta();
                if (meta != null) {
                    meta.setEnchantmentGlintOverride(true);
                    tab.setItemMeta(meta);
                }
            }
            inventory.setItem(slot, tab);
            slotToCategory.put(slot, cat.id());
        }
    }

    private String prettySort() {
        return switch (sort) {
            case NEWEST -> "Newest";
            case OLDEST -> "Oldest";
            case PRICE_LOW -> "Price ↑";
            case PRICE_HIGH -> "Price ↓";
        };
    }

    private String displayNameOf(String categoryId) {
        Category c = manager.categories().byId(categoryId);
        return c == null ? categoryId : Text.plain(Text.color(c.displayName()));
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        int slot = event.getSlot();

        if (slotToListing.containsKey(slot)) {
            Listing listing = slotToListing.get(slot);
            if (listing.isAuction()) {
                manager.openBid(player, listing, category, search, sort, page);
            } else {
                manager.openConfirm(player, listing, category, search, sort, page);
            }
            return;
        }
        if (slotToCategory.containsKey(slot)) {
            String clicked = slotToCategory.get(slot);
            category = clicked.equalsIgnoreCase(category) ? null : clicked;
            page = 0;
            render();
            return;
        }

        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case SEARCH -> manager.beginSearch(player, category, sort);
            case SORT -> {
                sort = sort.next();
                render();
            }
            case CREATE_AUCTION -> manager.openCreate(player);
            case CLEAR_FILTERS -> {
                category = null;
                search = null;
                page = 0;
                render();
            }
            case PREV_PAGE -> {
                if (page > 0) {
                    page--;
                    render();
                }
            }
            case NEXT_PAGE -> {
                page++;
                render();
            }
            case OPEN_MANAGE -> manager.openListings(player);
            case OPEN_COLLECTION -> manager.openCollection(player);
            case CLOSE -> player.closeInventory();
            default -> {
                // decorative / filler
            }
        }
    }
}
