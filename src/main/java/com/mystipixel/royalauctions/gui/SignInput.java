package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Sign-based text entry (Hypixel-style): a throwaway sign is placed at the player's feet, opened
 * with Paper's {@code openSign} API, and whatever they type on the top line is read back via
 * {@link SignChangeEvent}. Uses only official Paper API so it survives version changes better than
 * NMS/packet approaches.
 *
 * <p>The sign is a real block, so the borrowed spot is guarded until it is given back: nobody can
 * break, burn, blow up or push it, only the prompted player's edit is accepted, and the original
 * block is restored on submit, on quit, when the prompt is replaced or times out, and on shutdown.
 * A restore only overwrites the block if it is still our sign.
 *
 * The callback runs on the main thread. It receives the typed text, or {@code null} if the sign
 * could not be opened or the prompt timed out (so callers can fall back).
 */
public final class SignInput implements Listener {

    /** A prompt left open this long is closed and its block restored. */
    private static final long TIMEOUT_TICKS = 20L * 120L;

    private static final class Pending {
        final UUID player;
        final BlockData original;
        final Consumer<String> callback;
        BukkitTask timeout;

        Pending(UUID player, BlockData original, Consumer<String> callback) {
            this.player = player;
            this.original = original;
            this.callback = callback;
        }
    }

    private final JavaPlugin plugin;
    private final Map<Location, Pending> pending = new ConcurrentHashMap<>();
    /** Spots whose prompt has been answered but whose block is not yet put back. */
    private final Map<Location, Pending> restoring = new ConcurrentHashMap<>();

    public SignInput(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Open a sign editor for {@code player}. {@code hints} fill lines 2-4 (the input is line 1). */
    public void request(Player player, List<String> hints, Consumer<String> callback) {
        // Drop any earlier prompt still open for this player, giving its block back.
        closeAll(player.getUniqueId());
        // Close the current menu, then open the sign a tick later (opening a sign editor while a
        // chest inventory is open is unreliable otherwise).
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> openNow(player, hints, callback));
    }

    private void openNow(Player player, List<String> hints, Consumer<String> callback) {
        if (!player.isOnline()) {
            return;
        }
        Block block = signSpot(player);
        if (block == null) {
            callback.accept(null);
            return;
        }
        Location loc = block.getLocation();
        BlockData original = block.getBlockData();
        block.setType(Material.OAK_SIGN, false);
        if (!(block.getState() instanceof Sign sign)) {
            block.setBlockData(original, false);
            callback.accept(null);
            return;
        }
        for (int i = 0; i < hints.size() && i < 3; i++) {
            sign.getSide(Side.FRONT).line(i + 1, Text.chat(hints.get(i)));
        }
        sign.update(true, false);
        Pending p = new Pending(player.getUniqueId(), original, callback);
        pending.put(loc, p);
        p.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(loc, p), TIMEOUT_TICKS);
        player.openSign(sign, Side.FRONT);
    }

    /**
     * Where to put the throwaway sign: the player's feet, else the block at their head. Putting the
     * original back only restores block data, not a block entity's contents, so a block with one (a
     * sign's text, a banner's patterns) is never borrowed. Nor is a block another player's prompt is
     * already using, since its "original" would then be that prompt's sign. Air is preferred, so
     * nothing visible changes. {@code null} if neither spot will do.
     */
    private Block signSpot(Player player) {
        Block feet = player.getLocation().getBlock();
        Block head = feet.getRelative(org.bukkit.block.BlockFace.UP);
        Block fallback = null;
        for (Block candidate : List.of(feet, head)) {
            if (pending.containsKey(candidate.getLocation())
                    || candidate.getY() < candidate.getWorld().getMinHeight()
                    || candidate.getY() >= candidate.getWorld().getMaxHeight()
                    || candidate.getState(false) instanceof org.bukkit.block.TileState) {
                continue;
            }
            if (candidate.getType().isAir()) {
                return candidate;
            }
            if (fallback == null) {
                fallback = candidate;
            }
        }
        return fallback;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSignChange(SignChangeEvent event) {
        Location loc = event.getBlock().getLocation();
        Pending p = pending.get(loc);
        if (p == null) {
            return;
        }
        event.setCancelled(true);
        // Only the prompted player's edit counts; anyone else's is discarded and the prompt stays open.
        if (!event.getPlayer().getUniqueId().equals(p.player) || !pending.remove(loc, p)) {
            return;
        }
        String input = PlainTextComponentSerializer.plainText().serialize(event.line(0)).trim();
        plugin.getLogger().fine("[sign-input] received from " + p.player + ": '" + input + "'");
        // Restoring is deferred a tick so it does not race the cancelled edit. Until then the spot is
        // tracked as restoring, so shutdown still puts it back.
        restoring.put(loc, p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (restoring.remove(loc, p)) {
                restore(loc, p);
            }
            Player player = Bukkit.getPlayer(p.player);
            if (player != null) {
                p.callback.accept(input);
            }
        });
    }

    private void expire(Location loc, Pending p) {
        if (!pending.remove(loc, p)) {
            return;
        }
        restore(loc, p);
        Player player = Bukkit.getPlayer(p.player);
        if (player != null && player.isOnline()) {
            p.callback.accept(null);
        }
    }

    /** Put the original block back, but only over our own sign: never clobber something else. */
    private void restore(Location loc, Pending p) {
        if (p.timeout != null) {
            p.timeout.cancel();
        }
        Block block = loc.getBlock();
        if (block.getType() == Material.OAK_SIGN) {
            block.setBlockData(p.original, false);
        }
    }

    private void closeAll(UUID player) {
        List<Map.Entry<Location, Pending>> mine = new ArrayList<>();
        for (Map.Entry<Location, Pending> entry : pending.entrySet()) {
            if (entry.getValue().player.equals(player)) {
                mine.add(entry);
            }
        }
        for (Map.Entry<Location, Pending> entry : mine) {
            if (pending.remove(entry.getKey(), entry.getValue())) {
                restore(entry.getKey(), entry.getValue());
            }
        }
    }

    /** Shutdown: put back every borrowed block, answered or not. */
    public void restoreAll() {
        for (Map<Location, Pending> map : List.of(pending, restoring)) {
            for (Map.Entry<Location, Pending> entry : new ArrayList<>(map.entrySet())) {
                if (map.remove(entry.getKey(), entry.getValue())) {
                    restore(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        closeAll(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ guard the borrowed blocks

    private boolean borrowed(Block block) {
        Location loc = block.getLocation();
        return pending.containsKey(loc) || restoring.containsKey(loc);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (borrowed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (borrowed(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::borrowed);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::borrowed);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(this::borrowed)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(this::borrowed)) {
            event.setCancelled(true);
        }
    }
}
