package com.mystipixel.royalauctions.tier;

import org.bukkit.Material;

/**
 * One item tier / rarity the browser can filter by. {@code id} must match the EcoItems rarity id
 * (the file name under {@code plugins/EcoItems/rarities/}), e.g. {@code epic}.
 */
public record Tier(String id, String displayName, Material icon, boolean enabled) {
}
