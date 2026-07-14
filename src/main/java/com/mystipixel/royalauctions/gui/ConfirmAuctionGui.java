package com.mystipixel.royalauctions.gui;

import com.mystipixel.royalauctions.data.ListingType;
import com.mystipixel.royalauctions.gui.menu.MenuTemplate;
import com.mystipixel.royalauctions.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Confirm Auction — layout from gui/confirm-auction.yml. */
public final class ConfirmAuctionGui extends CreateFlowGui {

    private final MenuTemplate template;
    private boolean submitted;

    public ConfirmAuctionGui(GuiManager manager, Player player) {
        super(manager, player);
        this.template = manager.menus().confirmAuction();
        this.inventory = Bukkit.createInventory(this, template.size(), Text.color(template.title(Map.of())));
        render();
    }

    private void render() {
        CreateSession s = manager.session(player.getUniqueId());
        if (s == null || !s.hasItem()) {
            return;
        }
        boolean auction = s.type() == ListingType.AUCTION;
        double fee = manager.config().feeFor(s.price());

        template.applyStatic(inventory, Map.of(
                "type", auction ? "Auction" : "Buy It Now",
                "price", manager.vault().format(s.price()),
                "duration", s.durationHours() + "h",
                "fee", manager.vault().format(fee)));

        int itemSlot = template.slotOf("item");
        if (itemSlot >= 0) {
            List<String> lore = new ArrayList<>();
            lore.add("");
            lore.add("&7Type: " + (auction ? "&dAuction" : "&bBuy It Now"));
            lore.add(auction ? "&7Starting bid: &a" + manager.vault().format(s.price())
                    : "&7Price: &a" + manager.vault().format(s.price()));
            lore.add("&7Duration: &f" + s.durationHours() + "h");
            lore.add("&7Listing fee: &c" + manager.vault().format(fee));
            inventory.setItem(itemSlot, GuiUtil.appendLore(s.item(), lore));
        }
    }

    @Override
    public void onClick(InventoryClickEvent event) {
        if (!isTopClick(event)) {
            return;
        }
        switch (MenuTemplate.actionOf(event.getCurrentItem())) {
            case CONFIRM -> {
                if (!submitted) {
                    submitted = true;
                    manager.confirmCreate(player);
                }
            }
            case BACK, CANCEL -> manager.openCreate(player);
            default -> {
                // decorative
            }
        }
    }
}
