package com.mystipixel.royalauctions.category;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The match predicate for one category. A rule matches an item if <em>any</em> of its
 * configured predicate types matches (eco namespace OR material OR suffix OR prefix OR tag),
 * or if it is the catch-all ({@code any: true}).
 */
public final class MatchRule {

    public enum Tag {
        EDIBLE, BLOCK, ARMOR, TOOL, WEAPON
    }

    private final Set<String> ecoNamespaces;
    private final Set<Material> materials;
    private final List<String> suffixes;
    private final List<String> prefixes;
    private final Set<Tag> tags;
    private final boolean any;

    private MatchRule(Set<String> ecoNamespaces, Set<Material> materials, List<String> suffixes,
                      List<String> prefixes, Set<Tag> tags, boolean any) {
        this.ecoNamespaces = ecoNamespaces;
        this.materials = materials;
        this.suffixes = suffixes;
        this.prefixes = prefixes;
        this.tags = tags;
        this.any = any;
    }

    /** Parse a {@code match:} section. Unknown materials/tags are logged by the caller-supplied warner. */
    public static MatchRule fromConfig(ConfigurationSection section, java.util.function.Consumer<String> warn) {
        if (section == null) {
            return new MatchRule(Set.of(), Set.of(), List.of(), List.of(), EnumSet.noneOf(Tag.class), false);
        }

        Set<String> namespaces = new HashSet<>();
        for (String s : section.getStringList("eco-namespaces")) {
            namespaces.add(s.toLowerCase(Locale.ROOT));
        }

        Set<Material> mats = new HashSet<>();
        for (String s : section.getStringList("materials")) {
            Material m = Material.matchMaterial(s);
            if (m == null) {
                warn.accept("Unknown material '" + s + "' in category match rule.");
            } else {
                mats.add(m);
            }
        }

        List<String> suffixes = section.getStringList("material-suffixes").stream()
                .map(s -> s.toUpperCase(Locale.ROOT)).toList();
        List<String> prefixes = section.getStringList("material-prefixes").stream()
                .map(s -> s.toUpperCase(Locale.ROOT)).toList();

        Set<Tag> tags = EnumSet.noneOf(Tag.class);
        for (String s : section.getStringList("tags")) {
            try {
                tags.add(Tag.valueOf(s.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                warn.accept("Unknown tag '" + s + "' in category match rule.");
            }
        }

        boolean any = section.getBoolean("any", false);
        return new MatchRule(namespaces, mats, suffixes, prefixes, tags, any);
    }

    public boolean matches(ItemStack item, String ecoNamespace) {
        if (any) {
            return true;
        }
        if (ecoNamespace != null && ecoNamespaces.contains(ecoNamespace)) {
            return true;
        }
        Material mat = item.getType();
        if (materials.contains(mat)) {
            return true;
        }
        String name = mat.name();
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        for (Tag tag : tags) {
            if (matchesTag(tag, mat)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTag(Tag tag, Material mat) {
        return switch (tag) {
            case EDIBLE -> mat.isEdible();
            case BLOCK -> mat.isBlock();
            case ARMOR -> Materials.isArmor(mat);
            case TOOL -> Materials.isTool(mat);
            case WEAPON -> Materials.isWeapon(mat);
        };
    }
}
