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
import net.milkbowl.vault.economy.Economy;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServiceRegisterEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

public final class RoyalAuctionsPlugin extends JavaPlugin {

    private PluginConfig config;
    private MessageManager messages;
    private VaultHook vault;
    private CategoryManager categories;
    private com.mystipixel.royalauctions.hooks.RarityRegistry rarities;
    private com.mystipixel.royalauctions.tier.TierManager tiers;
    private AuctionDatabase database;
    private AuctionService service;
    private MenuManager menus;
    private GuiManager guiManager;

    private BukkitTask expiryTask;
    private AuctionPlaceholderExpansion placeholderExpansion;
    private boolean fullyEnabled;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.config = new PluginConfig(this);
        new com.mystipixel.royalauctions.config.ConfigValidator(this, config).validate();
        this.messages = new MessageManager(this);
        this.vault = new VaultHook();

        EcoHook eco = new EcoHook();
        if (eco.isPresent()) {
            getLogger().info("eco detected - custom item categories enabled.");
        }
        // Menu templates resolve their item lookups through eco, so this must be set before they load.
        com.mystipixel.royalauctions.gui.menu.MenuTemplate.EcoHookHolder.set(eco);
        this.categories = new CategoryManager(eco, getLogger());
        this.categories.load(config.categoriesSection(), config.categoryOptionsSection());

        // Tiers are eco's rarities. EcoItems' rarities/ folder is the suite-wide registry — a rarity's
        // items: list can claim items from any eco plugin — so we read it as the single source of truth.
        this.rarities = new com.mystipixel.royalauctions.hooks.RarityRegistry(
                getDataFolder().getParentFile(), getLogger());
        this.tiers = new com.mystipixel.royalauctions.tier.TierManager(eco, rarities, getLogger());
        this.tiers.load(config.tiersSection());

        this.database = new AuctionDatabase(getDataFolder(), config.storageSection(), getLogger());
        try {
            database.init();
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Failed to initialise storage — disabling RoyalAuctions.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Vault is a hard dependency, but the economy *provider* (EssentialsX, CMI, an EcoBits currency
        // with vault:true, ...) is a separate plugin and can register after we enable. Disabling here
        // would mean the plugin silently kills itself on a perfectly good server purely because of
        // plugin load order — so wait for the provider instead.
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().warning("No Vault economy provider found yet. RoyalAuctions is waiting for one to"
                    + " register (install an economy plugin, e.g. EssentialsX). /ah is unavailable until then.");
            getServer().getPluginManager().registerEvents(new EconomyWaiter(), this);
            // Fallback, in case the provider registered before our listener was active.
            getServer().getScheduler().runTaskLater(this, this::tryLateEnable, 100L);
        }
    }

    /** Everything that needs a working economy. Idempotent — runs once, whenever the provider shows up. */
    private void finishEnable() {
        if (fullyEnabled) {
            return;
        }
        fullyEnabled = true;

        com.mystipixel.royalauctions.hooks.EconGuardHook econGuard =
                new com.mystipixel.royalauctions.hooks.EconGuardHook();
        if (econGuard.isPresent()) {
            getLogger().info("EconGuard detected - auction money movements will be reported to the central audit core.");
        }
        this.service = new AuctionService(this, database, vault, config, categories, tiers, messages, econGuard);
        this.menus = new MenuManager(this);
        SignInput signInput = new SignInput(this);
        getServer().getPluginManager().registerEvents(signInput, this);
        this.guiManager = new GuiManager(this, service, config, categories, tiers, messages, vault, menus, signInput);

        getServer().getPluginManager().registerEvents(new AuctionGuiListener(guiManager), this);

        AuctionCommand command = new AuctionCommand(this, guiManager, messages);
        if (getCommand("auctionhouse") != null) {
            getCommand("auctionhouse").setExecutor(command);
            getCommand("auctionhouse").setTabCompleter(command);
        }

        scheduleExpiryTask();
        service.refreshActiveCount();
        // The browse menu only repairs the categories it draws, so sweep everything once on startup.
        service.repairCategoriesOnStartup();

        // The eco plugins register their items in a delayed task (their "Loaded X" lines land after
        // the server reports Done), so the audit has to wait for that or it would see an empty registry.
        getServer().getScheduler().runTaskLater(this, () -> categories.auditCustomItems(), 100L);

        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            this.placeholderExpansion = new AuctionPlaceholderExpansion(
                    service, config, vault, getPluginMeta().getVersion());
            placeholderExpansion.register();
            getLogger().info("Registered PlaceholderAPI expansion.");
        }

        getLogger().info("RoyalAuctions enabled.");
    }

    private void tryLateEnable() {
        if (fullyEnabled) {
            return;
        }
        if (vault.setup()) {
            finishEnable();
        } else {
            getLogger().severe("Still no Vault economy provider after waiting. Install an economy plugin"
                    + " (e.g. EssentialsX) and restart. RoyalAuctions is loaded but inactive.");
        }
    }

    /** Completes startup if the economy provider registers after we enabled. */
    private final class EconomyWaiter implements Listener {
        @EventHandler
        public void onServiceRegister(ServiceRegisterEvent event) {
            if (!fullyEnabled && event.getProvider().getService() == Economy.class && vault.setup()) {
                getLogger().info("Vault economy provider detected. Finishing RoyalAuctions startup.");
                finishEnable();
            }
        }
    }

    @Override
    public void onDisable() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        // Return anything still escrowed in a create-flow BEFORE the database closes. An item
        // deposited into the sell menu lives only in memory until the listing is confirmed, so a
        // restart at that moment would destroy it outright.
        if (guiManager != null && database != null) {
            int returned = guiManager.drainEscrowToCollection();
            if (returned > 0) {
                getLogger().info("Returned " + returned + " escrowed auction item(s) to their owners' collection.");
            }
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
        categories.load(config.categoriesSection(), config.categoryOptionsSection());
        // Re-read eco's rarities too, so adding a rarity file is picked up by /ah reload.
        this.rarities = new com.mystipixel.royalauctions.hooks.RarityRegistry(
                getDataFolder().getParentFile(), getLogger());
        this.tiers.rebind(rarities);
        this.tiers.load(config.tiersSection());
        menus.reload();
        scheduleExpiryTask();
        categories.auditCustomItems();
    }

    public PluginConfig pluginConfig() {
        return config;
    }
}
