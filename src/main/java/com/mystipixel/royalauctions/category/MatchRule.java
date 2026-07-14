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
 * The match predicate for one category. A rule matches an item if <em>any</em> of its configured
 * predicate types matches, or if it is the catch-all ({@code any: true}).
 *
 * <p>Resolution is layered, most-specific first:
 * <ol>
 *   <li>{@code items:} — an exact item pin ({@code ecoitems:brazen_sword}, {@code minecraft:trident}).
 *       Checked across <em>all</em> categories before anything else and wins regardless of priority.</li>
 *   <li>{@code eco-namespaces:} — a whole eco plugin ({@code talismans}) maps to one category.</li>
 *   <li>{@code materials} / {@code material-suffixes} / {@code material-prefixes} / {@code tags} —
 *       inferred from the item's base material.</li>
 *   <li>{@code any: true} — the catch-all.</li>
 * </ol>
 *
 * <p>Everything below the pin is an <em>inference</em> from the base material, which is only a guess
 * for custom items (a custom sword built on a STICK would otherwise land in Misc). {@code items:} is
 * the escape hatch, and {@link #reason} exists so an admin can always see which rule fired.
 */
public final class MatchRule {

    public enum Tag {
        EDIBLE, BLOCK, ARMOR, TOOL, WEAPON
    }

    private final Set<String> pins;          // canonical keys: "namespace:id"
    private final Set<String> ecoNamespaces;
    private final Set<Material> materials;
    private final List<String> suffixes;
    private final List<String> prefixes;
    private final Set<Tag> tags;
    private final boolean any;

    private MatchRule(Set<String> pins, Set<String> ecoNamespaces, Set<Material> materials,
                      List<String> suffixes, List<String> prefixes, Set<Tag> tags, boolean any) {
        this.pins = pins;
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
            return new MatchRule(Set.of(), Set.of(), Set.of(), List.of(), List.of(),
                    EnumSet.noneOf(Tag.class), false);
        }

        // items: accepts eco ids (ecoitems:brazen_sword) AND vanilla (minecraft:trident, or bare TRIDENT).
        Set<String> pins = new HashSet<>();
        for (String s : section.getStringList("items")) {
            String key = canonicalKey(s);
            if (key != null) {
                pins.add(key);
            } else {
                warn.accept("Unknown item '" + s + "' in category items: pin.");
            }
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
        return new MatchRule(pins, namespaces, mats, suffixes, prefixes, tags, any);
    }

    /**
     * Normalise a config entry into the canonical {@code namespace:id} key.
     * A bare or {@code minecraft:}-prefixed vanilla material becomes {@code minecraft:<material>};
     * anything with a non-minecraft namespace is taken as an eco id verbatim. Null if unresolvable.
     */
    static String canonicalKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains(":")) {
            String ns = s.substring(0, s.indexOf(':'));
            if (!ns.equals("minecraft")) {
                return s; // eco id — trust it (the plugin may not be installed yet)
            }
            s = s.substring(s.indexOf(':') + 1);
        }
        Material m = Material.matchMaterial(s);
        return m == null ? null : "minecraft:" + m.name().toLowerCase(Locale.ROOT);
    }

    /** True if this category explicitly pins the given canonical item key. */
    public boolean pins(String itemKey) {
        return itemKey != null && this.pins.contains(itemKey.toLowerCase(Locale.ROOT));
    }

    public boolean matches(ItemStack item, String ecoNamespace) {
        return reason(item, ecoNamespace) != null;
    }

    /**
     * Which rule matched, in human-readable form — or null if this category doesn't match. Used by
     * {@code /ah category} so an admin never has to guess why an item landed where it did.
     */
    public String reason(ItemStack item, String ecoNamespace) {
        if (ecoNamespace != null && ecoNamespaces.contains(ecoNamespace)) {
            return "eco-namespace '" + ecoNamespace + "'";
        }
        Material mat = item.getType();
        if (materials.contains(mat)) {
            return "material " + mat.name();
        }
        String name = mat.name();
        for (String suffix : suffixes) {
            if (name.endsWith(suffix)) {
                return "material-suffix " + suffix;
            }
        }
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return "material-prefix " + prefix;
            }
        }
        for (Tag tag : tags) {
            if (matchesTag(tag, mat)) {
                return "tag " + tag;
            }
        }
        if (any) {
            return CATCH_ALL;
        }
        return null;
    }

    /** The reason string used when only the {@code any: true} catch-all matched. */
    public static final String CATCH_ALL = "catch-all (any: true)";

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
