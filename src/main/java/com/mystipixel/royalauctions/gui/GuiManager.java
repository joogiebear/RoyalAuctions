package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.data.SortOrder;
import com.mystipixel.royalauctions.gui.menu.MenuManager;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.service.AuctionService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Opens every menu and runs the sign-based text input flows (search / price / bid). */
public final class GuiManager {

    private final JavaPlugin plugin;
    private final AuctionService service;
    private final PluginConfig config;
    private final CategoryManager categories;
    private final com.mystipixel.royalauctions.tier.TierManager tiers;
    private final MessageManager messages;
    private final VaultHook vault;
    private final MenuManager menus;
    private final SignInput signInput;

    private final Map<UUID, CreateSession> createSessions = new ConcurrentHashMap<>();

    public GuiManager(JavaPlugin plugin, AuctionService service, PluginConfig config, CategoryManager categories,
                      com.mystipixel.royalauctions.tier.TierManager tiers, MessageManager messages,
                      VaultHook vault, MenuManager menus, SignInput signInput) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.categories = categories;
        this.tiers = tiers;
        this.messages = messages;
        this.vault = vault;
        this.menus = menus;
        this.signInput = signInput;
    }

    // ------------------------------------------------------------------ hub / bids / seller view

    /** The {@code /ah} landing menu. Loads the counts the hub icons display, then opens. */
    public void openHub(Player player) {
        UUID uuid = player.getUniqueId();
        service.loadBidListings(uuid, bids -> service.loadSellerListings(uuid, mine -> {
            int top = 0;
            for (Listing l : bids) {
                if (l.topBidderId() != null && l.topBidderId().equals(uuid)) {
                    top++;
                }
            }
            HubGui gui = new HubGui(this, player, bids.size(), top, mine.size(), service.activeCache());
            player.openInventory(gui.getInventory());
        }));
    }

    public void openBids(Player player) {
        openBids(player, 0);
    }

    public void openBids(Player player, int page) {
        service.loadBidListings(player.getUniqueId(), listings -> {
            BidsGui gui = new BidsGui(this, player, listings);
            gui.populate(page);
            player.openInventory(gui.getInventory());
        });
    }

    /** {@code /ah <username>} — that seller's active auctions. */
    public void openSeller(Player player, UUID sellerId, String sellerName) {
        service.loadSellerListings(sellerId, listings -> {
            SellerGui gui = new SellerGui(this, player, sellerName, listings);
            gui.populate(0);
            player.openInventory(gui.getInventory());
        });
    }

    // ------------------------------------------------------------------ browsing / buying / bidding

    public void openBrowse(Player player) {
        openBrowse(player, null, null, config.defaultSort(), 0);
    }

    public void openBrowse(Player player, String category, String search, SortOrder sort, int page) {
        service.loadActiveListings(listings -> {
            BrowseGui gui = new BrowseGui(this, player, category, search, sort);
            gui.populate(listings, page);
            player.openInventory(gui.getInventory());
        });
    }

    public void openConfirm(Player player, Listing listing, String category, String search, SortOrder sort, int page) {
        if (!config.confirmPurchase()) {
            service.purchase(player, listing, () -> openBrowse(player, category, search, sort, page));
            return;
        }
        ConfirmPurchaseGui gui = new ConfirmPurchaseGui(this, listing, category, search, sort, page);
        player.openInventory(gui.getInventory());
    }

    /**
     * Gate a bid behind a confirmation. A bid takes the money immediately (it's held until you're
     * outbid or the auction ends), so a stray click on "Bid" is a real cost — hence the same
     * treatment Buy-It-Now already had. Falls straight through when confirmations.bid is off.
     */
    public void confirmBid(Player player, Listing listing, double amount,
                           String category, String search, SortOrder sort, int page) {
        if (!config.confirmBid()) {
            service.placeBid(player, listing, amount, () -> openBrowse(player, category, search, sort, page));
            return;
        }
        ConfirmBidGui gui = new ConfirmBidGui(this, player, listing, amount, category, search, sort, page);
        player.openInventory(gui.getInventory());
    }

    /** Gate a cancellation behind a confirmation. Falls through when confirmations.cancel is off. */
    public void confirmCancel(Player player, Listing listing, int page) {
        if (!config.confirmCancel()) {
            service.cancelListing(player, listing, () -> openListings(player, page));
            return;
        }
        ConfirmCancelGui gui = new ConfirmCancelGui(this, player, listing, page);
        player.openInventory(gui.getInventory());
    }

    public void openBid(Player player, Listing listing, String category, String search, SortOrder sort, int page) {
        BidGui gui = new BidGui(this, listing, category, search, sort, page);
        player.openInventory(gui.getInventory());
    }

    public void openCollection(Player player) {
        openCollection(player, 0);
    }

    public void openCollection(Player player, int page) {
        service.loadCollection(player.getUniqueId(), items -> {
            CollectionGui gui = new CollectionGui(this, player, items);
            gui.populate(page);
            player.openInventory(gui.getInventory());
        });
    }

    public void openListings(Player player) {
        openListings(player, 0);
    }

    public void openListings(Player player, int page) {
        service.loadSellerListings(player.getUniqueId(), listings -> {
            ListingsGui gui = new ListingsGui(this, player, listings);
            gui.populate(page);
            player.openInventory(gui.getInventory());
        });
    }

    // ------------------------------------------------------------------ create-auction flow

    public CreateSession session(UUID uuid) {
        return createSessions.get(uuid);
    }

    public void openCreate(Player player) {
        createSessions.computeIfAbsent(player.getUniqueId(),
                k -> new CreateSession(config.defaultDurationHours(), config.defaultType()));
        CreateAuctionGui gui = new CreateAuctionGui(this, player);
        player.openInventory(gui.getInventory());
    }

    public void openDuration(Player player) {
        DurationGui gui = new DurationGui(this, player);
        player.openInventory(gui.getInventory());
    }

    public void setDuration(Player player, int hours) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s != null) {
            s.durationHours(hours);
        }
        openCreate(player);
    }

    public void openConfirmAuction(Player player) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null || !s.isReady()) {
            openCreate(player);
            return;
        }
        ConfirmAuctionGui gui = new ConfirmAuctionGui(this, player);
        player.openInventory(gui.getInventory());
    }

    public void confirmCreate(Player player) {
        UUID uuid = player.getUniqueId();
        CreateSession s = createSessions.get(uuid);
        if (s == null || !s.isReady()) {
            openCreate(player);
            return;
        }
        ItemStack item = s.item();
        double price = s.price();
        ListingType type = s.type();
        long durationMillis = config.durationMillisFor(s.durationHours());
        service.createListing(player, item, price, type, durationMillis, listed -> {
            if (listed) {
                createSessions.remove(uuid);
                openListings(player);
            } else {
                openCreate(player);
            }
        });
    }

    public void cancelCreate(Player player) {
        endCreateSession(player, true);
        openBrowse(player);
    }

    /**
     * Shutdown drain: any item still escrowed in a create-flow goes to its owner's collection so they
     * can claim it back. Without this, an item deposited into the sell menu exists only in this map and
     * is destroyed by a restart — the one place the create flow could lose real player property.
     * Runs synchronously during disable (the scheduler is already gone), and returns how many it saved.
     */
    public int drainEscrowToCollection() {
        int saved = 0;
        for (Map.Entry<UUID, CreateSession> entry : createSessions.entrySet()) {
            CreateSession session = entry.getValue();
            if (session == null || !session.hasItem()) {
                continue;
            }
            try {
                service.escrowToCollection(entry.getKey(), session.item());
                saved++;
            } catch (Exception e) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE,
                        "Could not return escrowed auction item for " + entry.getKey(), e);
            }
        }
        createSessions.clear();
        return saved;
    }

    public void endCreateSession(Player player, boolean refund) {
        CreateSession s = createSessions.remove(player.getUniqueId());
        if (s == null) {
            return;
        }
        if (refund && s.hasItem()) {
            service.returnItem(player, s.item());
            messages.send(player, "create.cancelled");
        }
    }

    // ------------------------------------------------------------------ sign input: search / price / bid

    public void beginSearch(Player player, String category, SortOrder sort) {
        signInput.request(player, List.of("^^^^^^^^^^^^^^^", "Search by name", "blank = show all"), input -> {
            if (input == null || input.isBlank()) {
                openBrowse(player, category, null, sort, 0);
            } else {
                openBrowse(player, category, input, sort, 0);
            }
        });
    }

    public void beginPriceInput(Player player) {
        CreateSession s = createSessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        // Suppress the create screen's return-item-on-close while the sign editor is up.
        s.awaitingPrice(true);
        signInput.request(player, List.of("^^^^^^^^^^^^^^^", "Enter a price", "in numbers"), input -> {
            s.awaitingPrice(false);
            if (input != null && !input.isBlank() && !input.equalsIgnoreCase("cancel")) {
                Double price = parsePositive(input);
                if (price == null) {
                    messages.send(player, "general.invalid-number");
                } else {
                    s.price(price);
                    messages.send(player, "create.price-set", "price", vault.format(price));
                }
            }
            openCreate(player);
        });
    }

    public void beginBidInput(Player player, Listing listing, String category, String search, SortOrder sort, int page) {
        signInput.request(player, List.of("^^^^^^^^^^^^^^^", "Enter your bid", "amount"), input -> {
            if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                openBrowse(player, category, search, sort, page);
                return;
            }
            Double amount = parsePositive(input);
            if (amount == null) {
                messages.send(player, "general.invalid-number");
                openBrowse(player, category, search, sort, page);
            } else {
                // A typed amount is the easiest place to fat-finger an extra zero, so it goes
                // through the same confirmation as the one-click bid.
                confirmBid(player, listing, amount, category, search, sort, page);
            }
        });
    }

    // ------------------------------------------------------------------ housekeeping

    public void handleQuit(Player player) {
        CreateSession s = createSessions.remove(player.getUniqueId());
        if (s != null && s.hasItem()) {
            service.returnItem(player, s.item());
        }
    }

    /** Parse a positive amount, accepting k/m/b/t shorthand (e.g. "5k", "50m", "1.5b") and commas. */
    private Double parsePositive(String input) {
        if (input == null) {
            return null;
        }
        String s = input.trim().toLowerCase(java.util.Locale.ROOT).replace(",", "").replace("$", "");
        if (s.isEmpty()) {
            return null;
        }
        double multiplier = 1;
        char last = s.charAt(s.length() - 1);
        int idx = "kmbt".indexOf(last);
        if (idx >= 0) {
            multiplier = switch (last) {
                case 'k' -> 1_000d;
                case 'm' -> 1_000_000d;
                case 'b' -> 1_000_000_000d;
                default -> 1_000_000_000_000d; // 't'
            };
            s = s.substring(0, s.length() - 1);
        }
        try {
            double value = Double.parseDouble(s) * multiplier;
            if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) {
                return null;
            }
            return value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ accessors

    public AuctionService service() {
        return service;
    }

    public PluginConfig config() {
        return config;
    }

    public CategoryManager categories() {
        return categories;
    }

    public com.mystipixel.royalauctions.tier.TierManager tiers() {
        return tiers;
    }

    public MessageManager messages() {
        return messages;
    }

    public VaultHook vault() {
        return vault;
    }

    public MenuManager menus() {
        return menus;
    }
}
