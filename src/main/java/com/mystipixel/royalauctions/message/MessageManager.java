package com.mystipixel.royalauctions.message;

import com.mystipixel.royalauctions.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Loads messages.yml and renders '&'-coloured, placeholder-filled chat components. */
public final class MessageManager {

    private final JavaPlugin plugin;
    private FileConfiguration messages;
    private String prefix = "";

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File file = new File(plugin.getDataFolder(), "messages.yml");
        if (!file.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.messages = YamlConfiguration.loadConfiguration(file);
        this.prefix = messages.getString("prefix", "");
    }

    private String raw(String path) {
        String value = messages.getString(path);
        return value == null ? path : value;
    }

    private String apply(String template, Map<String, String> placeholders) {
        String result = template.replace("{prefix}", prefix);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue());
        }
        return result;
    }

    public Component component(String path, Map<String, String> placeholders) {
        return Text.chat(apply(raw(path), placeholders));
    }

    public void send(CommandSender to, String path) {
        send(to, path, Map.of());
    }

    public void send(CommandSender to, String path, Map<String, String> placeholders) {
        to.sendMessage(component(path, placeholders));
    }

    /** Varargs convenience: send(sender, "sell.success", "item", name, "price", price). */
    public void send(CommandSender to, String path, String... kv) {
        send(to, path, map(kv));
    }

    public static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
