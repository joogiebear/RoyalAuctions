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
    private final MessageManager messages;
    private final VaultHook vault;
    private final MenuManager menus;
    private final SignInput signInput;

    private final Map<UUID, CreateSession> createSessions = new ConcurrentHashMap<>();

    public GuiManager(JavaPlugin plugin, AuctionService service, PluginConfig config, CategoryManager categories,
                      MessageManager messages, VaultHook vault, MenuManager menus, SignInput signInput) {
        this.plugin = plugin;
        this.service = service;
        this.config = config;
        this.categories = categories;
        this.messages = messages;
        this.vault = vault;
        this.menus = menus;
        this.signInput = signInput;
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
        ConfirmPurchaseGui gui = new ConfirmPurchaseGui(this, listing, category, search, sort, page);
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
                service.placeBid(player, listing, amount,
                        () -> openBrowse(player, category, search, sort, page));
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
