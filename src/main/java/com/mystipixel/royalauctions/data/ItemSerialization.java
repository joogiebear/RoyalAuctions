package com.mystipixel.royalauctions.data;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/**
 * Serialises an {@link ItemStack} with full component/NBT fidelity using Paper's
 * native {@code serializeAsBytes()} / {@code deserializeBytes()}. This is what makes
 * every eco-suite custom item (EcoItems, Talismans, Reforges, EcoArmor, …) survive a
 * round-trip through the auction house: their data lives in the item's persistent data
 * container / components, all of which these methods preserve.
 */
public final class ItemSerialization {

    private ItemSerialization() {
    }

    public static byte[] serialize(ItemStack item) {
        return item.serializeAsBytes();
    }

    public static ItemStack deserialize(byte[] bytes) {
        return ItemStack.deserializeBytes(bytes);
    }

    public static String toBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] fromBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
