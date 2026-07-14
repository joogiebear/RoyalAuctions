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
 * Loads the configured categories and decides which one an item belongs to.
 *
 * <p>Resolution order (see {@link MatchRule}): an explicit {@code items:} pin wins over everything,
 * then the priority-ordered rules, then the catch-all. {@code strict-items} turns the inference off
 * entirely, so only pinned items get a category — for servers that want zero guessing.
 */
public final class CategoryManager {

    /** Where an item landed, and which rule put it there. */
    public record CategoryMatch(String categoryId, String reason) {
    }

    private final EcoHook eco;
    private final Logger logger;

    private final List<Category> ordered = new ArrayList<>();
    private final Map<String, Category> byId = new LinkedHashMap<>();
    private String fallbackId = "misc";
    private boolean strictItems = false;

    public CategoryManager(EcoHook eco, Logger logger) {
        this.eco = eco;
        this.logger = logger;
    }

    public void load(ConfigurationSection section, ConfigurationSection options) {
        ordered.clear();
        byId.clear();
        this.strictItems = options != null && options.getBoolean("strict-items", false);

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
        logger.info("Loaded " + ordered.size() + " auction categories"
                + (strictItems ? " (strict-items: only pinned items are categorised)." : "."));
    }

    /** The category id an item should be listed under. Never returns null once categories are loaded. */
    public String categorize(ItemStack item) {
        return explain(item).categoryId();
    }

    /**
     * Resolve an item's category <em>and</em> say why — this backs {@code /ah category}, so the
     * inference is never a black box to a server admin.
     */
    public CategoryMatch explain(ItemStack item) {
        String key = keyOf(item);

        // 1. An explicit pin always wins, whatever the priority order says.
        for (Category category : ordered) {
            if (category.rule().pins(key)) {
                return new CategoryMatch(category.id(), "explicit items: pin (" + key + ")");
            }
        }

        // 2. strict-items: no inference at all — unpinned items go to the catch-all.
        if (strictItems) {
            return new CategoryMatch(fallbackId, "strict-items is on and " + key + " is not pinned");
        }

        // 3. Inference: eco namespace, then material rules, then the catch-all.
        String namespace = eco.namespaceOf(item);
        for (Category category : ordered) {
            String reason = category.rule().reason(item, namespace);
            if (reason != null) {
                return new CategoryMatch(category.id(), reason);
            }
        }
        return new CategoryMatch(fallbackId, "no rule matched");
    }

    /**
     * The canonical key for an item: {@code namespace:id} for eco custom items, else
     * {@code minecraft:<material>}. This is what {@code items:} pins are matched against.
     */
    public String keyOf(ItemStack item) {
        if (item == null) {
            return null;
        }
        String namespace = eco.namespaceOf(item);
        String id = eco.customItemId(item);
        if (namespace != null && id != null) {
            return (namespace + ":" + id).toLowerCase(Locale.ROOT);
        }
        return "minecraft:" + item.getType().name().toLowerCase(Locale.ROOT);
    }

    /**
     * Every eco item that only matched the catch-all — i.e. the ones where our inference had nothing
     * to go on. Logged at startup so a server dev knows precisely which items are worth pinning,
     * instead of having to enumerate every material in the game.
     */
    public void auditCustomItems() {
        if (ordered.isEmpty()) {
            return;
        }
        Map<String, ItemStack> customItems = eco.allCustomItems();
        if (customItems.isEmpty()) {
            return;
        }
        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, ItemStack> e : customItems.entrySet()) {
            CategoryMatch match = explain(e.getValue());
            if (MatchRule.CATCH_ALL.equals(match.reason()) || match.reason().startsWith("no rule")
                    || match.reason().startsWith("strict-items")) {
                unresolved.add(e.getKey());
            }
        }
        if (unresolved.isEmpty()) {
            logger.info("Category audit: all " + customItems.size() + " eco items resolved to a category.");
            return;
        }
        java.util.Collections.sort(unresolved);
        logger.info("Category audit: " + unresolved.size() + " of " + customItems.size()
                + " eco item(s) fell through to '" + fallbackId + "'. Pin them under a category's"
                + " match.items: if they belong elsewhere:");
        for (String id : unresolved) {
            logger.info("  - " + id);
        }
    }

    /** The category id an item that matches nothing lands in (the highest-priority catch-all). */
    public String fallbackId() {
        return fallbackId;
    }

    public List<Category> categories() {
        return List.copyOf(ordered);
    }

    public Category byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** True if this id is one of the configured categories (used to detect stale/renamed categories). */
    public boolean isKnown(String categoryId) {
        return categoryId != null && byId.containsKey(categoryId.toLowerCase(Locale.ROOT));
    }

    public boolean isEmpty() {
        return ordered.isEmpty();
    }
}
