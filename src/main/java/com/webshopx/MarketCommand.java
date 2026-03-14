package com.webshopx;

import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

class MarketCommand implements CommandExecutor {
  private final MarketService marketService;

  MarketCommand(MarketService marketService) {
    this.marketService = marketService;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§c只有玩家可以使用市场命令。");
      return true;
    }

    if (args.length == 0) {
      sendHelp(player);
      return true;
    }

    String sub = args[0].toLowerCase(Locale.ROOT);
    if (!sub.equals("sell")) {
      sendHelp(player);
      return true;
    }

    if (args.length < 2) {
      player.sendMessage("§c用法: /market sell <price> [amount] [currency]");
      return true;
    }

    try {
      long price = Long.parseLong(args[1]);
      int amount = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
      CurrencyType currency = args.length >= 4
          ? CurrencyType.fromConfig(args[3])
          : CurrencyType.SHOP_COIN;
      MarketService.ListingCreateResult result = marketService.createListingFromPlayer(
          player,
          price,
          amount,
          currency);
      player.sendMessage("§a上架成功，ID: §e" + result.listingId()
          + " §7| 物品: §f" + result.material()
          + " x" + result.quantity()
          + " §7| 价格: §f" + result.price() + " " + result.currency().name());
      return true;
    } catch (NumberFormatException exception) {
      player.sendMessage("§c价格和数量必须是有效数字。");
      return true;
    } catch (ServiceException exception) {
      player.sendMessage("§c上架失败: " + exception.getMessage());
      return true;
    }
  }

  private void sendHelp(Player player) {
    player.sendMessage("§e/market sell <price> [amount] [currency] §7- 游戏内上架当前主手物品");
    player.sendMessage("§7currency 可选: SHOP_COIN / GAME_COIN，默认 SHOP_COIN。");
  }
}
