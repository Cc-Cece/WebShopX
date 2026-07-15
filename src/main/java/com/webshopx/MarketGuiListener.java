package com.webshopx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;

class MarketGuiListener implements Listener {
  private final MarketGuiService marketGuiService;
  private final MarketService marketService;
  private final MessageService messageService;
  private final SchedulerBridge schedulerBridge;

  MarketGuiListener(
      MarketGuiService marketGuiService,
      MarketService marketService,
      MessageService messageService,
      SchedulerBridge schedulerBridge) {
    this.marketGuiService = marketGuiService;
    this.marketService = marketService;
    this.messageService = messageService;
    this.schedulerBridge = schedulerBridge;
  }

  @EventHandler
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    MarketService.ProtectedSupplyInfo supplyInfo = protectedSupplyInfo(event.getView().getTopInventory());
    if (isDeniedSupplyAccess(player, supplyInfo)) {
      event.setCancelled(true);
      sendProtectedSupplyMessage(player, supplyInfo);
      return;
    }
    if (!(event.getView().getTopInventory().getHolder() instanceof MarketGuiService.GuiHolder holder)) {
      return;
    }
    int topSize = event.getView().getTopInventory().getSize();
    int rawSlot = event.getRawSlot();
    boolean isTopInventorySlot = rawSlot >= 0 && rawSlot < topSize;
    boolean isInputContentSlot =
        holder.kind() == MarketGuiService.GuiKind.INPUT && rawSlot >= 0 && rawSlot < 45;

    if (isTopInventorySlot && !isInputContentSlot) {
      event.setCancelled(true);
    }

    // Non-input menus are read-only; prevent shift-injecting items into top inventory.
    if (!isTopInventorySlot
        && holder.kind() != MarketGuiService.GuiKind.INPUT
        && event.isShiftClick()) {
      event.setCancelled(true);
    }
    try {
      marketGuiService.handleInventoryClick(player, event);
    } catch (ServiceException exception) {
      player.sendMessage(messageService.format(
          player,
          "chat.market.action_failed",
          java.util.Map.of("reason", marketGuiService.humanizeError(player, exception))));
    } catch (Exception exception) {
      player.sendMessage(messageService.get(player, "chat.market.listener_exception"));
    }
  }

  @EventHandler
  public void onInventoryDrag(InventoryDragEvent event) {
    if (event.getWhoClicked() instanceof Player player) {
      MarketService.ProtectedSupplyInfo supplyInfo = protectedSupplyInfo(event.getView().getTopInventory());
      if (isDeniedSupplyAccess(player, supplyInfo)) {
        event.setCancelled(true);
        sendProtectedSupplyMessage(player, supplyInfo);
        return;
      }
    }
    if (!(event.getView().getTopInventory().getHolder() instanceof MarketGuiService.GuiHolder holder)) {
      return;
    }
    int topSize = event.getView().getTopInventory().getSize();
    for (int slot : event.getRawSlots()) {
      boolean draggingIntoTop = slot >= 0 && slot < topSize;
      boolean editableInputSlot =
          holder.kind() == MarketGuiService.GuiKind.INPUT && slot >= 0 && slot < 45;
      if (draggingIntoTop && !editableInputSlot) {
        event.setCancelled(true);
        return;
      }
    }
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryOpen(InventoryOpenEvent event) {
    if (!(event.getPlayer() instanceof Player player)) {
      return;
    }
    MarketService.ProtectedSupplyInfo info = protectedSupplyInfo(event.getInventory());
    if (!isDeniedSupplyAccess(player, info)) {
      return;
    }
    event.setCancelled(true);
    sendProtectedSupplyMessage(player, info);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onInventoryMoveItem(InventoryMoveItemEvent event) {
    MarketService.ProtectedSupplyInfo info = protectedSupplyInfo(event.getSource());
    if (info != null && info.accessProtected()) {
      event.setCancelled(true);
    }
  }

  private MarketService.ProtectedSupplyInfo protectedSupplyInfo(org.bukkit.inventory.Inventory inventory) {
    if (inventory == null) {
      return null;
    }
    Block block = null;
    if (inventory.getHolder() instanceof Container container) {
      block = container.getBlock();
    } else if (inventory.getHolder() instanceof DoubleChest doubleChest) {
      if (doubleChest.getLeftSide() instanceof Container container) {
        block = container.getBlock();
      } else if (doubleChest.getRightSide() instanceof Container container) {
        block = container.getBlock();
      }
    } else if (inventory.getLocation() != null) {
      block = inventory.getLocation().getBlock();
    }
    return block == null ? null : marketService.findProtectedSupplyInfo(block);
  }

  private boolean isDeniedSupplyAccess(Player player, MarketService.ProtectedSupplyInfo info) {
    return info != null && info.accessProtected() && !player.getUniqueId().equals(info.ownerUuid());
  }

  private void sendProtectedSupplyMessage(Player player, MarketService.ProtectedSupplyInfo info) {
    if (info == null) {
      return;
    }
    player.sendMessage(
        messageService.format(
            player,
            "chat.market.protected_supply",
            java.util.Map.of(
                "ownerName", info.ownerName(),
                "itemMaterial", info.itemMaterial(),
                "listingId", info.listingId(),
                "status", marketGuiService.marketStatusLabel(player, info.status()))));
  }

  @EventHandler
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) {
      return;
    }
    marketGuiService.handleInventoryClose(player, event.getInventory());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onPlayerInteract(PlayerInteractEvent event) {
    Player player = event.getPlayer();
    if (!marketGuiService.isAwaitingSupplyBind(player.getUniqueId())) {
      return;
    }
    Block clicked = event.getClickedBlock();
    if (clicked == null) {
      return;
    }
    event.setCancelled(true);
    marketGuiService.handleSupplyBindClick(player, clicked);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onAsyncChat(AsyncPlayerChatEvent event) {
    Player player = event.getPlayer();
    if (!marketGuiService.hasChatSession(player.getUniqueId())) {
      return;
    }
    event.setCancelled(true);
    String message = event.getMessage();
    schedulerBridge.runPlayer(player.getUniqueId(), p -> marketGuiService.handlePlayerChat(p, message), null);
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    MarketService.ProtectedSupplyInfo info = marketService.findProtectedSupplyInfo(event.getBlock());
    if (info == null) {
      return;
    }
    event.setCancelled(true);
    event.getPlayer().sendMessage(
        messageService.format(
            event.getPlayer(),
            "chat.market.protected_supply",
            java.util.Map.of(
                "ownerName", info.ownerName(),
                "itemMaterial", info.itemMaterial(),
                "listingId", info.listingId(),
                "status", marketGuiService.marketStatusLabel(event.getPlayer(), info.status()))));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockExplode(BlockExplodeEvent event) {
    stripProtectedBlocks(event.blockList());
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onEntityExplode(EntityExplodeEvent event) {
    stripProtectedBlocks(event.blockList());
  }

  private void stripProtectedBlocks(List<Block> blocks) {
    Iterator<Block> iterator = new ArrayList<>(blocks).iterator();
    while (iterator.hasNext()) {
      Block block = iterator.next();
      if (marketService.isProtectedSupplyBlock(block)) {
        blocks.remove(block);
      }
    }
  }
}
