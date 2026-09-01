package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.data.AuctionDatabase;
import com.mystipixel.royalauctions.data.OfflineEvent;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Replays the auction-house events that happened while a player was offline — sales, outbids, won
 * auctions, expired listings — as a short summary shortly after they join. Without this, money and
 * refunds move silently while they're away and items appear in the collection with no explanation.
 *
 * <p>Delivery is drain-then-send with a re-queue: events are read and deleted off-thread, and if the
 * player disconnected again before the main-thread send, they are written back rather than lost.
 * The delay after join is so the summary lands after the join-message noise, not inside it.
 */
public final class OfflineEventNotifier implements Listener {

    private static final long DELAY_TICKS = 60L;
    private static final int MAX_LINES = 8;

    private final JavaPlugin plugin;
    private final AuctionDatabase db;
    private final MessageManager messages;
    private final VaultHook vault;

    public OfflineEventNotifier(JavaPlugin plugin, AuctionDatabase db, MessageManager messages, VaultHook vault) {
        this.plugin = plugin;
        this.db = db;
        this.messages = messages;
        this.vault = vault;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> deliver(id), DELAY_TICKS);
    }

    private void deliver(UUID id) {
        List<OfflineEvent> events;
        try {
            events = db.drainEvents(id);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read offline auction events for " + id, e);
            return;
        }
        if (events.isEmpty()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                requeue(id, events);             // bounced before delivery — keep the events
                return;
            }
            send(player, events);
        });
    }

    private void requeue(UUID id, List<OfflineEvent> events) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            for (OfflineEvent event : events) {
                try {
                    db.addEvent(id, event.type(), event.item(), event.amount(), event.createdAt());
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Could not re-queue an offline auction event", e);
                }
            }
        });
    }

    private void send(Player player, List<OfflineEvent> events) {
        messages.send(player, "away.header");

        // Expired listings collapse into one count line however many sweeps produced them.
        long expired = 0;
        for (OfflineEvent event : events) {
            if (OfflineEvent.EXPIRED.equals(event.type())) {
                expired += (long) event.amount();
            }
        }

        int lines = 0;
        int hidden = 0;
        for (OfflineEvent event : events) {
            if (OfflineEvent.EXPIRED.equals(event.type())) {
                continue;
            }
            if (lines >= MAX_LINES) {
                hidden++;
                continue;
            }
            String item = event.item() == null ? "an item" : event.item();
            switch (event.type()) {
                case OfflineEvent.SOLD -> messages.send(player, "away.sold",
                        "item", item, "amount", vault.format(event.amount()));
                case OfflineEvent.OUTBID -> messages.send(player, "away.outbid",
                        "item", item, "amount", vault.format(event.amount()));
                case OfflineEvent.WON -> messages.send(player, "away.won",
                        "item", item, "amount", vault.format(event.amount()));
                default -> {
                    continue;                    // a type from a future version — skip, don't crash
                }
            }
            lines++;
        }
        if (expired > 0) {
            messages.send(player, "away.expired", "count", String.valueOf(expired));
        }
        if (hidden > 0) {
            messages.send(player, "away.more", "count", String.valueOf(hidden));
        }
    }
}
