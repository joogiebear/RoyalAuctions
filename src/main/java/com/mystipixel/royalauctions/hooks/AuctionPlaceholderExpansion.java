package com.mystipixel.royalauctions.hooks;

import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.service.AuctionService;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * Placeholders:
 *   %royalauctions_active%       total active listings (cached)
 *   %royalauctions_max_listings% per-player listing limit
 *   %royalauctions_fee_percent%  listing fee percentage
 *   %royalauctions_min_price%    minimum listing price
 */
public final class AuctionPlaceholderExpansion extends PlaceholderExpansion {

    private final AuctionService service;
    private final PluginConfig config;
    private final VaultHook vault;
    private final String version;

    public AuctionPlaceholderExpansion(AuctionService service, PluginConfig config, VaultHook vault, String version) {
        this.service = service;
        this.config = config;
        this.vault = vault;
        this.version = version;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "royalauctions";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase()) {
            case "active" -> String.valueOf(service.activeCache());
            case "max_listings" -> String.valueOf(config.maxPerPlayer());
            case "fee_percent" -> String.format("%.1f", config.feePercent() * 100);
            case "min_price" -> vault.format(config.minPrice());
            default -> null;
        };
    }
}
