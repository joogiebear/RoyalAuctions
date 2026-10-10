package com.mystipixel.royalauctions.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TextInputTest {

    private static final class FakeHost implements TextInput.Host {
        boolean enabled = true;
        final List<Runnable> ticks = new ArrayList<>();
        final List<Runnable> timeouts = new ArrayList<>();
        final List<Consumer<String>> done = new ArrayList<>();
        final List<Runnable> cancel = new ArrayList<>();
        final List<Player> closed = new ArrayList<>();

        @Override public boolean enabled() { return enabled; }
        @Override public void nextTick(Runnable task) { ticks.add(task); }

        @Override public Runnable later(Runnable task, long ticks) {
            assertEquals(TextInput.TIMEOUT_TICKS, ticks);
            timeouts.add(task);
            return () -> timeouts.remove(task);
        }

        @Override public void show(Player player, List<String> lines, Consumer<String> done, Runnable cancel) {
            this.done.add(done);
            this.cancel.add(cancel);
        }

        @Override public void close(Player player) { closed.add(player); }

        void runTicks() {
            List<Runnable> now = new ArrayList<>(ticks);
            ticks.clear();
            now.forEach(Runnable::run);
        }
    }

    private FakeHost host;
    private TextInput input;
    private Player player;
    private List<String> answers;

    @BeforeEach
    void setUp() {
        host = new FakeHost();
        input = new TextInput(host);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        answers = new ArrayList<>();
    }

    private void ask() {
        input.request(player, List.of("Search by name", "blank = show all"), answers::add);
    }

    @Test
    void typedTextIsCleanedAndCappedServerSide() {
        assertEquals("", TextInput.sanitize(null));
        assertEquals("casque", TextInput.sanitize("  casque \n"));
        assertEquals("ab", TextInput.sanitize("a\u0000\u0007b"));
        assertEquals("ab", TextInput.sanitize("a\u0085\u009Fb"));
        assertEquals("' OR '1'='1; DROP TABLE x; --", TextInput.sanitize("' OR '1'='1; DROP TABLE x; --"),
                "Kept verbatim: callers bind it, never build SQL from it");
        assertEquals(TextInput.MAX_LENGTH, TextInput.sanitize("x".repeat(10_000)).length());
        String emoji = TextInput.sanitize("😀".repeat(TextInput.MAX_LENGTH + 5));
        assertEquals(TextInput.MAX_LENGTH, emoji.codePointCount(0, emoji.length()), "Never splits a surrogate pair");
        String mixed = TextInput.sanitize("a".repeat(TextInput.MAX_LENGTH - 1) + "😀😀");
        assertFalse(Character.isHighSurrogate(mixed.charAt(mixed.length() - 1)));
        assertTrue(mixed.endsWith("😀"));
    }

    @Test
    void doneAnswersOnceWithCleanedTextOnTheNextTick() {
        ask();
        host.done.get(0).accept("  diamond \u0007");
        assertTrue(answers.isEmpty(), "Answer waits for the main thread");
        host.runTicks();
        assertEquals(List.of("diamond"), answers);
        assertTrue(host.timeouts.isEmpty(), "Timeout cancelled");
        assertFalse(input.isWaiting(player));

        host.done.get(0).accept("again");
        host.cancel.get(0).run();
        host.runTicks();
        assertEquals(List.of("diamond"), answers);
    }

    @Test
    void doneWithNothingTypedAnswersEmpty() {
        ask();
        host.done.get(0).accept("");
        host.runTicks();
        assertEquals(List.of(""), answers);
    }

    @Test
    void cancelOrEscapeAnswersNull() {
        ask();
        host.cancel.get(0).run();
        host.runTicks();
        assertEquals(Arrays.asList((String) null), answers);
    }

    @Test
    void quitAnswersNull() {
        ask();
        input.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null,
                PlayerQuitEvent.QuitReason.DISCONNECTED));
        host.runTicks();
        assertEquals(Arrays.asList((String) null), answers);
        host.done.get(0).accept("late");
        host.runTicks();
        assertEquals(1, answers.size());
    }

    @Test
    void deathAnswersNull() {
        ask();
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        input.onDeath(death);
        host.runTicks();
        assertEquals(Arrays.asList((String) null), answers);
    }

    @Test
    void openingAnotherInventoryAnswersNull() {
        ask();
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getPlayer()).thenReturn(player);
        input.onInventoryOpen(open);
        host.runTicks();
        assertEquals(Arrays.asList((String) null), answers);
    }

    @Test
    void newPromptSupersedesTheOldOneAndItsButtonsGoDead() {
        List<String> first = new ArrayList<>();
        List<Boolean> waitingWhenTold = new ArrayList<>();
        input.request(player, List.of("a"), text -> {
            first.add(text);
            waitingWhenTold.add(input.isWaiting(player));
        });
        ask();
        assertEquals(Arrays.asList((String) null), first);
        assertEquals(List.of(true), waitingWhenTold, "The old caller can tell a newer prompt is up");
        assertEquals(1, host.timeouts.size(), "Old timeout cancelled");

        host.done.get(0).accept("stale");
        host.runTicks();
        assertEquals(1, first.size());
        assertTrue(answers.isEmpty(), "Old token does not answer the new prompt");

        host.done.get(1).accept("fresh");
        host.runTicks();
        assertEquals(List.of("fresh"), answers);
    }

    @Test
    void timeoutClosesTheDialogAndAnswersNull() {
        ask();
        host.timeouts.get(0).run();
        assertEquals(List.of(player), host.closed);
        assertEquals(Arrays.asList((String) null), answers);
        host.done.get(0).accept("late");
        host.runTicks();
        assertEquals(1, answers.size());
    }

    @Test
    void dismissalQueuedBeforeANewPromptDoesNotAnswerIt() {
        ask();
        InventoryOpenEvent open = mock(InventoryOpenEvent.class);
        when(open.getPlayer()).thenReturn(player);
        input.onInventoryOpen(open);
        List<String> second = new ArrayList<>();
        input.request(player, List.of("b"), second::add);
        host.runTicks();
        assertTrue(second.isEmpty());
        assertTrue(input.isWaiting(player));
    }

    @Test
    void shutdownClosesDialogsWithoutCallingBack() {
        ask();
        input.shutdown();
        assertEquals(List.of(player), host.closed);
        assertTrue(host.timeouts.isEmpty());
        assertFalse(input.isWaiting(player));

        host.enabled = false;
        assertDoesNotThrow(() -> host.done.get(0).accept("after disable"));
        assertDoesNotThrow(() -> host.cancel.get(0).run());
        host.runTicks();
        assertTrue(answers.isEmpty());

        ask();
        assertTrue(host.done.size() == 1, "No new prompts after shutdown");
    }

    @Test
    void clickAfterThePluginIsDisabledDoesNothing() {
        ask();
        host.enabled = false;
        host.done.get(0).accept("late");
        assertTrue(host.ticks.isEmpty(), "Nothing scheduled on a disabled plugin");
        assertTrue(answers.isEmpty());
    }
}
