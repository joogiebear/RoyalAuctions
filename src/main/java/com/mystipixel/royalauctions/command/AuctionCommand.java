package com.mystipixel.royalauctions.command;

import com.mystipixel.royalauctions.RoyalAuctionsPlugin;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.message.MessageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuctionCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("sell", "create", "search", "collect", "listings", "help");

    private final RoyalAuctionsPlugin plugin;
    private final GuiManager gui;
    private final MessageManager messages;

    public AuctionCommand(RoyalAuctionsPlugin plugin, GuiManager gui, MessageManager messages) {
        this.plugin = plugin;
        this.gui = gui;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("royalauctions.admin")) {
                messages.send(sender, "general.no-permission");
                return true;
            }
            plugin.reloadEverything();
            messages.send(sender, "general.reloaded");
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.send(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission("royalauctions.use")) {
            messages.send(player, "general.no-permission");
            return true;
        }

        if (args.length == 0) {
            gui.openBrowse(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "sell", "create" -> {
                if (!player.hasPermission("royalauctions.sell")) {
                    messages.send(player, "general.no-permission");
                } else {
                    gui.openCreate(player);
                }
            }
            case "search" -> {
                if (args.length < 2) {
                    gui.openBrowse(player);
                } else {
                    String query = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                    gui.openBrowse(player, null, query, plugin.pluginConfig().defaultSort(), 0);
                }
            }
            case "collect", "collection" -> gui.openCollection(player);
            case "listings", "mylistings" -> gui.openListings(player);
            case "help" -> sendHelp(player);
            default -> gui.openBrowse(player);
        }
        return true;
    }

    private void sendHelp(Player player) {
        player.sendMessage(com.mystipixel.royalauctions.util.Text.chat("&6&lAuction House"));
        player.sendMessage(com.mystipixel.royalauctions.util.Text.chat("&e/ah &7- browse listings"));
        player.sendMessage(com.mystipixel.royalauctions.util.Text.chat("&e/ah sell &7- open the Create Auction menu"));
        player.sendMessage(com.mystipixel.royalauctions.util.Text.chat("&e/ah search <query> &7- search by name"));
        player.sendMessage(com.mystipixel.royalauctions.util.Text.chat("&e/ah listings &7- manage your listings"));
        player.sendMessage(com.mystipixel.royalauctions.util.Text.chat("&e/ah collect &7- claim purchases & returns"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String sub : SUBS) {
                if (sub.startsWith(prefix)) {
                    out.add(sub);
                }
            }
            if (sender.hasPermission("royalauctions.admin") && "reload".startsWith(prefix)) {
                out.add("reload");
            }
            return out;
        }
        return List.of();
    }
}
