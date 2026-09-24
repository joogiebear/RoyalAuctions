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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Replays the auction-house events that happened while a player was offline — sales, outbids, won
 * auctions, expired listings — as a short summary shortly after they join. Without this, money and
 * refunds move silently while they're away and items appear in the collection with no explanation.
 *
 * <p>Delivery is read, send, then delete: events are read off-thread, shown on the main thread, and
 * only then deleted by row id. A player who disconnects before the send, or a crash at any point,
 * leaves the events queued for next time. One delivery per player runs at a time, so a join and a
 * live notification arriving together cannot show the same events twice.
 * The delay after join is so the summary lands after the join-message noise, not inside it.
 */
public final class OfflineEventNotifier implements Listener {

    private static final long DELAY_TICKS = 60L;
    private static final int MAX_LINES = 8;

    private final JavaPlugin plugin;
    private final AuctionDatabase db;
    private final MessageManager messages;
    private final VaultHook vault;
    private final Workers workers;
    private final Set<UUID> delivering = ConcurrentHashMap.newKeySet();
    private final Set<UUID> again = ConcurrentHashMap.newKeySet();

    public OfflineEventNotifier(JavaPlugin plugin, AuctionDatabase db, MessageManager messages, VaultHook vault,
                                Workers workers) {
        this.plugin = plugin;
        this.db = db;
        this.messages = messages;
        this.vault = vault;
        this.workers = workers;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> start(id), DELAY_TICKS);
    }

    /** Trigger the same queue for an online recipient; leave offline players' notices untouched. */
    public void notifyOnline(UUID id) {
        workers.sync(() -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) start(id);
        });
    }

    private void start(UUID id) {
        if (workers.closing()) return;
        if (!delivering.add(id)) {
            again.add(id);                       // picked up when the running delivery finishes
            return;
        }
        workers.async(() -> deliver(id));
    }

    private void deliver(UUID id) {
        Map<Long, OfflineEvent> events;
        try {
            events = db.peekEvents(id);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read offline auction events for " + id, e);
            finish(id);
            return;
        }
        if (events.isEmpty()) {
            finish(id);
            return;
        }
        workers.sync(() -> {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                finish(id);                      // bounced before delivery — the events stay queued
                return;
            }
            send(player, List.copyOf(events.values()));
            List<Long> shown = new ArrayList<>(events.keySet());
            workers.async(() -> {
                try {
                    db.deleteEvents(shown);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Could not clear delivered offline auction events", e);
                } finally {
                    finish(id);
                }
            });
        });
    }

    private void finish(UUID id) {
        delivering.remove(id);
        if (again.remove(id)) start(id);
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
