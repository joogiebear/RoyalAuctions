package com.mystipixel.royalauctions.hooks;

import com.willfp.eco.core.items.CustomItem;
import com.willfp.eco.core.items.Items;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;

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
