package com.webshopx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

class ShopCommand implements CommandExecutor, TabCompleter {
  private final WebShopPlugin plugin;
  private final BindingService bindingService;
  private final RedeemCodeService redeemCodeService;

  ShopCommand(
      WebShopPlugin plugin,
      BindingService bindingService,
      RedeemCodeService redeemCodeService) {
    this.plugin = plugin;
    this.bindingService = bindingService;
    this.redeemCodeService = redeemCodeService;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      sendHelp(sender);
      return true;
    }

    String subCommand = args[0].toLowerCase(Locale.ROOT);
    return switch (subCommand) {
      case "bind" -> handleBind(sender, args);
      case "reload" -> handleReload(sender);
      case "redeem-create" -> handleRedeemCreate(sender, args);
      default -> {
        sender.sendMessage("§c未知子命令。使用 /shop 查看帮助。");
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
      options.add("bind");
      if (sender.hasPermission("webshop.admin")) {
        options.add("reload");
        options.add("redeem-create");
      }
      return options;
    }
    return List.of();
  }

  private boolean handleBind(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage("§c只有玩家可以执行绑定命令。请在游戏内执行 /shop bind <code>。");
      return true;
    }
    if (args.length < 2) {
      player.sendMessage("§c用法: /shop bind <code>");
      return true;
    }

    BindingService.BindResult result = bindingService.bindPlayer(player.getUniqueId(), args[1]);
    switch (result.status()) {
      case SUCCESS -> player.sendMessage("§a绑定成功，账号: " + result.username());
      case INVALID_CODE -> player.sendMessage("§c绑定码不存在或格式错误。");
      case EXPIRED -> player.sendMessage("§c绑定码已过期，请在网页重新生成。");
      case ALREADY_USED -> player.sendMessage("§c该绑定码已被使用。");
      case USER_ALREADY_BOUND -> player.sendMessage("§c该网页账号已经绑定过游戏角色。");
      case PLAYER_ALREADY_BOUND -> player.sendMessage("§c你的角色已绑定其他网页账号。");
      default -> player.sendMessage("§c绑定失败，请稍后重试。");
    }
    return true;
  }

  private boolean handleReload(CommandSender sender) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§c你没有权限执行该命令。");
      return true;
    }
    try {
      plugin.reloadRuntimeConfig();
      sender.sendMessage("§aWebShop 配置已重载。");
    } catch (Exception exception) {
      sender.sendMessage("§c配置重载失败，详见控制台日志。");
      plugin.getLogger().log(java.util.logging.Level.SEVERE, "Reload failed", exception);
    }
    return true;
  }

  private boolean handleRedeemCreate(CommandSender sender, String[] args) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§c你没有权限执行该命令。");
      return true;
    }
    if (args.length < 3) {
      sender.sendMessage("§c用法: /shop redeem-create <shopCoin> <gameCoin> [maxUses] [minutes] [code]");
      return true;
    }

    try {
      long shopCoin = Long.parseLong(args[1]);
      long gameCoin = Long.parseLong(args[2]);
      int maxUses = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
      Integer minutes = args.length >= 5 ? Integer.parseInt(args[4]) : null;
      String customCode = args.length >= 6 ? args[5] : null;
      String code = redeemCodeService.createCode(shopCoin, gameCoin, maxUses, minutes, customCode);
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
    sender.sendMessage("§e/shop bind <code> §7- 绑定网页账号");
    if (sender.hasPermission("webshop.admin")) {
      sender.sendMessage("§e/shop reload §7- 重载配置并重启内置 Web");
      sender.sendMessage("§e/shop redeem-create <shop> <game> [max] [min] [code] §7- 创建兑换码");
    }
  }
}
