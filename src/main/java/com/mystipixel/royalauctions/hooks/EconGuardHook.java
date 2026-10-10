package com.mystipixel.royalauctions.hooks;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Optional integration with the EconGuard audit core.
 *
 * <p>Wired by reflection against EconGuard's flat {@code EconGuard.record(...)} bridge, so RoyalAuctions
 * carries no build-time dependency on EconGuard and builds standalone. The bridge is resolved once at
 * construction; when EconGuard is absent (or too old to expose it) every call is a safe no-op.
 *
 * <p>Auctions are the suite's largest player-to-player money mover, so reporting here is what lets
 * EconGuard link buyers and sellers for collusion / RMT analysis. Bids and purchases first pass
 * {@link #allow}, EconGuard's {@code allowTrade} veto, which refuses flagged players when its
 * {@code enforcement.block-flagged-trades} is on.
 */
public final class EconGuardHook {

    private static final String SOURCE_AUCTION = "auction";

    private final Method bridge;
    private final Method veto;
    private final Logger logger;
    private final AtomicBoolean vetoFailureLogged = new AtomicBoolean();

    public EconGuardHook(Logger logger) {
        this.logger = logger;
        Method resolved = null;
        Method resolvedVeto = null;
        if (Bukkit.getPluginManager().isPluginEnabled("EconGuard")) {
            try {
                Class<?> econGuard = Class.forName("com.mystipixel.econguard.api.EconGuard");
                resolved = econGuard.getMethod("record",
                        UUID.class, String.class, String.class, String.class,
                        double.class, boolean.class, double.class,
                        UUID.class, String.class, String.class, String.class);
                try {
                    resolvedVeto = econGuard.getMethod("allowTrade", UUID.class);
                } catch (NoSuchMethodException oldEconGuard) {
                    // Predates the veto bridge: reporting still works, allow() permits.
                }
            } catch (Throwable ignored) {
                // EconGuard missing or predates the bridge - stay a no-op.
            }
        }
        this.bridge = resolved;
        this.veto = resolvedVeto;
    }

    public boolean isPresent() {
        return bridge != null;
    }

    /** Whether the installed EconGuard exposes the pre-trade veto (its enforcement decides the rest). */
    public boolean hasVeto() {
        return veto != null;
    }

    /** Whether EconGuard lets this player trade right now. Any failure to answer permits. */
    public boolean allow(UUID player) {
        if (veto == null) {
            return true;
        }
        try {
            return (boolean) veto.invoke(null, player);
        } catch (Throwable failure) {
            if (vetoFailureLogged.compareAndSet(false, true)) {
                logger.log(Level.WARNING, "EconGuard's trade veto failed; auctions are allowed until it answers again."
                        + " Further failures are not logged.", failure);
            }
            return true;
        }
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
