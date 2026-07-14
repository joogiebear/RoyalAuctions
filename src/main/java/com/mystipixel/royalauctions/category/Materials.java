package com.mystipixel.royalauctions.category;

import org.bukkit.Material;

/** Built-in material group tests used by the {@code tags} match rule. */
public final class Materials {

    private Materials() {
    }

    public static boolean isArmor(Material m) {
        String n = m.name();
        return n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS")
                || m == Material.ELYTRA || m == Material.SHIELD || m == Material.TURTLE_HELMET;
    }

    public static boolean isTool(Material m) {
        String n = m.name();
        return n.endsWith("_PICKAXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE")
                || m == Material.SHEARS || m == Material.FISHING_ROD
                || m == Material.FLINT_AND_STEEL || m == Material.BRUSH;
    }

    public static boolean isWeapon(Material m) {
        String n = m.name();
        return n.endsWith("_SWORD") || m == Material.BOW || m == Material.CROSSBOW
                || m == Material.TRIDENT || m == Material.MACE;
    }
}
