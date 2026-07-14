package com.mystipixel.royalauctions.hooks;

import com.willfp.eco.core.items.CustomItem;
import com.willfp.eco.core.items.Items;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Optional integration with the eco platform. All eco types are only ever touched
 * <em>after</em> the {@link #present} guard, so if eco isn't installed the JVM never
 * links the {@code com.willfp.*} references and this class stays harmless.
 */
public final class EcoHook {

    private final boolean present;

    public EcoHook() {
        this.present = Bukkit.getPluginManager().isPluginEnabled("eco");
    }

    public boolean isPresent() {
        return present;
    }

    /**
     * The eco item id of a custom item — e.g. {@code enchanted_diamond} for an {@code ecoitems:} item —
     * or {@code null} for vanilla / non-eco items. Pair with {@link #namespaceOf} to know which plugin
     * it came from.
     */
    public String customItemId(ItemStack item) {
        if (!present || item == null) {
            return null;
        }
        try {
            if (!Items.isCustomItem(item)) {
                return null;
            }
            CustomItem custom = Items.getCustomItem(item);
            if (custom == null || custom.getKey() == null) {
                return null;
            }
            return custom.getKey().getKey();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Every custom item registered with eco, keyed by its full id ({@code ecoitems:brazen_sword}).
     * Spans the whole suite — EcoItems, Talismans, Reforges, EcoArmor, EcoPets, EcoScrolls — because
     * they all register into eco's shared item registry. Used for the startup category audit.
     * Returns an empty map if eco is absent or its API shape changes; the audit is a nicety, never
     * something that should break startup.
     */
    public Map<String, ItemStack> allCustomItems() {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        if (!present) {
            return out;
        }
        try {
            for (CustomItem custom : Items.getCustomItems()) {
                if (custom == null || custom.getKey() == null) {
                    continue;
                }
                ItemStack stack = custom.getItem();
                if (stack == null || stack.getType().isAir()) {
                    continue;
                }
                out.put((custom.getKey().getNamespace() + ":" + custom.getKey().getKey())
                        .toLowerCase(Locale.ROOT), stack);
            }
        } catch (Throwable ignored) {
            // eco API hiccup — the audit is optional, so just report nothing.
        }
        return out;
    }

    /**
     * Resolve a menu item lookup id into a stack. Vanilla ids (bare or {@code minecraft:}-namespaced)
     * go straight to Bukkit — eco's lookup doesn't reliably handle the {@code minecraft:} namespace.
     * Custom namespaces ({@code ecoitems:...}) are resolved through eco. Falls back to STONE so a
     * typo in a menu config can never blow up rendering.
     */
    public ItemStack resolve(String id, int amount) {
        Material vanilla = vanillaMaterial(id);
        if (vanilla != null) {
            return new ItemStack(vanilla, amount);
        }
        if (present) {
            try {
                TestableItem test = Items.lookup(id);
                ItemStack item = test.getItem();
                if (item != null && !item.getType().isAir()) {
                    item = item.clone();
                    item.setAmount(amount);
                    return item;
                }
            } catch (Throwable ignored) {
                // unknown id — fall through
            }
        }
        return new ItemStack(Material.STONE, amount);
    }

    /** The vanilla material for a bare or {@code minecraft:}-namespaced id, else null. */
    private Material vanillaMaterial(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String raw = id;
        if (id.contains(":")) {
            String ns = id.substring(0, id.indexOf(':'));
            if (!ns.equalsIgnoreCase("minecraft")) {
                return null;
            }
            raw = id.substring(id.indexOf(':') + 1);
        }
        Material material = Material.matchMaterial(raw);
        return (material != null && !material.isAir()) ? material : null;
    }

    /**
     * The eco source namespace of an item — e.g. {@code talismans}, {@code reforges},
     * {@code ecoarmor}, {@code ecoitems} — or {@code null} for vanilla / non-eco items.
     */
    public String namespaceOf(ItemStack item) {
        if (!present || item == null) {
            return null;
        }
        try {
            if (!Items.isCustomItem(item)) {
                return null;
            }
            CustomItem custom = Items.getCustomItem(item);
            if (custom == null || custom.getKey() == null) {
                return null;
            }
            return custom.getKey().getNamespace();
        } catch (Throwable ignored) {
            // Any eco API hiccup must never block a listing.
            return null;
        }
    }
}
