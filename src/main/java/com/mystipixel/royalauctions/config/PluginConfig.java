package com.mystipixel.royalauctions.config;

import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.data.SortOrder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/** Typed, reloadable view over config.yml. */
public final class PluginConfig {

    /** One selectable duration on the "Auction Duration" screen. */
    public record DurationOption(String label, int hours, Material icon) {
    }

    private final JavaPlugin plugin;

    private int maxPerPlayer;
    private double minPrice;
    private double maxPrice;
    private double feePercent;
    private double feeMinimum;
    private boolean instantDeliver;

    private final List<DurationOption> durations = new ArrayList<>();
    private int defaultDurationHours;
    private ListingType defaultType;

    private double bidMinIncrement;
    private double bidIncrementPercent;

    private String browseTitle;
    private String collectionTitle;
    private String listingsTitle;
    private String confirmTitle;
    private String createTitle;
    private String durationTitle;
    private String confirmAuctionTitle;
    private String bidTitle;
    private int guiRows;

    private SortOrder defaultSort;
    private long expirySweepTicks;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        ConfigurationSection c = plugin.getConfig();

        ConfigurationSection listings = section(c, "listings");
        this.maxPerPlayer = listings.getInt("max-per-player", 7);
        this.minPrice = listings.getDouble("min-price", 1.0);
        this.maxPrice = listings.getDouble("max-price", -1);
        this.instantDeliver = listings.getBoolean("instant-deliver-purchases", true);
        ConfigurationSection fee = section(listings, "fee");
        this.feePercent = fee.getDouble("percent", 0.02);
        this.feeMinimum = fee.getDouble("minimum", 5.0);

        loadDurations(listings);
        this.defaultDurationHours = listings.getInt("default-duration-hours", 24);
        this.defaultType = ListingType.fromString(listings.getString("default-type"), ListingType.BIN);

        ConfigurationSection bidding = section(c, "bidding");
        this.bidMinIncrement = bidding.getDouble("min-increment", 10.0);
        this.bidIncrementPercent = bidding.getDouble("increment-percent", 0.05);

        ConfigurationSection gui = section(c, "gui");
        this.browseTitle = gui.getString("browse-title", "&8Auction House");
        this.collectionTitle = gui.getString("collection-title", "&8Collection");
        this.listingsTitle = gui.getString("listings-title", "&8Manage Auctions");
        this.confirmTitle = gui.getString("confirm-title", "&8Confirm Purchase");
        this.createTitle = gui.getString("create-title", "&8Create Auction");
        this.durationTitle = gui.getString("duration-title", "&8Auction Duration");
        this.confirmAuctionTitle = gui.getString("confirm-auction-title", "&8Confirm Auction");
        this.bidTitle = gui.getString("bid-title", "&8Place Bid");
        this.guiRows = Math.min(6, Math.max(2, gui.getInt("rows", 6)));

        this.defaultSort = SortOrder.fromString(c.getString("default-sort"), SortOrder.NEWEST);
        this.expirySweepTicks = Math.max(1, c.getLong("expiry-sweep-minutes", 5)) * 60L * 20L;
    }

    private void loadDurations(ConfigurationSection listings) {
        durations.clear();
        List<?> raw = listings.getList("durations");
        if (raw != null) {
            for (Object entry : raw) {
                if (!(entry instanceof java.util.Map<?, ?> map)) {
                    continue;
                }
                Object labelObj = map.get("label");
                String label = labelObj == null ? "Duration" : String.valueOf(labelObj);
                int hours = map.get("hours") instanceof Number n ? n.intValue() : 24;
                Object iconObj = map.get("icon");
                Material icon = Material.matchMaterial(iconObj == null ? "CLOCK" : String.valueOf(iconObj));
                durations.add(new DurationOption(label, hours, icon == null ? Material.CLOCK : icon));
            }
        }
        if (durations.isEmpty()) {
            durations.add(new DurationOption("1 Day", 24, Material.CLOCK));
        }
    }

    private ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection s = parent.getConfigurationSection(path);
        return s != null ? s : parent.createSection(path);
    }

    public ConfigurationSection storageSection() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("storage");
        return s != null ? s : plugin.getConfig().createSection("storage");
    }

    public ConfigurationSection categoriesSection() {
        return plugin.getConfig().getConfigurationSection("categories");
    }

    /** Compute the listing fee for a given sale price. */
    public double feeFor(double price) {
        return Math.max(feeMinimum, price * feePercent);
    }

    public List<DurationOption> durations() {
        return durations;
    }

    public int defaultDurationHours() {
        return defaultDurationHours;
    }

    public ListingType defaultType() {
        return defaultType;
    }

    public long durationMillisFor(int hours) {
        return Math.max(1, hours) * 3_600_000L;
    }

    public double bidMinIncrement() {
        return bidMinIncrement;
    }

    public double bidIncrementPercent() {
        return bidIncrementPercent;
    }

    /** Minimum raise over the current bid: the larger of the flat and percentage increments. */
    public double bidIncrementFor(double currentBid) {
        return Math.max(bidMinIncrement, currentBid * bidIncrementPercent);
    }

    public int maxPerPlayer() {
        return maxPerPlayer;
    }

    public double feePercent() {
        return feePercent;
    }

    public double feeMinimum() {
        return feeMinimum;
    }

    public double minPrice() {
        return minPrice;
    }

    public double maxPrice() {
        return maxPrice;
    }

    public boolean hasMaxPrice() {
        return maxPrice > 0;
    }

    public boolean instantDeliver() {
        return instantDeliver;
    }

    public String browseTitle() {
        return browseTitle;
    }

    public String collectionTitle() {
        return collectionTitle;
    }

    public String listingsTitle() {
        return listingsTitle;
    }

    public String confirmTitle() {
        return confirmTitle;
    }

    public String createTitle() {
        return createTitle;
    }

    public String durationTitle() {
        return durationTitle;
    }

    public String confirmAuctionTitle() {
        return confirmAuctionTitle;
    }

    public String bidTitle() {
        return bidTitle;
    }

    public int guiRows() {
        return guiRows;
    }

    public SortOrder defaultSort() {
        return defaultSort;
    }

    public long expirySweepTicks() {
        return expirySweepTicks;
    }
}
