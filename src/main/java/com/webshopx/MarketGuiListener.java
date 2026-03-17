package com.webshopx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
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
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;

class MarketGuiListener implements Listener {
  private final MarketGuiService marketGuiService;
  private final MarketService marketService;

  MarketGuiListener(MarketGuiService marketGuiService, MarketService marketService) {
    this.marketGuiService = marketGuiService;
    this.marketService = marketService;
  }

  @EventHandler
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }
    if (!(event.getView().getTopInventory().getHolder() instanceof MarketGuiService.GuiHolder)) {
      return;
    }
    int rawSlot = event.getRawSlot();
    if (rawSlot >= event.getView().getTopInventory().getSize()) {
      return;
    }
    event.setCancelled(rawSlot >= 45 && rawSlot < event.getView().getTopInventory().getSize());
    try {
      marketGuiService.handleInventoryClick(player, event);
    } catch (ServiceException exception) {
      player.sendMessage("§c操作失败：" + exception.getMessage());
    } catch (Exception exception) {
      player.sendMessage("§c市场 GUI 出现异常，请稍后重试。");
    }
  }

  @EventHandler
  public void onInventoryDrag(InventoryDragEvent event) {
    if (!(event.getView().getTopInventory().getHolder() instanceof MarketGuiService.GuiHolder)) {
      return;
    }
    for (int slot : event.getRawSlots()) {
      if (slot >= 45) {
        event.setCancelled(true);
        return;
      }
    }
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
    Bukkit.getScheduler().runTask(
        marketService.plugin(),
        () -> marketGuiService.handlePlayerChat(player, message));
  }

  @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
  public void onBlockBreak(BlockBreakEvent event) {
    MarketService.ProtectedSupplyInfo info = marketService.findProtectedSupplyInfo(event.getBlock());
    if (info == null) {
      return;
    }
    event.setCancelled(true);
    event.getPlayer().sendMessage(
        "§c该供货箱正在被市场保护中。§7主人：§f" + info.ownerName()
            + " §7| 物品：§f" + info.itemMaterial()
            + " §7| 编号：§f#" + info.listingId()
            + " §7| 状态：§f" + info.status());
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
