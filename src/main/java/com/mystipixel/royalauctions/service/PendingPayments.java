package com.mystipixel.royalauctions.service;

import com.mystipixel.royalauctions.data.PaymentJournal;
import com.mystipixel.royalauctions.hooks.EconGuardHook;
import com.mystipixel.royalauctions.hooks.VaultHook;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.*;

/** Main-thread credit obligations; known rejections retry, ambiguous outcomes never do. */
public final class PendingPayments {
    private final PaymentJournal journal;
    private final VaultHook vault;
    private final EconGuardHook guard;
    private final Logger logger;
    private int retryOffset;

    public PendingPayments(PaymentJournal journal, VaultHook vault, EconGuardHook guard, Logger logger) {
        this.journal = journal; this.vault = vault; this.guard = guard; this.logger = logger;
        if (!journal.pending().isEmpty()) logger.warning(journal.pending().size()
                + " payment receipt(s) pending. Only explicitly rejected credits will be retried.");
    }

    public static UUID key(String action, String source) {
        return UUID.nameUUIDFromBytes((action + ":" + source).getBytes(StandardCharsets.UTF_8));
    }

    public boolean credit(UUID id, UUID recipient, double amount, String action, UUID counterparty) {
        return credit(id, recipient, amount, action, counterparty, id.toString(), "payment:" + id);
    }

    public boolean credit(UUID id, UUID recipient, double amount, String action, UUID counterparty,
                          String source, String itemName) {
        try {
            journal.begin(id, recipient, counterparty, action,
                    Map.of("source", source, "item", Objects.toString(itemName, "")));
            Properties before = journal.read(id);
            OfflinePlayer player = Bukkit.getOfflinePlayer(recipient);
            boolean paid = journal.attempt(id, "credit", recipient, amount, true,
                    () -> vault.deposit(player, amount));
            if (!paid) return false;
            journal.complete(id);
            if (!"SUCCESS".equals(before.getProperty("leg.credit.status"))) {
                // Success-only reporting, including credits first accepted by a later retry.
                try {
                    guard.report(recipient, player.getName(), action, amount, true, counterparty,
                            counterparty == null ? null : Bukkit.getOfflinePlayer(counterparty).getName(),
                            itemName);
                } catch (RuntimeException auditFailure) {
                    logger.log(Level.WARNING, "Payment succeeded but audit failed: " + id, auditFailure);
                }
            }
            return true;
        } catch (RuntimeException e) {
            logger.log(Level.SEVERE, "Payment held: receipt=" + id + " recipient=" + recipient
                    + " amount=" + amount + " action=" + action
                    + ". Inspect payments/pending; do not blindly replay an unknown outcome.", e);
            return false;
        }
    }

    /** At most ten provider calls per pass; successful and unknown payments are not paid again. */
    public void retryRejected() {
        int attempted = 0;
        var entries = new ArrayList<>(journal.pending().entrySet());
        if (entries.isEmpty()) { retryOffset = 0; return; }
        int start = retryOffset % entries.size();
        for (int offset = 0; offset < entries.size(); offset++) {
            int index = (start + offset) % entries.size();
            var entry = entries.get(index);
            Properties p = entry.getValue();
            String status = p.getProperty("leg.credit.status");
            if (!"REJECTED".equals(status) && !"SUCCESS".equals(status)) continue;
            if (attempted++ >= 10) { retryOffset = index; break; }
            retryOffset = (index + 1) % entries.size();
            UUID other = p.getProperty("other", "").isEmpty() ? null : UUID.fromString(p.getProperty("other"));
            credit(entry.getKey(), UUID.fromString(p.getProperty("owner")),
                    Double.parseDouble(p.getProperty("leg.credit.amount")), p.getProperty("reason"), other,
                    p.getProperty("context.source", entry.getKey().toString()),
                    p.getProperty("context.item", "payment:" + entry.getKey()));
        }
    }
}
