package com.mystipixel.royalauctions.hooks;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Optional integration with the EconGuard audit core.
 *
 * <p>Wired by reflection against EconGuard's flat {@code EconGuard.record(...)} bridge, so RoyalAuctions
 * carries no build-time dependency on EconGuard and builds standalone. The bridge is resolved once at
 * construction; when EconGuard is absent (or too old to expose it) every call is a safe no-op.
 *
 * <p>Auctions are the suite's largest player-to-player money mover, so reporting here is what lets
 * EconGuard link buyers and sellers for collusion / RMT analysis.
 */
public final class EconGuardHook {

    private static final String SOURCE_AUCTION = "auction";

    private final Method bridge;

    public EconGuardHook() {
        Method resolved = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            try {
                Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
                resolved = econGuard.getMethod("record",
                        UUID.class, String.class, String.class, String.class,
                        double.class, boolean.class, double.class,
                        UUID.class, String.class, String.class, String.class);
            } catch (Throwable ignored) {
                // EconGuard missing or predates the bridge - stay a no-op.
            }
        }
        this.bridge = resolved;
    }

    public boolean isPresent() {
        return bridge != null;
    }

    /**
     * Fire-and-forget report of an auction money movement. {@code amount} is a positive magnitude;
     * {@code incoming} is true when the money arrives to {@code player}. {@code counterparty} is the
     * other side of the trade (nullable - e.g. an escrow refund has none). Never throws into the caller:
     * an audit failure must never affect committed money.
     */
    public void report(UUID player, String playerName, String action, double amount, boolean incoming,
                       UUID counterparty, String counterpartyName, String item) {
        if (bridge == null) {
            return;
        }
        try {
            bridge.invoke(null, player, playerName, SOURCE_AUCTION, action, amount, incoming,
                    Double.NaN, counterparty, counterpartyName, item, null);
        } catch (Throwable ignored) {
        }
    }
}
