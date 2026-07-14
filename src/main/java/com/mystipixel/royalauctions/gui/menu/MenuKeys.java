package com.mystipixel.royalauctions.gui.menu;

import org.bukkit.NamespacedKey;

/** Persistent-data keys stamped onto GUI items so clicks can be resolved. */
public final class MenuKeys {

    /** The {@link MenuAction} name a configured button carries. */
    public static final NamespacedKey ACTION = new NamespacedKey("royalauctions", "gui_action");

    private MenuKeys() {
    }
}
