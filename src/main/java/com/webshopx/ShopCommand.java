package com.webshopx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

class ShopCommand implements CommandExecutor, TabCompleter {
  private final WebShopPlugin plugin;
  private final BindingService bindingService;
  private final RedeemCodeService redeemCodeService;
  private final MarketService marketService;
  private final DeliveryService deliveryService;

  ShopCommand(
      WebShopPlugin plugin,
      BindingService bindingService,
      RedeemCodeService redeemCodeService,
      MarketService marketService,
      DeliveryService deliveryService) {
    this.plugin = plugin;
    this.bindingService = bindingService;
    this.redeemCodeService = redeemCodeService;
    this.marketService = marketService;
    this.deliveryService = deliveryService;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      sendHelp(sender);
      return true;
    }

    String subCommand = args[0].toLowerCase(Locale.ROOT);
    return switch (subCommand) {
      case "help" -> {
        sendHelp(sender);
        yield true;
      }
      case "bind" -> handleBind(sender, args);
      case "market" -> handleMarket(sender, args);
      case "claim" -> handleClaim(sender, args);
      case "reload" -> handleReload(sender);
      case "redeem" -> handleRedeem(sender, args);
      default -> {
        sender.sendMessage("§c未知子命令，请使用 /webshopx help。");
        yield true;
      }
    };
  }

  @Override
  public List<String> onTabComplete(
      CommandSender sender,
      Command command,
      String alias,
      String[] args) {
    if (args.length == 1) {
      List<String> options = new ArrayList<>();
      options.add("help");
      options.add("bind");
      options.add("market");
      options.add("claim");
      if (sender.hasPermission("webshop.admin")) {
        options.add("reload");
        options.add("redeem");
      }
      return filterByPrefix(options, args[0]);
    }

    String top = args[0].toLowerCase(Locale.ROOT);
    if (top.equals("market")) {
      if (args.length == 2) {
        return filterByPrefix(List.of("sell"), args[1]);
      }
      if (args.length == 4) {
        return filterByPrefix(List.of("1", "16", "64"), args[3]);
      }
      if (args.length == 5) {
        return filterByPrefix(List.of("SHOP_COIN", "GAME_COIN"), args[4]);
      }
      return List.of();
    }

    if (top.equals("claim")) {
      if (args.length == 2) {
        return filterByPrefix(List.of("all", "ODR-", "MKT-"), args[1]);
      }
      return List.of();
    }

    if (top.equals("redeem") && sender.hasPermission("webshop.admin")) {
      if (args.length == 2) {
        return filterByPrefix(List.of("create"), args[1]);
      }
      return List.of();
    }

    return List.of();
  }

  private boolean handleBind(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§c该命令仅可在游戏内执行：/webshopx bind <code>");
      return true;
    }
    if (args.length < 2) {
      player.sendMessage("§e用法：/webshopx bind <code>");
      return true;
    }

    BindingService.BindResult result = bindingService.bindPlayer(
        player.getUniqueId(),
        player.getName(),
        args[1]);

    switch (result.status()) {
      case SUCCESS -> player.sendMessage("§a绑定成功，网页账号：§f" + result.username());
      case INVALID_CODE -> player.sendMessage("§c绑定码无效。");
      case INVALID_USERNAME -> player.sendMessage("§c当前角色名不合法。");
      case EXPIRED -> player.sendMessage("§c绑定码已过期。");
      case ALREADY_USED -> player.sendMessage("§c绑定码已被使用。");
      case USER_ALREADY_BOUND -> player.sendMessage("§c该网页账号已绑定其他角色。");
      case PLAYER_ALREADY_BOUND -> player.sendMessage("§c该角色已绑定其他网页账号。");
      case USERNAME_ALREADY_USED -> player.sendMessage("§c该 Minecraft 名称已被占用。");
      default -> player.sendMessage("§c绑定失败，请稍后重试。");
    }
    return true;
  }

  private boolean handleClaim(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§c仅玩家可领取待发货物品。");
      return true;
    }

    String token = args.length >= 2 ? args[1] : null;
    try {
      DeliveryService.ClaimSummary summary = deliveryService.claimPending(player, token);
      if (summary.success() == 0 && summary.failed() == 0) {
        player.sendMessage("§e当前没有可领取内容。");
      } else if (summary.failed() > 0) {
        player.sendMessage(
            "§6领取完成：成功 §a" + summary.success() + " §6条，失败 §c" + summary.failed()
                + " §6条。可再次使用 /ws claim。");
      } else {
        player.sendMessage("§a领取完成：成功 " + summary.success() + " 条。");
      }
    } catch (ServiceException exception) {
      player.sendMessage("§c领取失败：" + exception.getMessage());
    }
    return true;
  }

  private boolean handleMarket(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§c仅玩家可使用市场命令。");
      return true;
    }
    if (args.length < 2 || !args[1].equalsIgnoreCase("sell")) {
      player.sendMessage("§e用法：/webshopx market sell <price> [amount] [currency]");
      return true;
    }
    if (args.length < 3) {
      player.sendMessage("§e用法：/webshopx market sell <price> [amount] [currency]");
      return true;
    }

    try {
      long price = Long.parseLong(args[2]);
      int amount = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
      CurrencyType currency = args.length >= 5
          ? CurrencyType.fromConfig(args[4])
          : CurrencyType.SHOP_COIN;
      MarketService.ListingCreateResult result = marketService.createListingFromPlayer(
          player,
          price,
          amount,
          currency);
      player.sendMessage(
          "§a上架成功：§f#" + result.listingId()
              + " §7| 物品 §f" + result.material()
              + " x" + result.quantity()
              + " §7| 单价 §f" + result.price() + " " + result.currency().name());
      return true;
    } catch (NumberFormatException exception) {
      player.sendMessage("§c价格与数量必须为数字。");
      return true;
    } catch (ServiceException exception) {
      player.sendMessage("§c上架失败：" + exception.getMessage());
      return true;
    }
  }

  private boolean handleReload(CommandSender sender) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§c你没有权限。");
      return true;
    }
    try {
      plugin.reloadRuntimeConfig();
      sender.sendMessage("§aWebShopX 配置已重载。");
    } catch (Exception exception) {
      sender.sendMessage("§c重载失败，请查看服务端日志。");
      plugin.getLogger().log(java.util.logging.Level.SEVERE, "Reload failed", exception);
    }
    return true;
  }

  private boolean handleRedeem(CommandSender sender, String[] args) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§c你没有权限。");
      return true;
    }
    if (args.length < 2 || !args[1].equalsIgnoreCase("create")) {
      sender.sendMessage(
          "§e用法：/webshopx redeem create <shopCoin> <gameCoin>"
              + " [maxUses] [perUserMaxUses] [minutes] [code]");
      return true;
    }
    if (args.length < 4) {
      sender.sendMessage(
          "§e用法：/webshopx redeem create <shopCoin> <gameCoin>"
              + " [maxUses] [perUserMaxUses] [minutes] [code]");
      return true;
    }

    try {
      long shopCoin = Long.parseLong(args[2]);
      long gameCoin = Long.parseLong(args[3]);
      int maxUses = args.length >= 5 ? Integer.parseInt(args[4]) : 1;
      int perUserMaxUses = args.length >= 6 ? Integer.parseInt(args[5]) : 1;
      Integer minutes = args.length >= 7 ? Integer.parseInt(args[6]) : null;
      String customCode = args.length >= 8 ? args[7] : null;
      String code = redeemCodeService.createCode(
          shopCoin,
          gameCoin,
          maxUses,
          perUserMaxUses,
          minutes,
          customCode);
      sender.sendMessage("§a兑换码已创建：§f" + code);
      return true;
    } catch (NumberFormatException exception) {
      sender.sendMessage("§c参数必须为有效数字。");
      return true;
    } catch (ServiceException exception) {
      sender.sendMessage("§c创建失败：" + exception.getMessage());
      return true;
    }
  }

  private void sendHelp(CommandSender sender) {
    sender.sendMessage("§e/webshopx help §7- 查看帮助");
    sender.sendMessage("§e/webshopx bind <code> §7- 绑定网页账号");
    sender.sendMessage("§e/webshopx market sell <price> [amount] [currency] §7- 上架手持物品");
    sender.sendMessage("§e/webshopx claim [all|ODR-...|MKT-...] §7- 领取待发货内容");
    if (sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§e/webshopx reload §7- 重载配置与内置网页");
      sender.sendMessage(
          "§e/webshopx redeem create <shop> <game> [max] [perUserMax] [minutes] [code] §7- 创建兑换码");
    }
  }

  private List<String> filterByPrefix(List<String> source, String userInput) {
    String token = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
    return source.stream()
        .filter(item -> item.toLowerCase(Locale.ROOT).startsWith(token))
        .collect(Collectors.toList());
  }
}
