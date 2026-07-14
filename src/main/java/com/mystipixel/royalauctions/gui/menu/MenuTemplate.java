package com.mystipixel.royalauctions.gui.menu;

import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A single menu loaded from a {@code gui/*.yml} file: title, size, filler, sounds, the configured
 * buttons (each with a slot, material, name, lore and {@link MenuAction}) and any named slot lists
 * used for dynamic content (e.g. {@code content-slots}, {@code category-slots}). Slots in the file
 * are 1-based (like RoyalBank); they are converted to 0-based indices here.
 */
public final class MenuTemplate {

    /** One configured button. Material/name/lore may contain {placeholders}. */
    public record ConfiguredItem(int slot, String material, String name, List<String> lore, MenuAction action) {
    }

    private final String title;
    private final int rows;
    private final ItemStack filler; // null when disabled
    private final Map<String, ConfiguredItem> items;
    private final Map<String, List<Integer>> slotLists;
    private final FileConfiguration source;

    private MenuTemplate(String title, int rows, ItemStack filler, Map<String, ConfiguredItem> items,
                         Map<String, List<Integer>> slotLists, FileConfiguration source) {
        this.title = title;
        this.rows = rows;
        this.filler = filler;
        this.items = items;
        this.slotLists = slotLists;
        this.source = source;
    }

    public static MenuTemplate load(File file, String defaultTitle, int defaultRows) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String title = cfg.getString("title", defaultTitle);
        int rows = Math.max(1, Math.min(6, cfg.getInt("rows", defaultRows)));

        ItemStack filler = null;
        if (cfg.getBoolean("filler.enabled", true)) {
            Material m = matchMaterial(cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE"));
            filler = simpleItem(m, cfg.getString("filler.name", " "), cfg.getStringList("filler.lore"), MenuAction.NONE);
        }

        Map<String, ConfiguredItem> items = new LinkedHashMap<>();
        ConfigurationSection itemsSec = cfg.getConfigurationSection("items");
        if (itemsSec != null) {
            for (String key : itemsSec.getKeys(false)) {
                ConfigurationSection is = itemsSec.getConfigurationSection(key);
                if (is == null) {
                    continue;
                }
                items.put(key, new ConfiguredItem(
                        is.getInt("slot", 1) - 1,
                        is.getString("material", "STONE"),
                        is.getString("name", "&f"),
                        is.getStringList("lore"),
                        MenuAction.parse(is.getString("action", "NONE"))));
            }
        }

        Map<String, List<Integer>> slotLists = new LinkedHashMap<>();
        for (String listName : List.of("content-slots", "category-slots", "duration-slots")) {
            List<Integer> raw = cfg.getIntegerList(listName);
            if (!raw.isEmpty()) {
                List<Integer> zeroBased = new ArrayList<>(raw.size());
                for (int s : raw) {
                    zeroBased.add(s - 1);
                }
                slotLists.put(listName, zeroBased);
            }
        }

        return new MenuTemplate(title, rows, filler, items, slotLists, cfg);
    }

    // ------------------------------------------------------------------ rendering

    public int size() {
        return rows * 9;
    }

    public String title(Map<String, String> placeholders) {
        return apply(title, placeholders);
    }

    /** Fill the filler and place every configured static button (with placeholders + action tags). */
    public void applyStatic(Inventory inv, Map<String, String> placeholders) {
        if (filler != null) {
            for (int i = 0; i < inv.getSize(); i++) {
                inv.setItem(i, filler.clone());
            }
        }
        for (ConfiguredItem ci : items.values()) {
            if (ci.slot() < 0 || ci.slot() >= inv.getSize()) {
                continue;
            }
            inv.setItem(ci.slot(), build(ci, placeholders));
        }
    }

    private ItemStack build(ConfiguredItem ci, Map<String, String> placeholders) {
        Material material = matchMaterial(apply(ci.material(), placeholders));
        List<String> lore = new ArrayList<>();
        for (String line : ci.lore()) {
            lore.add(apply(line, placeholders));
        }
        return simpleItem(material, apply(ci.name(), placeholders), lore, ci.action());
    }

    public int slotOf(String key) {
        ConfiguredItem ci = items.get(key);
        return ci == null ? -1 : ci.slot();
    }

    public MenuAction actionOf(String key) {
        ConfiguredItem ci = items.get(key);
        return ci == null ? MenuAction.NONE : ci.action();
    }

    public List<Integer> slots(String listName) {
        return slotLists.getOrDefault(listName, List.of());
    }

    /** A configurable list of lore lines at {@code path} (e.g. "listing-lore.auction"), placeholders filled. */
    public List<String> lore(String path, Map<String, String> placeholders) {
        List<String> raw = source.getStringList(path);
        List<String> out = new ArrayList<>(raw.size());
        for (String line : raw) {
            out.add(apply(line, placeholders));
        }
        return out;
    }

    public void playSound(Player player, String path) {
        if (!source.getBoolean("sounds." + path + ".enabled", false)) {
            return;
        }
        String name = source.getString("sounds." + path + ".name", "UI_BUTTON_CLICK");
        org.bukkit.Sound sound;
        try {
            sound = org.bukkit.Sound.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }
        float volume = (float) source.getDouble("sounds." + path + ".volume", 1.0);
        float pitch = (float) source.getDouble("sounds." + path + ".pitch", 1.0);
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    // ------------------------------------------------------------------ helpers

    public static ItemStack simpleItem(Material material, String name, List<String> lore, MenuAction action) {
        ItemStack item = new ItemStack(material == null || material.isAir() ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Text.color(name));
            if (lore != null && !lore.isEmpty()) {
                List<Component> lines = new ArrayList<>(lore.size());
                for (String line : lore) {
                    lines.add(Text.color(line));
                }
                meta.lore(lines);
            }
            meta.getPersistentDataContainer().set(MenuKeys.ACTION, PersistentDataType.STRING, action.name());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Stamp an action onto an existing item (used for dynamically-built buttons like the sell slot). */
    public static ItemStack tag(ItemStack item, MenuAction action) {
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(MenuKeys.ACTION, PersistentDataType.STRING, action.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Read the action tagged on a clicked item, or NONE. */
    public static MenuAction actionOf(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return MenuAction.NONE;
        }
        String raw = item.getItemMeta().getPersistentDataContainer().get(MenuKeys.ACTION, PersistentDataType.STRING);
        return MenuAction.parse(raw);
    }

    private static Material matchMaterial(String raw) {
        Material m = Material.matchMaterial(raw == null ? "STONE" : raw);
        return m == null ? Material.STONE : m;
    }

    private static String apply(String input, Map<String, String> placeholders) {
        if (input == null) {
            return "";
        }
        String result = input;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }
}
