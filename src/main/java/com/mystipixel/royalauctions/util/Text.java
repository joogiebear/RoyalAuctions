package com.mystipixel.royalauctions.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * Small helper around Adventure so the rest of the plugin can keep using
 * friendly legacy '&' colour strings from config while still producing
 * modern {@link Component}s (Bukkit's String-based APIs are deprecated on Paper).
 */
public final class Text {

    private static final LegacyComponentSerializer AMP = LegacyComponentSerializer.legacyAmpersand();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private Text() {
    }

    /** Colourise a legacy string, disabling the default italic that Adventure applies to item names. */
    public static Component color(String input) {
        return AMP.deserialize(input == null ? "" : input).decoration(TextDecoration.ITALIC, false);
    }

    /** Colourise a legacy string, leaving italics as authored (for chat messages). */
    public static Component chat(String input) {
        return AMP.deserialize(input == null ? "" : input);
    }

    /** Plain (colour-stripped) text of a component — used to build the search index. */
    public static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }
}
