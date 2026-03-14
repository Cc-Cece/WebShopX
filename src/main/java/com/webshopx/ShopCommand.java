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

  ShopCommand(
      WebShopPlugin plugin,
      BindingService bindingService,
      RedeemCodeService redeemCodeService,
      MarketService marketService) {
    this.plugin = plugin;
    this.bindingService = bindingService;
    this.redeemCodeService = redeemCodeService;
    this.marketService = marketService;
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
      case "reload" -> handleReload(sender);
      case "redeem" -> handleRedeem(sender, args);
      default -> {
        sender.sendMessage("§c未知子命令。使用 /webshopx help 查看帮助。");
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
      sender.sendMessage("§c只有玩家可以执行绑定命令，请在游戏内使用 /webshopx bind <code>。");
      return true;
    }
    if (args.length < 2) {
      player.sendMessage("§c用法: /webshopx bind <code>");
      return true;
    }

    BindingService.BindResult result = bindingService.bindPlayer(
        player.getUniqueId(),
        player.getName(),
        args[1]);
    switch (result.status()) {
      case SUCCESS -> player.sendMessage("§a绑定成功，账号: " + result.username());
      case INVALID_CODE -> player.sendMessage("§c绑定码不存在或格式错误。");
      case INVALID_USERNAME -> player.sendMessage("§c当前玩家名不合法，请联系管理员。");
      case EXPIRED -> player.sendMessage("§c绑定码已过期，请在网页重新生成。");
      case ALREADY_USED -> player.sendMessage("§c该绑定码已被使用。");
      case USER_ALREADY_BOUND -> player.sendMessage("§c该网页账号已经绑定过游戏角色。");
      case PLAYER_ALREADY_BOUND -> player.sendMessage("§c你的角色已绑定其他网页账号。");
      case USERNAME_ALREADY_USED -> player.sendMessage("§c该 MC 名称已被其他账号使用。");
      default -> player.sendMessage("§c绑定失败，请稍后重试。");
    }
    return true;
  }

  private boolean handleMarket(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§c只有玩家可以使用市场命令。");
      return true;
    }
    if (args.length < 2 || !args[1].equalsIgnoreCase("sell")) {
      player.sendMessage("§c用法: /webshopx market sell <price> [amount] [currency]");
      return true;
    }
    if (args.length < 3) {
      player.sendMessage("§c用法: /webshopx market sell <price> [amount] [currency]");
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
          "§a上架成功，ID: §e" + result.listingId()
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

  private boolean handleReload(CommandSender sender) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§c你没有权限执行该命令。");
      return true;
    }
    try {
      plugin.reloadRuntimeConfig();
      sender.sendMessage("§aWebShopX 配置已重载。");
    } catch (Exception exception) {
      sender.sendMessage("§c配置重载失败，详见控制台日志。");
      plugin.getLogger().log(java.util.logging.Level.SEVERE, "Reload failed", exception);
    }
    return true;
  }

  private boolean handleRedeem(CommandSender sender, String[] args) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§c你没有权限执行该命令。");
      return true;
    }
    if (args.length < 2 || !args[1].equalsIgnoreCase("create")) {
      sender.sendMessage(
          "§c用法: /webshopx redeem create <shopCoin> <gameCoin>"
              + " [maxUses] [perUserMaxUses] [minutes] [code]");
      return true;
    }
    if (args.length < 4) {
      sender.sendMessage(
          "§c用法: /webshopx redeem create <shopCoin> <gameCoin>"
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
      sender.sendMessage("§a兑换码已创建: §e" + code);
      return true;
    } catch (NumberFormatException exception) {
      sender.sendMessage("§c参数必须是有效数字。");
      return true;
    } catch (ServiceException exception) {
      sender.sendMessage("§c创建失败: " + exception.getMessage());
      return true;
    }
  }

  private void sendHelp(CommandSender sender) {
    sender.sendMessage("§e/webshopx help §7- 查看帮助");
    sender.sendMessage("§e/webshopx bind <code> §7- 绑定网页账号");
    sender.sendMessage("§e/webshopx market sell <price> [amount] [currency] §7- 上架手持物品");
    if (sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§e/webshopx reload §7- 重载配置并重启内置 Web");
      sender.sendMessage(
          "§e/webshopx redeem create <shop> <game> [max] [perUserMax] [minutes] [code]"
              + " §7- 创建兑换码");
    }
  }

  private List<String> filterByPrefix(List<String> source, String userInput) {
    String token = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
    return source.stream()
        .filter(item -> item.toLowerCase(Locale.ROOT).startsWith(token))
        .collect(Collectors.toList());
  }
}
