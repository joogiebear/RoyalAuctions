package com.mystipixel.royalauctions.category;

import com.mystipixel.royalauctions.hooks.EcoHook;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Loads the configured categories and decides which one an item belongs to. Items are
 * tested in ascending {@code priority} and land in the first category that matches, so a
 * catch-all ({@code any: true}) with a high priority number acts as the fallback.
 */
public final class CategoryManager {

    private final EcoHook eco;
    private final Logger logger;

    private final List<Category> ordered = new ArrayList<>();
    private final Map<String, Category> byId = new LinkedHashMap<>();
    private String fallbackId = "misc";

    public CategoryManager(EcoHook eco, Logger logger) {
        this.eco = eco;
        this.logger = logger;
    }

    public void load(ConfigurationSection section) {
        ordered.clear();
        byId.clear();

        if (section == null) {
            logger.warning("No 'categories' section found in config.yml — items will be uncategorised.");
            return;
        }

        for (String id : section.getKeys(false)) {
            ConfigurationSection cs = section.getConfigurationSection(id);
            if (cs == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            String displayName = cs.getString("display-name", id);
            Material icon = Material.matchMaterial(cs.getString("icon", "CHEST"));
            if (icon == null) {
                logger.warning("Category '" + id + "' has an unknown icon; defaulting to CHEST.");
                icon = Material.CHEST;
            }
            int priority = cs.getInt("priority", 100);
            MatchRule rule = MatchRule.fromConfig(cs.getConfigurationSection("match"),
                    msg -> logger.warning("[category " + id + "] " + msg));

            Category category = new Category(key, displayName, icon, priority, rule);
            ordered.add(category);
            byId.put(key, category);
        }

        ordered.sort(Comparator.comparingInt(Category::priority));
        if (!ordered.isEmpty()) {
            fallbackId = ordered.get(ordered.size() - 1).id();
        }
        logger.info("Loaded " + ordered.size() + " auction categories.");
    }

    /** The category id an item should be listed under. Never returns null once categories are loaded. */
    public String categorize(ItemStack item) {
        String namespace = eco.namespaceOf(item);
        for (Category category : ordered) {
            if (category.rule().matches(item, namespace)) {
                return category.id();
            }
        }
        return fallbackId;
    }

    public List<Category> categories() {
        return List.copyOf(ordered);
    }

    public Category byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }
}
