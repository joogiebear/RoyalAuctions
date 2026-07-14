package com.mystipixel.royalauctions.gui.menu;

import java.util.Locale;

/** Every click action a configured GUI button can carry (set via the {@code action:} key in gui/*.yml). */
public enum MenuAction {
    NONE,
    // Browse
    SEARCH,
    SORT,
    CREATE_AUCTION,
    PREV_PAGE,
    NEXT_PAGE,
    CLEAR_FILTERS,
    OPEN_MANAGE,
    OPEN_COLLECTION,
    OPEN_BROWSE,
    CLOSE,
    // Create flow
    SET_PRICE,
    SET_DURATION,
    TOGGLE_TYPE,
    REMOVE_ITEM,
    CONTINUE,
    CONFIRM,
    CANCEL,
    BACK,
    // Buying / bidding
    CONFIRM_PURCHASE,
    BID_MIN,
    BID_CUSTOM;

    public static MenuAction parse(String raw) {
        if (raw == null) {
            return NONE;
        }
        try {
            return valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
