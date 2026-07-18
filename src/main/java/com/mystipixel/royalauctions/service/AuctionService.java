package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.AuctionDatabase;
import com.mystipixel.royalauctions.data.CollectionItem;
import com.mystipixel.royalauctions.data.ItemSerialization;
import com.mystipixel.royalauctions.data.Listing;
import com.mystipixel.royalauctions.data.ListingQuery;
import com.mystipixel.royalauctions.data.ListingPage;
import com.mystipixel.royalauctions.data.ListingStatus;
import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * All auction business logic. DB work runs off the main thread; anything touching the
 * Bukkit world or the Vault economy is hopped back onto the main thread. Items are moved
 * into "escrow" (removed from the player) before any async work so nothing can be duped.
 */
public final class AuctionService {

    private final JavaPlugin plugin;
    private final AuctionDatabase db;
    private final VaultHook vault;
    private final PluginConfig config;
    private final CategoryManager categories;
    private final com.mystipixel.royalauctions.tier.TierManager tiers;
    private final MessageManager messages;
    private final com.mystipixel.royalauctions.hooks.EconGuardHook econGuard;

    /** Cheap cached count for placeholders; refreshed by the expiry sweep and on demand. */
    private volatile int activeCache = 0;

    public AuctionService(JavaPlugin plugin, AuctionDatabase db, VaultHook vault, PluginConfig config,
                          CategoryManager categories, com.mystipixel.royalauctions.tier.TierManager tiers,
                          MessageManager messages, com.mystipixel.royalauctions.hooks.EconGuardHook econGuard) {
        this.plugin = plugin;
        this.db = db;
        this.vault = vault;
        this.config = config;
        this.categories = categories;
        this.tiers = tiers;
        this.messages = messages;
        this.econGuard = econGuard;
    }

    // ------------------------------------------------------------------ scheduling helpers

    private void async(Runnable r) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, r);
        }
    }

    private void sync(Runnable r) {
        if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, r);
        }
    }

    private void logError(String what, Throwable t) {
        plugin.getLogger().log(Level.SEVERE, "Database error while " + what, t);
    }

    // ------------------------------------------------------------------ listing (selling)

    /**
     * Create a listing from the GUI create-flow. The item is already held in the create session
     * (escrow), so this never touches the player's inventory. {@code onResult} receives {@code true}
     * only if the listing was stored (item consumed); {@code false} leaves the item in the session
     * so the caller can reopen the create screen for the player to adjust.
     */
    public void createListing(Player seller, ItemStack item, double price, ListingType type,
                              long durationMillis, Consumer<Boolean> onResult) {
        if (item == null || item.getType().isAir()) {
            onResult.accept(false);
            return;
        }
        if (price < config.minPrice()) {
            messages.send(seller, "sell.min-price", "min", vault.format(config.minPrice()));
            onResult.accept(false);
            return;
        }
        if (config.hasMaxPrice() && price > config.maxPrice()) {
            messages.send(seller, "sell.max-price", "max", vault.format(config.maxPrice()));
            onResult.accept(false);
            return;
        }

        UUID sellerId = seller.getUniqueId();
        async(() -> {
            int count;
            try {
                count = db.countActiveBySeller(sellerId);
            } catch (Exception e) {
                logError("counting listings", e);
                sync(() -> onResult.accept(false));
                return;
            }
            sync(() -> finishListing(seller, item, price, type, durationMillis, count, onResult));
        });
    }

    private void finishListing(Player seller, ItemStack item, double price, ListingType type,
                               long durationMillis, int currentCount, Consumer<Boolean> onResult) {
        int limit = config.maxPerPlayer();
        boolean unlimited = limit < 0 || seller.hasPermission("royalauctions.admin");
        if (!unlimited && currentCount >= limit) {
            messages.send(seller, "sell.too-many", "max", String.valueOf(limit));
            onResult.accept(false);
            return;
        }

        double fee = config.feeFor(price);
        if (!vault.has(seller, fee) || !vault.withdraw(seller, fee)) {
            messages.send(seller, "sell.cannot-afford-fee", "fee", vault.format(fee));
            onResult.accept(false);
            return;
        }

        String category = categories.categorize(item);
        String tier = tiers.tierOf(item); // resolved once, at creation — browsing never recomputes it
        String displayName = displayNameOf(item);
        long now = System.currentTimeMillis();
        Listing listing = new Listing(UUID.randomUUID(), seller.getUniqueId(), seller.getName(),
                ItemSerialization.serialize(item), displayName, category, tier, type, price,
                now, now + durationMillis, ListingStatus.ACTIVE, 0, null, null, 0);

        async(() -> {
            try {
                db.insertListing(listing);
                sync(() -> {
                    messages.send(seller, "sell.success",
                            "item", displayName, "price", vault.format(price), "fee", vault.format(fee));
                    onResult.accept(true);
                });
            } catch (Exception e) {
                logError("inserting listing", e);
                sync(() -> {
                    vault.deposit(seller, fee); // refund the fee we took
                    onResult.accept(false);
                });
            }
        });
    }

    // ------------------------------------------------------------------ bidding

    public void placeBid(Player bidder, Listing listing, double amount, Runnable onDone) {
        if (!listing.isAuction()) {
            return;
        }
        if (bidder.getUniqueId().equals(listing.sellerId())) {
            messages.send(bidder, "bid.own-listing");
            return;
        }
        double minNext = listing.nextMinBid(config.bidIncrementFor(listing.currentBid()));
        if (amount < minNext) {
            messages.send(bidder, "bid.too-low", "min", vault.format(minNext));
            return;
        }
        if (!vault.has(bidder, amount)) {
            messages.send(bidder, "bid.cannot-afford", "amount", vault.format(amount));
            return;
        }

        UUID bidderId = bidder.getUniqueId();
        String bidderName = bidder.getName();
        async(() -> {
            com.mystipixel.royalauctions.data.AuctionDatabase.BidOutcome outcome;
            try {
                outcome = db.placeBid(listing.id(), bidderId, bidderName, amount);
            } catch (Exception e) {
                logError("placing bid", e);
                sync(onDone);
                return;
            }
            sync(() -> completeBid(bidder, listing, amount, outcome, onDone));
        });
    }

    private void completeBid(Player bidder, Listing listing, double amount,
                             com.mystipixel.royalauctions.data.AuctionDatabase.BidOutcome outcome, Runnable onDone) {
        if (!outcome.success) {
            messages.send(bidder, "OUTBID".equals(outcome.failureReason) ? "bid.outbid-already" : "bid.gone");
            onDone.run();
            return;
        }
        if (!vault.withdraw(bidder, amount)) {
            async(() -> {
                try {
                    db.revertBid(listing.id(), bidder.getUniqueId(),
                            outcome.previousBidderId, outcome.previousBidderName, outcome.previousBid);
                } catch (Exception e) {
                    logError("reverting bid", e);
                }
            });
            messages.send(bidder, "bid.cannot-afford", "amount", vault.format(amount));
            onDone.run();
            return;
        }

        // Report the bid's escrow debit to EconGuard (counterparty = seller) so bid velocity and
        // buyer<->seller links are visible to the central abuse analysis.
        econGuard.report(bidder.getUniqueId(), bidder.getName(), "bid", amount, false,
                listing.sellerId(), listing.sellerName(), listing.displayName());

        // Refund whoever we just outbid.
        if (outcome.previousBidderId != null) {
            OfflinePlayer prev = Bukkit.getOfflinePlayer(outcome.previousBidderId);
            vault.deposit(prev, outcome.previousBid);
            econGuard.report(outcome.previousBidderId, outcome.previousBidderName, "bid-refund",
                    outcome.previousBid, true, null, null, listing.displayName());
            Player prevOnline = Bukkit.getPlayer(outcome.previousBidderId);
            if (prevOnline != null) {
                messages.send(prevOnline, "bid.you-were-outbid",
                        "item", listing.displayName(), "amount", vault.format(outcome.previousBid));
            }
        }

        Player seller = Bukkit.getPlayer(listing.sellerId());
        if (seller != null) {
            messages.send(seller, "bid.new-bid-seller",
                    "item", listing.displayName(), "amount", vault.format(amount), "bidder", bidder.getName());
        }
        messages.send(bidder, "bid.placed", "item", listing.displayName(), "amount", vault.format(amount));
        onDone.run();
    }

    // ------------------------------------------------------------------ buying

    public void purchase(Player buyer, Listing listing, Runnable onDone) {
        if (buyer.getUniqueId().equals(listing.sellerId())) {
            messages.send(buyer, "buy.own-listing");
            return;
        }
        double price = listing.price();
        if (!vault.has(buyer, price)) {
            messages.send(buyer, "buy.cannot-afford", "price", vault.format(price));
            return;
        }

        UUID buyerId = buyer.getUniqueId();
        long now = System.currentTimeMillis();
        async(() -> {
            boolean reserved;
            try {
                reserved = db.markSoldIfActive(listing.id(), buyerId, now);
            } catch (Exception e) {
                logError("reserving listing", e);
                return;
            }
            if (!reserved) {
                sync(() -> {
                    messages.send(buyer, "buy.gone");
                    onDone.run();
                });
                return;
            }
            sync(() -> completePurchase(buyer, listing, price, onDone));
        });
    }

    private void completePurchase(Player buyer, Listing listing, double price, Runnable onDone) {
        if (!vault.withdraw(buyer, price)) {
            // Buyer's balance changed between the affordability check and now; release the listing.
            async(() -> {
                try {
                    db.revertSold(listing.id(), buyer.getUniqueId());
                } catch (Exception e) {
                    logError("reverting sold listing", e);
                }
            });
            messages.send(buyer, "buy.cannot-afford", "price", vault.format(price));
            onDone.run();
            return;
        }

        OfflinePlayer seller = Bukkit.getOfflinePlayer(listing.sellerId());
        vault.deposit(seller, price);

        // Both legs of the sale to EconGuard, each naming the other party for RMT / collusion analysis.
        econGuard.report(buyer.getUniqueId(), buyer.getName(), "buy", price, false,
                listing.sellerId(), listing.sellerName(), listing.displayName());
        econGuard.report(listing.sellerId(), listing.sellerName(), "sale", price, true,
                buyer.getUniqueId(), buyer.getName(), listing.displayName());

        ItemStack item = listing.item();
        String display = listing.displayName();
        boolean toCollection = !config.instantDeliver();
        if (!toCollection) {
            Map<Integer, ItemStack> leftover = buyer.getInventory().addItem(item);
            if (leftover.isEmpty()) {
                messages.send(buyer, "buy.success", "item", display, "price", vault.format(price));
            } else {
                for (ItemStack rest : leftover.values()) {
                    storeCollection(buyer.getUniqueId(), rest, CollectionItem.Reason.PURCHASE);
                }
                messages.send(buyer, "buy.inventory-full-collection");
            }
        } else {
            storeCollection(buyer.getUniqueId(), item, CollectionItem.Reason.PURCHASE);
            messages.send(buyer, "buy.success", "item", display, "price", vault.format(price));
        }

        Player onlineSeller = Bukkit.getPlayer(listing.sellerId());
        if (onlineSeller != null) {
            messages.send(onlineSeller, "buy.sold-notify",
                    "buyer", buyer.getName(), "item", display, "price", vault.format(price));
        }
        onDone.run();
    }

    // ------------------------------------------------------------------ cancelling

    public void cancelListing(Player seller, Listing listing, Runnable onDone) {
        if (!seller.getUniqueId().equals(listing.sellerId())) {
            messages.send(seller, "cancel.not-yours");
            return;
        }
        UUID sellerId = seller.getUniqueId();
        async(() -> {
            boolean ok;
            try {
                ok = db.cancelIfActive(listing.id(), sellerId);
            } catch (Exception e) {
                logError("cancelling listing", e);
                return;
            }
            if (!ok) {
                sync(() -> {
                    messages.send(seller, "cancel.gone");
                    onDone.run();
                });
                return;
            }
            try {
                db.addCollectionItem(new CollectionItem(UUID.randomUUID(), sellerId,
                        listing.itemData(), CollectionItem.Reason.CANCELLED, System.currentTimeMillis()));
            } catch (Exception e) {
                logError("returning cancelled item", e);
            }
            sync(() -> {
                messages.send(seller, "cancel.success");
                onDone.run();
            });
        });
    }

    // ------------------------------------------------------------------ collection claiming

    public void claim(Player player, CollectionItem ci, Runnable onDone) {
        UUID ownerId = player.getUniqueId();
        async(() -> {
            boolean removed;
            try {
                removed = db.removeCollectionItem(ci.id(), ownerId);
            } catch (Exception e) {
                logError("claiming collection item", e);
                return;
            }
            if (!removed) {
                sync(onDone);
                return;
            }
            sync(() -> {
                ItemStack item = ci.item();
                Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
                if (!leftover.isEmpty()) {
                    // No room — put it back so it is never lost.
                    for (ItemStack rest : leftover.values()) {
                        storeCollection(ownerId, rest, ci.reason());
                    }
                    messages.send(player, "collection.full-inventory");
                } else {
                    messages.send(player, "collection.claimed", "item", displayNameOf(item));
                }
                onDone.run();
            });
        });
    }

    // ------------------------------------------------------------------ loaders (async → main callback)

    /**
     * One page of browse results, fetched off the main thread. The stale-category repair runs over the
     * rows on this page; a full sweep also happens at startup, so listings the player never scrolls to
     * still get fixed.
     */
    public void loadBrowsePage(ListingQuery query, int page, int perPage, Consumer<ListingPage> callback) {
        async(() -> {
            try {
                ListingPage result = db.browse(query, page, perPage);
                repairStaleCategories(result.rows());
                sync(() -> callback.accept(result));
            } catch (Exception e) {
                logError("loading listings", e);
                sync(() -> callback.accept(ListingPage.empty()));
            }
        });
    }

    /** One-off pass over every active listing at startup, repairing categories renamed in config. */
    public void repairCategoriesOnStartup() {
        async(() -> {
            try {
                repairStaleCategories(db.activeListings());
            } catch (Exception e) {
                logError("repairing listing categories", e);
            }
        });
    }

    public void loadActiveListings(Consumer<List<Listing>> callback) {
        async(() -> {
            try {
                List<Listing> list = db.activeListings();
                repairStaleCategories(list);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading listings", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    /**
     * A listing's category is stamped when it's created, so renaming a category in config would
     * otherwise strand every existing listing under an id no button matches — visible in "All", but
     * in no category. Re-derive those from the item and persist the fix, once.
     */
    private void repairStaleCategories(List<Listing> listings) {
        int repaired = 0;
        for (Listing l : listings) {
            if (categories.isKnown(l.category())) {
                continue;
            }
            String fresh = categories.categorize(l.item());
            l.category(fresh);
            try {
                db.updateCategory(l.id(), fresh);
                repaired++;
            } catch (Exception e) {
                logError("repairing category for listing " + l.id(), e);
            }
        }
        if (repaired > 0) {
            plugin.getLogger().info("Re-categorised " + repaired
                    + " listing(s) whose category no longer exists.");
        }
    }

    public void loadSellerListings(UUID sellerId, Consumer<List<Listing>> callback) {
        async(() -> {
            try {
                List<Listing> list = db.activeListingsBySeller(sellerId);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading seller listings", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    /** Active auctions this player has bid on (winning or outbid) — backs the View Bids menu. */
    public void loadBidListings(UUID bidderId, Consumer<List<Listing>> callback) {
        async(() -> {
            try {
                List<Listing> list = db.activeListingsBidOnBy(bidderId);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading bid listings", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    public void loadCollection(UUID ownerId, Consumer<List<CollectionItem>> callback) {
        async(() -> {
            try {
                List<CollectionItem> list = db.collectionItems(ownerId);
                sync(() -> callback.accept(list));
            } catch (Exception e) {
                logError("loading collection", e);
                sync(() -> callback.accept(List.of()));
            }
        });
    }

    // ------------------------------------------------------------------ expiry sweep

    /** A finished auction that needs the seller paid and the winner notified (done on the main thread). */
    private record AuctionWin(UUID sellerId, double amount, UUID winnerId, String winnerName, String itemName) {
    }

    public void sweepExpired() {
        long now = System.currentTimeMillis();
        List<Listing> due;
        try {
            due = db.dueExpirations(now);
        } catch (Exception e) {
            logError("scanning for expirations", e);
            return;
        }
        Map<UUID, Integer> perSellerExpired = new HashMap<>();
        List<AuctionWin> wins = new ArrayList<>();

        for (Listing listing : due) {
            try {
                if (listing.type() == ListingType.AUCTION && listing.hasBids()) {
                    // Auction with a winner: hand the item to the top bidder, pay the seller the bid.
                    if (db.markSoldIfActive(listing.id(), listing.topBidderId(), now)) {
                        db.addCollectionItem(new CollectionItem(UUID.randomUUID(), listing.topBidderId(),
                                listing.itemData(), CollectionItem.Reason.PURCHASE, now));
                        wins.add(new AuctionWin(listing.sellerId(), listing.currentBid(),
                                listing.topBidderId(), listing.topBidderName(), listing.displayName()));
                    }
                } else if (db.markExpiredIfActive(listing.id())) {
                    // BIN or a bid-less auction: return the item to the seller.
                    db.addCollectionItem(new CollectionItem(UUID.randomUUID(), listing.sellerId(),
                            listing.itemData(), CollectionItem.Reason.EXPIRED, now));
                    perSellerExpired.merge(listing.sellerId(), 1, Integer::sum);
                }
            } catch (Exception e) {
                logError("finalising a listing", e);
            }
        }

        if (!perSellerExpired.isEmpty() || !wins.isEmpty()) {
            sync(() -> {
                perSellerExpired.forEach((sellerId, count) -> {
                    Player seller = Bukkit.getPlayer(sellerId);
                    if (seller != null) {
                        messages.send(seller, "expiry.expired-notify", "count", String.valueOf(count));
                    }
                });
                for (AuctionWin win : wins) {
                    OfflinePlayer winSeller = Bukkit.getOfflinePlayer(win.sellerId());
                    vault.deposit(winSeller, win.amount());
                    // The winner's outflow was already reported when they bid; log the seller's payout,
                    // naming the winner as counterparty so EconGuard can link the pair.
                    econGuard.report(win.sellerId(), winSeller.getName(), "auction-sale", win.amount(), true,
                            win.winnerId(), win.winnerName(), win.itemName());
                    Player seller = Bukkit.getPlayer(win.sellerId());
                    if (seller != null) {
                        messages.send(seller, "auction.sold-seller",
                                "item", win.itemName(), "amount", vault.format(win.amount()),
                                "buyer", win.winnerName() == null ? "someone" : win.winnerName());
                    }
                    Player winner = Bukkit.getPlayer(win.winnerId());
                    if (winner != null) {
                        messages.send(winner, "auction.won",
                                "item", win.itemName(), "amount", vault.format(win.amount()));
                    }
                }
            });
        }

        try {
            activeCache = db.countActive();
        } catch (Exception e) {
            logError("refreshing active count", e);
        }
    }

    /** Refresh the cached active-listing count off-thread (used at startup). */
    public void refreshActiveCount() {
        async(() -> {
            try {
                activeCache = db.countActive();
            } catch (Exception e) {
                logError("refreshing active count", e);
            }
        });
    }

    public int activeCache() {
        return activeCache;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Escrow an item into its owner's collection <em>on the calling thread</em>.
     *
     * <p>For the shutdown drain only. The normal {@link #storeCollection} path defers to the scheduler,
     * which Bukkit refuses to run for a disabling plugin — the write would be dropped and the item
     * destroyed. Throws so the caller can log a failure loudly rather than lose the item quietly.
     */
    public void escrowToCollection(UUID owner, ItemStack item) throws Exception {
        db.addCollectionItem(new CollectionItem(UUID.randomUUID(), owner,
                ItemSerialization.serialize(item), CollectionItem.Reason.CANCELLED,
                System.currentTimeMillis()));
    }

    private void storeCollection(UUID owner, ItemStack item, CollectionItem.Reason reason) {
        CollectionItem ci = new CollectionItem(UUID.randomUUID(), owner,
                ItemSerialization.serialize(item), reason, System.currentTimeMillis());
        async(() -> {
            try {
                db.addCollectionItem(ci);
            } catch (Exception e) {
                logError("storing collection item", e);
            }
        });
    }

    /** Give an item back to a player, dropping any overflow at their feet. Main thread only. */
    public void returnItem(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private String displayNameOf(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return Text.plain(item.getItemMeta().displayName());
        }
        String name = item.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
