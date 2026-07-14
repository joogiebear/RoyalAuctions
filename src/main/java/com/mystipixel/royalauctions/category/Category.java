package com.mystipixel.royalauctions.category;

import org.bukkit.Material;

public final class Category {

    private final String id;
    private final String displayName;
    private final Material icon;
    private final int priority;
    private final MatchRule rule;

    public Category(String id, String displayName, Material icon, int priority, MatchRule rule) {
        this.id = id;
        this.displayName = displayName;
        this.icon = icon;
        this.priority = priority;
        this.rule = rule;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public Material icon() {
        return icon;
    }

    public int priority() {
        return priority;
    }

    public MatchRule rule() {
        return rule;
    }
}
