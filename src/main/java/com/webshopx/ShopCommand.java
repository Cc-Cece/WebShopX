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
  private final AuthService authService;
  private final RedeemCodeService redeemCodeService;
  private final MarketService marketService;
  private final DeliveryService deliveryService;

  ShopCommand(
      WebShopPlugin plugin,
      AuthService authService,
      RedeemCodeService redeemCodeService,
      MarketService marketService,
      DeliveryService deliveryService) {
    this.plugin = plugin;
    this.authService = authService;
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
      case "password" -> handlePassword(sender, args);
      case "market" -> handleMarket(sender, args);
      case "claim" -> handleClaim(sender, args);
      case "reload" -> handleReload(sender);
      case "redeem" -> handleRedeem(sender, args);
      default -> {
        sender.sendMessage("§c未知子命令，请使用 /webshopx help 查看帮助。");
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
      options.add("password");
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

  private boolean handlePassword(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§c该命令仅可在游戏内执行：/webshopx password <新密码>");
      return true;
    }
    if (args.length < 2) {
      player.sendMessage("§e用法：/webshopx password <新密码>");
      return true;
    }

    try {
      AuthService.InGamePasswordResult result = authService.setPasswordFromGame(
          player.getUniqueId(),
          player.getName(),
          args[1]);
      if (result.created()) {
        player.sendMessage("§a网页账号已创建并绑定成功。");
      } else {
        player.sendMessage("§a网页账号密码已更新并重新绑定成功。");
      }
      player.sendMessage("§7登录用户名：§f" + result.username());
      player.sendMessage("§7现在可以前往网页使用该用户名和密码登录。");
    } catch (ServiceException exception) {
      player.sendMessage("§c设置密码失败：" + humanizePasswordError(exception));
    }
    return true;
  }

  private String humanizePasswordError(ServiceException exception) {
    return switch (exception.code()) {
      case "invalid_username" -> "当前游戏用户名不合法，请检查名称格式。";
      case "invalid_password" -> "密码长度需为 8-64 位。";
      case "username_exists" -> "当前游戏用户名已被其他网页账号占用，请联系管理员处理。";
      case "user_missing" -> "账号数据不存在，请联系管理员处理。";
      default -> exception.getMessage();
    };
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
    sender.sendMessage("§e/webshopx password <新密码> §7- 在游戏内创建或重置网页登录密码");
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
