package com.mystipixel.royalauctions;

import com.mystipixel.royalauctions.category.CategoryManager;
import com.mystipixel.royalauctions.command.AuctionCommand;
import com.mystipixel.royalauctions.config.PluginConfig;
import com.mystipixel.royalauctions.data.AuctionDatabase;
import com.mystipixel.royalauctions.gui.AuctionGuiListener;
import com.mystipixel.royalauctions.gui.GuiManager;
import com.mystipixel.royalauctions.gui.SignInput;
import com.mystipixel.royalauctions.gui.menu.MenuManager;
import com.mystipixel.royalauctions.hooks.AuctionPlaceholderExpansion;
import com.mystipixel.royalauctions.hooks.EcoHook;
import com.mystipixel.royalauctions.hooks.VaultHook;
import com.mystipixel.royalauctions.message.MessageManager;
import com.mystipixel.royalauctions.service.AuctionService;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public final class RoyalAuctionsPlugin extends JavaPlugin {

    private PluginConfig config;
    private MessageManager messages;
    private VaultHook vault;
    private CategoryManager categories;
    private AuctionDatabase database;
    private AuctionService service;
    private MenuManager menus;
    private GuiManager guiManager;

    private BukkitTask expiryTask;
    private AuctionPlaceholderExpansion placeholderExpansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new PluginConfig(this);
        this.messages = new MessageManager(this);

        this.vault = new VaultHook();
        if (!vault.setup()) {
            getLogger().severe("No Vault economy provider found — disabling RoyalAuctions.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        EcoHook eco = new EcoHook();
        if (eco.isPresent()) {
            getLogger().info("eco detected - custom item categories enabled.");
        }
        this.categories = new CategoryManager(eco, getLogger());
        this.categories.load(config.categoriesSection());

        this.database = new AuctionDatabase(getDataFolder(), config.storageSection(), getLogger());
        try {
            database.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise storage — disabling RoyalAuctions.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.service = new AuctionService(this, database, vault, config, categories, messages);
        this.menus = new MenuManager(this);
        SignInput signInput = new SignInput(this);
        getServer().getPluginManager().registerEvents(signInput, this);
        this.guiManager = new GuiManager(this, service, config, categories, messages, vault, menus, signInput);

        getServer().getPluginManager().registerEvents(new AuctionGuiListener(guiManager), this);

        AuctionCommand command = new AuctionCommand(this, guiManager, messages);
        if (getCommand("auctionhouse") != null) {
            getCommand("auctionhouse").setExecutor(command);
            getCommand("auctionhouse").setTabCompleter(command);
        }

        scheduleExpiryTask();
        service.refreshActiveCount();

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderExpansion = new AuctionPlaceholderExpansion(
                    service, config, vault, getPluginMeta().getVersion());
            placeholderExpansion.register();
            getLogger().info("Registered PlaceholderAPI expansion.");
        }

        getLogger().info("RoyalAuctions enabled.");
    }

    @Override
    public void onDisable() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
        }
        if (database != null) {
            database.close();
        }
    }

    private void scheduleExpiryTask() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        long interval = config.expirySweepTicks();
        this.expiryTask = getServer().getScheduler()
                .runTaskTimerAsynchronously(this, () -> service.sweepExpired(), interval, interval);
    }

    /** Reload config, messages and categories. Storage-backend changes still need a restart. */
    public void reloadEverything() {
        config.reload();
        messages.reload();
        categories.load(config.categoriesSection());
        menus.reload();
        scheduleExpiryTask();
    }

    public PluginConfig pluginConfig() {
        return config;
    }
}
