package com.mystipixel.royalauctions.service;
import com.mystipixel.royalauctions.data.PaymentJournal;
import com.mystipixel.royalauctions.hooks.*;
import org.bukkit.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PendingPaymentsTest {
    @TempDir Path folder;
    @Test void retryBudgetIsBoundedAndDoesNotStarveLaterRejectedPayments() {
        var journal = new PaymentJournal(folder);
        var vault = mock(VaultHook.class); var guard = mock(EconGuardHook.class);
        var service = new PendingPayments(journal, vault, guard, Logger.getLogger("test"));
        Map<UUID, OfflinePlayer> players = new HashMap<>();
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenAnswer(i -> players.get(i.getArgument(0)));
            for (int i = 0; i < 11; i++) {
                UUID id = UUID.randomUUID(); var player = mock(OfflinePlayer.class); players.put(id, player);
                assertFalse(service.credit(UUID.randomUUID(), id, 1, "sale", null));
            }
            clearInvocations(vault);
            service.retryRejected(); verify(vault, times(10)).deposit(any(), eq(1d));
            service.retryRejected(); verify(vault, times(20)).deposit(any(), eq(1d));
            for (var player : players.values()) verify(vault, atLeastOnce()).deposit(player, 1);
        }
    }
    @Test void auditFailureCannotConvertConfirmedCreditIntoPaymentFailure() {
        var journal = new PaymentJournal(folder);
        var vault = mock(VaultHook.class); var guard = mock(EconGuardHook.class);
        var service = new PendingPayments(journal, vault, guard, Logger.getLogger("test"));
        UUID recipient = UUID.randomUUID(); var player = mock(OfflinePlayer.class);
        when(vault.deposit(player, 1)).thenReturn(true);
        doThrow(new IllegalStateException("audit unavailable")).when(guard)
                .report(any(), any(), anyString(), anyDouble(), anyBoolean(), any(), any(), any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer(recipient)).thenReturn(player);
            UUID id = UUID.randomUUID();
            assertTrue(service.credit(id, recipient, 1, "sale", null));
            assertTrue(service.credit(id, recipient, 1, "sale", null));
            verify(vault).deposit(player, 1);
            assertTrue(journal.pending().isEmpty());
        }
    }
    @Test void unplannedInterruptedIntentIsNotAutomaticallyPaid() {
        var journal = new PaymentJournal(folder);
        journal.begin(UUID.randomUUID(), UUID.randomUUID(), null, "sale");
        var vault = mock(VaultHook.class);
        new PendingPayments(journal, vault, mock(EconGuardHook.class), Logger.getLogger("test")).retryRejected();
        verifyNoInteractions(vault);
    }
}
