package com.mystipixel.royalauctions.gui.menu;

import com.mystipixel.royalauctions.hooks.EcoHook;
import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses the EcoMenus inline item syntax used throughout {@code gui/*.yml}, e.g.
 * <pre>item: oak_sign hide_attributes name:"&amp;eSearch"</pre>
 * The first token is an item lookup (vanilla or {@code ecoitems:...}); the rest are flags
 * ({@code hide_enchants}, {@code hide_attributes}) and {@code key:"value"} modifiers ({@code name}).
 * Lore comes from the slot's separate {@code lore:} list. All of it may contain {@code %placeholders%}.
 */
public final class ItemSpec {

    private final String lookupId;
    private final String rawName;   // nullable
    private final boolean hideEnchants;
    private final boolean hideAttributes;

    private ItemSpec(String lookupId, String rawName, boolean hideEnchants, boolean hideAttributes) {
        this.lookupId = lookupId;
        this.rawName = rawName;
        this.hideEnchants = hideEnchants;
        this.hideAttributes = hideAttributes;
    }

    public static ItemSpec parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ItemSpec("stone", null, false, false);
        }
        List<String> tokens = tokenize(raw.trim());
        String lookup = tokens.isEmpty() ? "stone" : tokens.get(0);
        String name = null;
        boolean hideEnch = false;
        boolean hideAttr = false;
        for (int i = 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equalsIgnoreCase("hide_enchants")) {
                hideEnch = true;
            } else if (t.equalsIgnoreCase("hide_attributes")) {
                hideAttr = true;
            } else if (t.regionMatches(true, 0, "name:", 0, 5)) {
                name = stripQuotes(t.substring(5));
            }
        }
        return new ItemSpec(lookup, name, hideEnch, hideAttr);
    }

    /** Build the stack: resolve the lookup, fill placeholders in name/lore, and stamp the click action. */
    public ItemStack build(EcoHook eco, Map<String, String> placeholders, List<String> lore, MenuAction action) {
        return build(eco, placeholders, Map.of(), lore, action, action);
    }

    /**
     * Build the stack with distinct left/right click actions and support for <em>list placeholders</em>:
     * a lore line that is exactly {@code %key%} where {@code key} is in {@code listPlaceholders} is
     * replaced by one lore line per entry. That's what lets a filter button print its whole option
     * list, with the current selection marked, instead of only the current value.
     */
    public ItemStack build(EcoHook eco, Map<String, String> placeholders,
                           Map<String, List<String>> listPlaceholders, List<String> lore,
                           MenuAction leftAction, MenuAction rightAction) {
        ItemStack item = eco.resolve(apply(lookupId, placeholders), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (rawName != null) {
                meta.displayName(Text.color(apply(rawName, placeholders)));
            }
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    List<String> expanded = expand(line, listPlaceholders);
                    if (expanded != null) {
                        for (String sub : expanded) {
                            lines.add(Text.color(apply(sub, placeholders)));
                        }
                    } else {
                        lines.add(Text.color(apply(line, placeholders)));
                    }
                }
                meta.lore(lines);
            }
            if (hideEnchants) {
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(MenuKeys.ACTION, PersistentDataType.STRING, leftAction.name());
            meta.getPersistentDataContainer().set(MenuKeys.ACTION_RIGHT, PersistentDataType.STRING,
                    rightAction.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** If the whole lore line is a single {@code %list%} placeholder, return its lines; else null. */
    private static List<String> expand(String line, Map<String, List<String>> listPlaceholders) {
        if (line == null || listPlaceholders.isEmpty()) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.length() < 3 || !trimmed.startsWith("%") || !trimmed.endsWith("%")) {
            return null;
        }
        return listPlaceholders.get(trimmed.substring(1, trimmed.length() - 1));
    }

    // ---- helpers ----

    /** Split on spaces but keep quoted segments together, so name:"a b c" stays one token. */
    private static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch == '"') {
                inQuotes = !inQuotes;
                cur.append(ch);
            } else if (ch == ' ' && !inQuotes) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(ch);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("%" + e.getKey() + "%", e.getValue());
        }
        return result;
    }
}
