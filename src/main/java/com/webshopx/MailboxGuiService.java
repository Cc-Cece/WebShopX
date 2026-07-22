package com.webshopx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Read-only, paged view of the in-game delivery mailbox. */
class MailboxGuiService implements Listener {
  private static final int GUI_SIZE = 54;
  private static final int PAGE_SIZE = 45;
  private static final int PREVIOUS_SLOT = 45;
  private static final int COLLECT_SLOT = 49;
  private static final int NEXT_SLOT = 53;

  private final MailboxService mailboxService;
  private final MessageService messageService;

  MailboxGuiService(MailboxService mailboxService, MessageService messageService) {
    this.mailboxService = mailboxService;
    this.messageService = messageService;
  }

  void open(Player player) {
    open(player, 0);
  }

  private void open(Player player, int requestedPage) {
    int total = mailboxService.countPending(player.getUniqueId());
    int lastPage = Math.max(0, (total - 1) / PAGE_SIZE);
    int page = Math.max(0, Math.min(requestedPage, lastPage));
    MailboxHolder holder = new MailboxHolder(player.getUniqueId(), page);
    Inventory inventory = Bukkit.createInventory(
        holder,
        GUI_SIZE,
        messageService.format(player, "gui.mailbox.title", Map.of(
            "page", page + 1,
            "pages", lastPage + 1)));
    holder.inventory = inventory;

    List<MailboxService.MailboxItemView> entries =
        mailboxService.listPending(player.getUniqueId(), page * PAGE_SIZE, PAGE_SIZE);
    for (int slot = 0; slot < entries.size(); slot++) {
      inventory.setItem(slot, displayItem(player, entries.get(slot)));
    }
    ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    for (int slot = PAGE_SIZE; slot < GUI_SIZE; slot++) {
      inventory.setItem(slot, filler);
    }
    if (page > 0) {
      inventory.setItem(PREVIOUS_SLOT, named(
          Material.ARROW,
          messageService.get(player, "gui.mailbox.previous"),
          List.of()));
    }
    inventory.setItem(COLLECT_SLOT, named(
        total > 0 ? Material.CHEST_MINECART : Material.MINECART,
        messageService.get(player, "gui.mailbox.collect.title"),
        List.of(messageService.format(player, "gui.mailbox.collect.lore", Map.of("count", total)))));
    if (page < lastPage) {
      inventory.setItem(NEXT_SLOT, named(
          Material.ARROW,
          messageService.get(player, "gui.mailbox.next"),
          List.of()));
    }
    player.openInventory(inventory);
  }

  @EventHandler
  public void onClick(InventoryClickEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof MailboxHolder holder)) {
      return;
    }
    event.setCancelled(true);
    if (!(event.getWhoClicked() instanceof Player player)
        || !holder.playerId.equals(player.getUniqueId())) {
      return;
    }
    int slot = event.getRawSlot();
    if (slot == PREVIOUS_SLOT && holder.page > 0) {
      open(player, holder.page - 1);
    } else if (slot == NEXT_SLOT) {
      open(player, holder.page + 1);
    } else if (slot == COLLECT_SLOT) {
      MailboxService.MailboxClaimSummary summary = mailboxService.claimPending(player);
      sendSummary(player, summary);
      open(player, holder.page);
    }
  }

  @EventHandler
  public void onDrag(InventoryDragEvent event) {
    if (event.getView().getTopInventory().getHolder() instanceof MailboxHolder) {
      event.setCancelled(true);
    }
  }

  void sendSummary(Player player, MailboxService.MailboxClaimSummary summary) {
    if (summary.success() == 0 && summary.failed() == 0) {
      player.sendMessage(messageService.get(player, "command.mailbox.none"));
    } else if (summary.failed() > 0) {
      player.sendMessage(messageService.format(player, "command.mailbox.partial", Map.of(
          "success", summary.success(), "failed", summary.failed(), "remaining", summary.remaining())));
    } else {
      player.sendMessage(messageService.format(player, "command.mailbox.success", Map.of(
          "success", summary.success(), "remaining", summary.remaining())));
    }
  }

  private ItemStack displayItem(Player player, MailboxService.MailboxItemView entry) {
    ItemStack item = entry.item().clone();
    item.setAmount(Math.max(1, Math.min(item.getMaxStackSize(), entry.remainingQuantity())));
    ItemMeta meta = item.getItemMeta();
    List<String> lore = meta.hasLore() && meta.getLore() != null
        ? new ArrayList<>(meta.getLore())
        : new ArrayList<>();
    if (!lore.isEmpty()) {
      lore.add("");
    }
    lore.add(messageService.format(player, "gui.mailbox.item.quantity", Map.of("quantity", entry.remainingQuantity())));
    lore.add(messageService.format(player, "gui.mailbox.item.source", Map.of(
        "type", safe(entry.sourceType()), "ref", safe(entry.sourceRef()))));
    if (entry.reason() != null && !entry.reason().isBlank()) {
      lore.add(messageService.format(player, "gui.mailbox.item.reason", Map.of("reason", entry.reason())));
    }
    meta.setLore(lore);
    item.setItemMeta(meta);
    return item;
  }

  private ItemStack named(Material material, String name, List<String> lore) {
    ItemStack item = new ItemStack(material);
    ItemMeta meta = item.getItemMeta();
    meta.setDisplayName(name);
    meta.setLore(lore);
    item.setItemMeta(meta);
    return item;
  }

  private String safe(String value) {
    return value == null || value.isBlank() ? "-" : value;
  }

  private static final class MailboxHolder implements InventoryHolder {
    private final UUID playerId;
    private final int page;
    private Inventory inventory;

    private MailboxHolder(UUID playerId, int page) {
      this.playerId = playerId;
      this.page = page;
    }

    @Override
    public Inventory getInventory() {
      return inventory;
    }
  }
}
