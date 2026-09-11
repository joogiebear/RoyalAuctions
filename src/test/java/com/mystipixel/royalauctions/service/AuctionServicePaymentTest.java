package com.mystipixel.royalauctions.service;
import com.mystipixel.royalauctions.data.*;
import com.mystipixel.royalauctions.hooks.*;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.tier.TierManager;
import com.mystipixel.royalauctions.message.MessageManager;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuctionServicePaymentTest {
    @TempDir Path folder;
    JavaPlugin plugin; AuctionDatabase db; VaultHook vault; EconGuardHook guard;
    PluginConfig config; CategoryManager categories; TierManager tiers; MessageManager messages;
    PaymentJournal journal; PendingPayments payments; AuctionService service;
    Player buyer, seller; Listing listing; MockedStatic<Bukkit> bukkit;
    ItemStack item;
    UUID buyerId = UUID.randomUUID(), sellerId = UUID.randomUUID(), listingId = UUID.randomUUID();
    @BeforeEach void setup() {
        plugin = mock(JavaPlugin.class); db = mock(AuctionDatabase.class); vault = mock(VaultHook.class);
        guard = mock(EconGuardHook.class); config = mock(PluginConfig.class); categories = mock(CategoryManager.class);
        tiers = mock(TierManager.class); messages = mock(MessageManager.class);
        when(plugin.getDataFolder()).thenReturn(folder.toFile()); when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.isEnabled()).thenReturn(true); when(config.instantDeliver()).thenReturn(true);
        journal = new PaymentJournal(folder.resolve("payments"));
        payments = new PendingPayments(journal, vault, guard, Logger.getLogger("test"));
        service = new AuctionService(plugin, db, vault, config, categories, tiers, messages, guard, payments);
        buyer = player("buyer"); seller = player("seller");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getOfflinePlayer(buyerId)).thenReturn(buyer);
        bukkit.when(() -> Bukkit.getOfflinePlayer(sellerId)).thenReturn(seller);
        bukkit.when(() -> Bukkit.getPlayer(sellerId)).thenReturn(seller);
        bukkit.when(() -> Bukkit.getPlayer(buyerId)).thenReturn(buyer);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return mock(BukkitTask.class); });
        when(scheduler.runTaskAsynchronously(eq(plugin), any(Runnable.class))).thenAnswer(i -> { ((Runnable)i.getArgument(1)).run(); return mock(BukkitTask.class); });
        item = mock(ItemStack.class); when(item.getType()).thenReturn(Material.STONE);
        listing = mock(Listing.class); when(listing.id()).thenReturn(listingId);
        doReturn(sellerId).when(listing).sellerId(); when(listing.sellerName()).thenReturn("seller");
        when(listing.item()).thenReturn(item); when(listing.displayName()).thenReturn("test item");
        when(listing.itemData()).thenReturn(new byte[]{1, 2, 3}); when(vault.withdraw(any(), anyDouble())).thenReturn(true);
        when(vault.has(any(), anyDouble())).thenReturn(true);
    }
    @AfterEach void close() { bukkit.close(); }
    Player player(String name) {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(name.equals("buyer") ? buyerId : sellerId); when(p.getName()).thenReturn(name);
        PlayerInventory inventory = mock(PlayerInventory.class); when(p.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack[].class))).thenReturn(new HashMap<>());
        return p;
    }
    void purchase() throws Exception {
        var method = AuctionService.class.getDeclaredMethod("completePurchase", Player.class, Listing.class, double.class, Runnable.class);
        method.setAccessible(true); method.invoke(service, buyer, listing, 50d, (Runnable)() -> {});
    }
    @Test void rejectedSellerCreditRemainsOwedAndIsNotAuditedAsPaid() throws Exception {
        when(vault.deposit(seller, 50)).thenReturn(false);
        purchase();
        assertEquals(1, journal.pending().size());
        verify(guard, never()).report(eq(sellerId), any(), eq("sale"), anyDouble(), eq(true), any(), any(), any());
        verify(messages).send(seller, "payment-pending");
        verify(buyer.getInventory()).addItem(any(ItemStack[].class));
    }
    @Test void pendingSellerCreditRetriesAfterRestartWithoutChargingBuyerAgain() throws Exception {
        when(vault.deposit(seller, 50)).thenReturn(false, true);
        purchase();
        new PendingPayments(new PaymentJournal(folder.resolve("payments")), vault, guard, Logger.getLogger("test")).retryRejected();
        payments.retryRejected();
        verify(vault).withdraw(buyer, 50);
        verify(vault, times(2)).deposit(seller, 50);
        assertTrue(journal.pending().isEmpty());
        verify(guard).report(eq(sellerId), eq("seller"), eq("sale"), eq(50d), eq(true), eq(buyerId), eq("buyer"), anyString());
    }
    @Test void rejectedOutbidRefundIsPersistedWithoutClaimingRefundSuccess() throws Exception {
        var factory = AuctionDatabase.BidOutcome.class.getDeclaredMethod("ok", UUID.class, String.class, double.class, boolean.class, long.class);
        factory.setAccessible(true);
        var outcome = (AuctionDatabase.BidOutcome)factory.invoke(null, sellerId, "seller", 30d, false, 100L);
        var method = AuctionService.class.getDeclaredMethod("completeBid", Player.class, Listing.class, double.class, AuctionDatabase.BidOutcome.class, Runnable.class);
        method.setAccessible(true); method.invoke(service, buyer, listing, 50d, outcome, (Runnable)() -> {});
        assertEquals(1, journal.pending().size());
        verify(guard, never()).report(any(), any(), eq("bid-refund"), anyDouble(), eq(true), any(), any(), any());
        verify(messages).send(seller, "payment-pending");
    }
    @Test void expiryPayoutRejectionLeavesRecoverableCredit() throws Exception {
        when(listing.type()).thenReturn(ListingType.AUCTION); when(listing.hasBids()).thenReturn(true);
        doReturn(buyerId).when(listing).topBidderId(); when(listing.currentBid()).thenReturn(50d);
        when(db.dueExpirations(anyLong())).thenReturn(List.of(listing));
        when(db.markAuctionSoldIfUnchanged(eq(listingId), eq(buyerId), anyLong(), anyInt())).thenReturn(true);
        service.sweepExpired();
        assertEquals(1, journal.pending().size());
        verify(guard, never()).report(any(), any(), eq("auction-sale"), anyDouble(), eq(true), any(), any(), any());
        when(vault.deposit(seller, 50)).thenReturn(true); payments.retryRejected();
        assertTrue(journal.pending().isEmpty());
    }
    @Test void listingInsertFailureRetainsRejectedFeeRefund() throws Exception {
        when(config.maxPerPlayer()).thenReturn(-1); when(config.feeFor(50)).thenReturn(5d);
        doThrow(new java.sql.SQLException("insert rejected")).when(db).insertListing(any());
        try (var serialization = mockStatic(ItemSerialization.class)) {
            serialization.when(() -> ItemSerialization.serialize(item)).thenReturn(new byte[]{1, 2, 3});
            var method = AuctionService.class.getDeclaredMethod("finishListing", Player.class, ItemStack.class, double.class, ListingType.class, long.class, int.class, Consumer.class);
            method.setAccessible(true); method.invoke(service, seller, item, 50d, ListingType.BIN, 1000L, 0, (Consumer<Boolean>) ok -> assertFalse(ok));
        }
        assertEquals(1, journal.pending().size());
        assertEquals("listing-fee-refund", journal.pending().values().iterator().next().getProperty("reason"));
    }
    @Test void unknownSellerCreditDoesNotRetryOnTickOrRestart() throws Exception {
        when(vault.deposit(seller, 50)).thenThrow(new IllegalStateException("provider may have paid"));
        purchase(); payments.retryRejected();
        new PendingPayments(new PaymentJournal(folder.resolve("payments")), vault, guard, Logger.getLogger("test")).retryRejected();
        verify(vault).deposit(seller, 50);
        assertEquals("IN_FLIGHT", journal.pending().values().iterator().next().getProperty("leg.credit.status"));
    }
    @Test void repeatedCreditRequestDoesNotDuplicatePaymentOrSuccessAudit() {
        when(vault.deposit(seller, 50)).thenReturn(true);
        UUID id = PendingPayments.key("sale", listingId.toString());
        assertTrue(payments.credit(id, sellerId, 50, "sale", buyerId));
        assertTrue(payments.credit(id, sellerId, 50, "sale", buyerId));
        verify(vault).deposit(seller, 50);
        verify(guard).report(any(), any(), eq("sale"), eq(50d), eq(true), any(), any(), any());
    }
}
