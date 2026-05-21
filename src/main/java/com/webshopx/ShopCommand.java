package com.webshopx;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

class ShopCommand implements CommandExecutor, TabCompleter {
  private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm");

  private final WebShopPlugin plugin;
  private final AuthService authService;
  private final RedeemCodeService redeemCodeService;
  private final RechargeService rechargeService;
  private final AdminService adminService;
  private final MarketService marketService;
  private final MarketGuiService marketGuiService;
  private final DeliveryService deliveryService;
  private final MailboxService mailboxService;
  private final MessageService messageService;
  private final SchedulerBridge schedulerBridge;
  private final Supplier<PluginSettings> settingsSupplier;

  ShopCommand(
      WebShopPlugin plugin,
      AuthService authService,
      RedeemCodeService redeemCodeService,
      RechargeService rechargeService,
      AdminService adminService,
      MarketService marketService,
      MarketGuiService marketGuiService,
      DeliveryService deliveryService,
      MailboxService mailboxService,
      MessageService messageService,
      SchedulerBridge schedulerBridge,
      Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.authService = authService;
    this.redeemCodeService = redeemCodeService;
    this.rechargeService = rechargeService;
    this.adminService = adminService;
    this.marketService = marketService;
    this.marketGuiService = marketGuiService;
    this.deliveryService = deliveryService;
    this.mailboxService = mailboxService;
    this.messageService = messageService;
    this.schedulerBridge = schedulerBridge;
    this.settingsSupplier = settingsSupplier;
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
      case "mailbox" -> handleMailbox(sender, args);
      case "reload" -> handleReload(sender);
      case "redeem" -> handleRedeem(sender, args);
      case "recharge" -> handleRecharge(sender, args);
      case "gamecoin" -> handleWalletDelta(sender, args, CurrencyType.GAME_COIN);
      case "shopcoin" -> handleWalletDelta(sender, args, CurrencyType.SHOP_COIN);
      default -> {
        sender.sendMessage(msg(sender, "command.unknown_subcommand"));
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
      options.add("mailbox");
      options.add("recharge");
      if (sender.hasPermission("webshop.admin")) {
        options.add("reload");
        options.add("redeem");
        options.add("gamecoin");
        options.add("shopcoin");
      }
      return filterByPrefix(options, args[0]);
    }

    String top = args[0].toLowerCase(Locale.ROOT);
    if (top.equals("market")) {
      if (args.length == 2) {
        List<String> options = new ArrayList<>(List.of("gui", "sell", "logs"));
        if (sender.hasPermission("webshop.admin")) {
          options.add("recalc-tags");
        }
        return filterByPrefix(options, args[1]);
      }
      if (args.length == 3 && args[1].equalsIgnoreCase("logs")) {
        return filterByPrefix(List.of("5", "10", "20"), args[2]);
      }
      if (args.length == 3 && args[1].equalsIgnoreCase("recalc-tags")) {
        return filterByPrefix(List.of("active", "all"), args[2]);
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
        return filterByPrefix(List.of("all", "ODR-", "MKT-", "CLM-", "MCL-"), args[1]);
      }
      return List.of();
    }

    if (top.equals("mailbox")) {
      if (args.length == 2) {
        return filterByPrefix(List.of("claim"), args[1]);
      }
      return List.of();
    }

    if (top.equals("redeem") && sender.hasPermission("webshop.admin")) {
      if (args.length == 2) {
        return filterByPrefix(List.of("create"), args[1]);
      }
      return List.of();
    }

    if (top.equals("recharge")) {
      if (args.length == 2) {
        List<String> options = new ArrayList<>(List.of("10", "30", "50", "100"));
        if (sender.hasPermission("webshop.admin")) {
          options.add("fix");
        }
        return filterByPrefix(options, args[1]);
      }
      return List.of();
    }

    if ((top.equals("gamecoin") || top.equals("shopcoin")) && sender.hasPermission("webshop.admin")) {
      if (args.length == 2) {
        return filterByPrefix(plugin.getServer().getOnlinePlayers().stream()
            .map(Player::getName)
            .toList(), args[1]);
      }
      if (args.length == 3) {
        return filterByPrefix(List.of("100", "-100", "1000", "-1000"), args[2]);
      }
      return List.of();
    }

    return List.of();
  }

  private boolean handlePassword(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg(sender, "command.password.player_only"));
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(msg(player, "command.password.usage"));
      return true;
    }

    try {
      AuthService.InGamePasswordResult result = authService.setPasswordFromGame(
          player.getUniqueId(),
          player.getName(),
          args[1]);
      player.sendMessage(msg(player, result.created() ? "command.password.created" : "command.password.updated"));
      player.sendMessage(msg(player, "command.password.username", Map.of("username", result.username())));
      player.sendMessage(msg(player, "command.password.web_login"));
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "command.password.failed",
          Map.of("reason", humanizePasswordError(player, exception))));
    }
    return true;
  }

  private String humanizePasswordError(CommandSender sender, ServiceException exception) {
    return switch (exception.code()) {
      case "invalid_username" -> messageService.get(sender, "command.password.error.invalid_username");
      case "invalid_password" -> messageService.get(sender, "command.password.error.invalid_password");
      case "username_exists" -> messageService.get(sender, "command.password.error.username_exists");
      case "user_missing" -> messageService.get(sender, "command.password.error.user_missing");
      default -> exception.getMessage();
    };
  }

  private boolean handleClaim(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg(sender, "command.claim.player_only"));
      return true;
    }

    String token = args.length >= 2 ? args[1] : null;
    try {
      DeliveryService.ClaimSummary summary = deliveryService.claimPending(player, token);
      if (summary.success() == 0 && summary.failed() == 0) {
        player.sendMessage(msg(player, "command.claim.none"));
      } else if (summary.failed() > 0) {
        player.sendMessage(msg(player, "command.claim.partial",
            Map.of("success", summary.success(), "failed", summary.failed())));
      } else {
        player.sendMessage(msg(player, "command.claim.success", Map.of("success", summary.success())));
      }
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "command.claim.failed",
          Map.of("reason", humanizeDeliveryError(player, exception))));
    }
    return true;
  }

  private boolean handleMarket(CommandSender sender, String[] args) {
    if (args.length >= 2 && args[1].equalsIgnoreCase("recalc-tags")) {
      return handleMarketRecalcTags(sender, args);
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg(sender, "command.market.player_only"));
      return true;
    }
    if (args.length == 1 || (args.length >= 2 && args[1].equalsIgnoreCase("gui"))) {
      marketGuiService.openMainMenu(player);
      return true;
    }
    if (args.length >= 2 && args[1].equalsIgnoreCase("logs")) {
      return handleMarketLogs(player, args);
    }
    if (args.length < 2 || !args[1].equalsIgnoreCase("sell")) {
      player.sendMessage(msg(player, "command.market.usage_gui"));
      player.sendMessage(msg(player, "command.market.usage_legacy"));
      return true;
    }
    if (args.length < 3) {
      player.sendMessage(msg(player, "command.market.usage_sell"));
      return true;
    }

    try {
      long price = Long.parseLong(args[2]);
      int amount = args.length >= 4 ? Integer.parseInt(args[3]) : 1;
      CurrencyType currency = args.length >= 5
          ? CurrencyType.fromConfig(args[4])
          : CurrencyType.GAME_COIN;
      MarketService.ListingCreateResult result = marketService.createListingFromPlayer(
          player,
          price,
          amount,
          currency);
      player.sendMessage(msg(player, "command.market.sell_success", Map.of(
          "listingId", result.listingId(),
          "material", result.material(),
          "quantity", result.quantity(),
          "price", result.price(),
          "currency", result.currency().name())));
      return true;
    } catch (NumberFormatException exception) {
      player.sendMessage(msg(player, "command.market.sell_number"));
      return true;
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "command.market.sell_failed",
          Map.of("reason", humanizeMarketError(player, exception))));
      return true;
    }
  }

  private boolean handleMarketRecalcTags(CommandSender sender, String[] args) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage(msg(sender, "command.common.no_permission"));
      return true;
    }
    String scope = args.length >= 3 ? args[2] : "active";
    try {
      MarketService.TagRecalcResult result = marketService.recalcTags(scope);
      sender.sendMessage(msg(sender, "command.tag_recalc_completed", Map.of(
          "scanned", result.scanned(),
          "changed", result.changed(),
          "elapsed", result.elapsedMs())));
    } catch (ServiceException exception) {
      sender.sendMessage(msg(sender, "command.tag_recalc_failed", Map.of("reason", humanizeMarketError(sender, exception))));
    }
    return true;
  }

  private boolean handleMailbox(CommandSender sender, String[] args) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg(sender, "command.mailbox.player_only"));
      return true;
    }
    if (args.length == 1) {
      int pending = mailboxService.countPending(player.getUniqueId());
      if (pending <= 0) {
        player.sendMessage(msg(player, "command.mailbox.none"));
      } else {
        player.sendMessage(msg(player, "command.mailbox.pending", Map.of("count", pending)));
        player.sendMessage(msg(player, "command.mailbox.usage"));
      }
      return true;
    }
    if (!args[1].equalsIgnoreCase("claim")) {
      player.sendMessage(msg(player, "command.mailbox.usage"));
      return true;
    }
    MailboxService.MailboxClaimSummary summary = mailboxService.claimPending(player);
    if (summary.success() == 0 && summary.failed() == 0) {
      player.sendMessage(msg(player, "command.mailbox.none"));
      return true;
    }
    if (summary.failed() > 0) {
      player.sendMessage(msg(player, "command.mailbox.partial", Map.of(
          "success", summary.success(),
          "failed", summary.failed(),
          "remaining", summary.remaining())));
      return true;
    }
    player.sendMessage(msg(player, "command.mailbox.success", Map.of(
        "success", summary.success(),
        "remaining", summary.remaining())));
    return true;
  }

  private boolean handleMarketLogs(Player player, String[] args) {
    int limit = 5;
    if (args.length >= 3) {
      try {
        limit = Integer.parseInt(args[2]);
      } catch (NumberFormatException exception) {
        player.sendMessage(msg(player, "command.market.logs_number"));
        return true;
      }
    }
    try {
      List<MarketService.SellerTradeLog> logs = marketService.listRecentSellerTradeLogs(player.getUniqueId(), limit);
      if (logs.isEmpty()) {
        player.sendMessage(msg(player, "command.market.logs_empty"));
        return true;
      }
      player.sendMessage(msg(player, "command.market.logs_title"));
      for (MarketService.SellerTradeLog log : logs) {
        player.sendMessage(msg(player, "command.market.logs_entry", Map.of(
            "tradeId", log.tradeId(),
            "itemMaterial", log.itemMaterial(),
            "quantity", log.quantity(),
            "buyerName", log.buyerName(),
            "totalPrice", log.totalPrice(),
            "currency", log.currency().name(),
            "status", humanizeMarketStatus(player, log.status()),
            "createdAt", LOG_TIME_FORMATTER.format(log.createdAt()))));
      }
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "command.market.logs_failed",
          Map.of("reason", humanizeMarketError(player, exception))));
    }
    return true;
  }

  private boolean handleReload(CommandSender sender) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage(msg(sender, "command.common.no_permission"));
      return true;
    }
    try {
      plugin.reloadRuntimeConfig();
      sender.sendMessage(msg(sender, "command.reload.success"));
    } catch (Exception exception) {
      sender.sendMessage(msg(sender, "command.reload.failed"));
      plugin.getLogger().log(Level.SEVERE, messageService.getConsole("console.reload_failed"), exception);
    }
    return true;
  }

  private boolean handleRecharge(CommandSender sender, String[] args) {
    if (args.length >= 2 && args[1].equalsIgnoreCase("fix")) {
      return handleRechargeFix(sender, args);
    }
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg(sender, "command.recharge.player_only"));
      return true;
    }
    if (args.length < 2) {
      player.sendMessage(msg(player, "command.recharge.usage"));
      return true;
    }
    UUID playerUuid = player.getUniqueId();
    long amountMinor;
    try {
      amountMinor = rechargeService.yuanToAmountMinor(args[1]);
    } catch (ServiceException exception) {
      player.sendMessage(msg(player, "command.recharge.failed",
          Map.of("reason", humanizeRechargeError(player, exception))));
      return true;
    }
    player.sendMessage(msg(player, "command.recharge.creating"));
    schedulerBridge.runAsync(() -> {
      try {
        RechargeService.UserBinding binding = rechargeService.findUserByPlayer(playerUuid);
        if (binding == null) {
          schedulerBridge.runPlayer(
              playerUuid,
              target -> target.sendMessage(msg(target, "command.recharge.not_bound")),
              () -> { });
          return;
        }
        String currency = settingsSupplier.get().paymentSettings().primaryRechargeCurrency();
        long coinAmount = rechargeService.calculateCoinAmount(
            amountMinor,
            currency,
            com.webshopx.payment.api.PaymentMethod.AUTO);
        RechargeService.RechargeCreateResult result = rechargeService.createRechargeOrder(
            new RechargeService.RechargeCreateRequest(
                binding.userId(),
                playerUuid,
                amountMinor,
                currency,
                0L,
                com.webshopx.payment.api.PaymentMethod.AUTO,
                null,
                "MINECRAFT"));
        schedulerBridge.runPlayer(
            playerUuid,
            target -> sendRechargeCreated(target, result, amountMinor, currency, coinAmount),
            () -> { });
      } catch (ServiceException exception) {
        schedulerBridge.runPlayer(
            playerUuid,
            target -> target.sendMessage(msg(target, "command.recharge.failed",
                Map.of("reason", humanizeRechargeError(target, exception)))),
            () -> { });
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.WARNING, "Failed to create recharge order", exception);
        schedulerBridge.runPlayer(
            playerUuid,
            target -> target.sendMessage(msg(target, "command.recharge.failed",
                Map.of("reason", messageService.get(target, "error.recharge.internal_error")))),
            () -> { });
      }
    });
    return true;
  }

  private boolean handleRechargeFix(CommandSender sender, String[] args) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage(msg(sender, "command.common.no_permission"));
      return true;
    }
    if (args.length < 3) {
      sender.sendMessage(msg(sender, "command.recharge.fix_usage"));
      return true;
    }
    String orderId = args[2];
    sender.sendMessage(msg(sender, "command.recharge.fixing"));
    schedulerBridge.runAsync(() -> {
      RechargeService.FixRechargeResult result;
      try {
        result = rechargeService.fixRechargeOrder(orderId);
      } catch (ServiceException exception) {
        result = RechargeService.FixRechargeResult.fail(orderId, null, exception.code(), exception.getMessage());
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.WARNING, "Failed to fix recharge order", exception);
        result = RechargeService.FixRechargeResult.fail(
            orderId,
            null,
            "internal_error",
            messageService.getConsole("error.recharge.internal_error"));
      }
      RechargeService.FixRechargeResult finalResult = result;
      schedulerBridge.runGlobal(() -> sender.sendMessage(formatFixResult(sender, finalResult)));
    });
    return true;
  }

  private void sendRechargeCreated(
      Player player,
      RechargeService.RechargeCreateResult result,
      long amountMinor,
      String currency,
      long coinAmount) {
    if (!result.success()) {
      player.sendMessage(msg(player, "command.recharge.failed",
          Map.of("reason", result.message())));
      return;
    }
    player.sendMessage(msg(player, "command.recharge.created"));
    player.sendMessage(msg(player, "command.recharge.amount",
        Map.of("amount", formatMinorCurrency(amountMinor), "currency", currency)));
    player.sendMessage(msg(player, "command.recharge.coins",
        Map.of("coins", coinAmount)));
    if (result.expireTime() != null) {
      player.sendMessage(msg(player, "command.recharge.expires",
          Map.of("time", result.expireTime())));
    }
    String payUrl = result.payUrl();
    if (payUrl == null || payUrl.isBlank()) {
      player.sendMessage(msg(player, "command.recharge.no_pay_url"));
      return;
    }
    player.sendMessage(Component.text(
            messageService.get(player, "command.recharge.pay_link"),
            NamedTextColor.AQUA)
        .clickEvent(ClickEvent.openUrl(payUrl)));
    player.sendMessage(msg(player, "command.recharge.pay_url", Map.of("url", payUrl)));
  }

  private String formatFixResult(CommandSender sender, RechargeService.FixRechargeResult result) {
    if (result.success()) {
      return msg(sender, "command.recharge.fix_success", Map.of(
          "order", result.orderId(),
          "status", result.status(),
          "fixed", result.fixed()));
    }
    return msg(sender, "command.recharge.fix_failed", Map.of(
        "order", result.orderId(),
        "code", result.errorCode(),
        "message", result.message()));
  }

  private String formatMinorCurrency(long amountMinor) {
    long major = amountMinor / 100L;
    long minor = Math.abs(amountMinor % 100L);
    return major + "." + (minor < 10L ? "0" : "") + minor;
  }

  private boolean handleRedeem(CommandSender sender, String[] args) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage(msg(sender, "command.common.no_permission"));
      return true;
    }
    if (args.length < 2 || !args[1].equalsIgnoreCase("create")) {
      sender.sendMessage(msg(sender, "command.redeem.usage"));
      return true;
    }
    if (args.length < 4) {
      sender.sendMessage(msg(sender, "command.redeem.usage"));
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
      sender.sendMessage(msg(sender, "command.redeem.created", Map.of("code", code)));
      return true;
    } catch (NumberFormatException exception) {
      sender.sendMessage(msg(sender, "command.redeem.number"));
      return true;
    } catch (ServiceException exception) {
      sender.sendMessage(msg(sender, "command.redeem.failed",
          Map.of("reason", humanizeRedeemError(sender, exception))));
      return true;
    }
  }

  private boolean handleWalletDelta(CommandSender sender, String[] args, CurrencyType currency) {
    if (!sender.hasPermission("webshop.admin")) {
      sender.sendMessage(msg(sender, "command.common.no_permission"));
      return true;
    }
    if (args.length < 3) {
      sender.sendMessage(msg(sender, currency == CurrencyType.GAME_COIN
          ? "command.wallet.gamecoin_usage"
          : "command.wallet.shopcoin_usage"));
      return true;
    }

    String identifier = args[1];
    long delta;
    try {
      delta = Long.parseLong(args[2]);
      if (delta == 0L) {
        throw new NumberFormatException("zero delta");
      }
    } catch (NumberFormatException exception) {
      sender.sendMessage(msg(sender, "command.wallet.number"));
      return true;
    }

    String reason = args.length >= 4
        ? String.join(" ", Arrays.copyOfRange(args, 3, args.length))
        : "COMMAND_ADJUST";
    String currencyName = currencyDisplayName(currency);
    sender.sendMessage(msg(sender, "command.wallet.adjusting", Map.of("currency", currencyName)));

    schedulerBridge.runAsync(() -> {
      try {
        AdminService.UserSupportView user = adminService.lookupUser(identifier)
            .orElseThrow(() -> new ServiceException("user_not_found", "User not found"));
        WalletService.WalletBalance balance = adminService.adjustWallet(
            user.userId(),
            currency,
            delta,
            reason);
        long currentBalance = balanceFor(balance, currency);
        schedulerBridge.runGlobal(() -> sender.sendMessage(msg(sender, "command.wallet.adjusted", Map.of(
            "username", user.username(),
            "currency", currencyName,
            "delta", delta,
            "balance", currentBalance))));
      } catch (ServiceException exception) {
        schedulerBridge.runGlobal(() -> sender.sendMessage(msg(sender, "command.wallet.failed",
            Map.of("reason", humanizeWalletError(sender, exception)))));
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.WARNING, "Failed to adjust wallet from command", exception);
        schedulerBridge.runGlobal(() -> sender.sendMessage(msg(sender, "command.wallet.failed",
            Map.of("reason", messageService.get(sender, "error.wallet.internal_error")))));
      }
    });
    return true;
  }

  private void sendHelp(CommandSender sender) {
    messageService.getList(sender, "command.help.base").forEach(sender::sendMessage);
    if (sender.hasPermission("webshop.admin")) {
      messageService.getList(sender, "command.help.admin").forEach(sender::sendMessage);
    }
  }

  private String humanizeMarketError(CommandSender sender, ServiceException exception) {
    return switch (exception.code()) {
      case "invalid_price" -> messageService.get(sender, "error.market.invalid_price");
      case "invalid_amount" -> messageService.get(sender, "error.market.invalid_amount");
      case "empty_hand" -> messageService.get(sender, "error.market.empty_hand");
      case "insufficient_item" -> messageService.get(sender, "error.market.insufficient_item");
      case "invalid_item" -> messageService.get(sender, "error.market.invalid_item");
      case "not_bound" -> messageService.get(sender, "error.market.not_bound");
      case "supply_empty" -> messageService.get(sender, "error.market.supply_empty");
      case "supply_missing" -> messageService.get(sender, "error.market.supply_missing");
      case "invalid_listing" -> messageService.get(sender, "error.market.invalid_listing");
      case "listing_missing" -> messageService.get(sender, "error.market.listing_missing");
      case "listing_unavailable" -> messageService.get(sender, "error.market.listing_unavailable");
      case "invalid_trade" -> messageService.get(sender, "error.market.invalid_trade");
      case "insufficient_quantity" -> messageService.get(sender, "error.market.insufficient_quantity");
      case "forbidden" -> messageService.get(sender, "error.market.forbidden");
      case "sync_timeout" -> messageService.get(sender, "error.market.sync_timeout");
      case "sync_interrupted" -> messageService.get(sender, "error.market.sync_interrupted");
      case "listing_limit" -> messageService.get(sender, "error.market.listing_limit");
      case "buy_requires_direct_mode" -> messageService.get(sender, "error.market.buy_requires_direct_mode");
      case "buy_requires_manual_source" -> messageService.get(sender, "error.market.buy_requires_manual_source");
      case "buy_requires_fixed_price" -> messageService.get(sender, "error.market.buy_requires_fixed_price");
      case "buy_escrow_insufficient" -> messageService.get(sender, "error.market.buy_escrow_insufficient");
      case "buy_order_not_active" -> messageService.get(sender, "error.market.buy_order_not_active");
      case "cannot_fulfill_own_buy_order" -> messageService.get(sender, "error.market.cannot_fulfill_own_buy_order");
      case "fulfill_item_not_match" -> messageService.get(sender, "error.market.fulfill_item_not_match");
      case "invalid_market_side" -> messageService.get(sender, "error.market.invalid_market_side");
      case "invalid_tag" -> messageService.get(sender, "error.market.invalid_tag");
      case "tag_disabled" -> messageService.get(sender, "error.market.tag_disabled");
      case "limitation_item_forbidden" -> messageService.get(sender, "error.market.limitation_item_forbidden");
      case "limitation_currency_not_allowed" -> messageService.get(sender, "error.market.limitation_currency_not_allowed");
      case "limitation_trade_mode_not_allowed" -> messageService.get(sender, "error.market.limitation_trade_mode_not_allowed");
      case "limitation_side_not_allowed" -> messageService.get(sender, "error.market.limitation_side_not_allowed");
      default -> exception.getMessage();
    };
  }

  private String humanizeDeliveryError(CommandSender sender, ServiceException exception) {
    return switch (exception.code()) {
      case "claim_token_invalid" -> messageService.get(sender, "error.delivery.claim_token_invalid");
      case "claim_forbidden" -> messageService.get(sender, "error.delivery.claim_forbidden");
      default -> exception.getMessage();
    };
  }

  private String humanizeRedeemError(CommandSender sender, ServiceException exception) {
    return switch (exception.code()) {
      case "invalid_amount" -> messageService.get(sender, "error.redeem.invalid_amount");
      case "code_exists" -> messageService.get(sender, "error.redeem.code_exists");
      default -> exception.getMessage();
    };
  }

  private String humanizeRechargeError(CommandSender sender, ServiceException exception) {
    return switch (exception.code()) {
      case "payment_unavailable" -> messageService.get(sender, "error.recharge.payment_unavailable");
      case "payment_provider_not_found" -> messageService.get(sender, "error.recharge.payment_provider_not_found");
      case "payment_api_mismatch" -> messageService.get(sender, "error.recharge.payment_api_mismatch");
      case "payment_api_error" -> messageService.get(sender, "error.recharge.payment_api_error");
      case "payment_create_failed" -> messageService.get(sender, "error.recharge.payment_create_failed");
      case "payment_query_failed" -> messageService.get(sender, "error.recharge.payment_query_failed");
      case "payment_notify_rejected" -> messageService.get(sender, "error.recharge.payment_notify_rejected");
      case "payment_amount_mismatch" -> messageService.get(sender, "error.recharge.payment_amount_mismatch");
      case "payment_currency_mismatch" -> messageService.get(sender, "error.recharge.payment_currency_mismatch");
      case "payment_provider_mismatch" -> messageService.get(sender, "error.recharge.payment_provider_mismatch");
      case "payment_order_mismatch" -> messageService.get(sender, "error.recharge.payment_order_mismatch");
      case "payment_unknown_status" -> messageService.get(sender, "error.recharge.payment_unknown_status");
      case "yupay_unavailable" -> messageService.get(sender, "error.recharge.yupay_unavailable");
      case "yupay_api_mismatch" -> messageService.get(sender, "error.recharge.yupay_api_mismatch");
      case "yupay_api_error" -> messageService.get(sender, "error.recharge.yupay_api_error");
      case "invalid_amount", "INVALID_AMOUNT" -> messageService.get(sender, "error.recharge.invalid_amount");
      case "UNSUPPORTED_CURRENCY" -> messageService.get(sender, "error.recharge.unsupported_currency");
      case "METHOD_UNSUPPORTED" -> messageService.get(sender, "error.recharge.unsupported_method");
      case "UNSUPPORTED_RECHARGE_RATE" -> messageService.get(sender, "error.recharge.unsupported_rate");
      case "ORDER_NOT_FOUND" -> messageService.get(sender, "error.recharge.order_not_found");
      case "PROVIDER_ORDER_MISMATCH" -> messageService.get(sender, "error.recharge.provider_order_mismatch");
      case "AMOUNT_MISMATCH" -> messageService.get(sender, "error.recharge.amount_mismatch");
      case "CURRENCY_MISMATCH" -> messageService.get(sender, "error.recharge.currency_mismatch");
      case "ORDER_CLOSED" -> messageService.get(sender, "error.recharge.order_closed");
      case "INVALID_STATUS" -> messageService.get(sender, "error.recharge.invalid_status");
      case "user_missing" -> messageService.get(sender, "error.recharge.user_missing");
      case "bad_request" -> messageService.get(sender, "error.recharge.bad_request");
      case "internal_error" -> messageService.get(sender, "error.recharge.internal_error");
      default -> exception.getMessage();
    };
  }

  private String humanizeWalletError(CommandSender sender, ServiceException exception) {
    return switch (exception.code()) {
      case "user_not_found" -> messageService.get(sender, "error.wallet.user_not_found");
      case "invalid_amount" -> messageService.get(sender, "error.wallet.invalid_amount");
      case "invalid_currency" -> messageService.get(sender, "error.wallet.invalid_currency");
      case "insufficient_funds" -> messageService.get(sender, "error.wallet.insufficient_funds");
      case "vault_unavailable" -> messageService.get(sender, "error.wallet.vault_unavailable");
      case "vault_error" -> messageService.get(sender, "error.wallet.vault_error");
      default -> exception.getMessage();
    };
  }

  private String currencyDisplayName(CurrencyType currency) {
    PluginSettings.CurrencyDisplaySettings display = settingsSupplier.get().currencyDisplaySettings();
    return switch (currency) {
      case SHOP_COIN -> display.shopCoinName();
      case GAME_COIN -> display.gameCoinName();
    };
  }

  private long balanceFor(WalletService.WalletBalance balance, CurrencyType currency) {
    return switch (currency) {
      case SHOP_COIN -> balance.shopCoin();
      case GAME_COIN -> balance.gameCoin();
    };
  }

  private String humanizeMarketStatus(CommandSender sender, String status) {
    String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    return switch (normalized) {
      case "ACTIVE" -> messageService.get(sender, "enum.market_status.active");
      case "PAUSED" -> messageService.get(sender, "enum.market_status.paused");
      case "SOLD" -> messageService.get(sender, "enum.market_status.sold");
      case "UNLISTED" -> messageService.get(sender, "enum.market_status.unlisted");
      case "SUPPLY_EMPTY" -> messageService.get(sender, "enum.market_status.supply_empty");
      default -> normalized;
    };
  }

  private String msg(CommandSender sender, String key) {
    return messageService.get(sender, key);
  }

  private String msg(CommandSender sender, String key, Map<String, ?> params) {
    return messageService.format(sender, key, params);
  }

  private List<String> filterByPrefix(List<String> source, String userInput) {
    String token = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
    return source.stream()
        .filter(item -> item.toLowerCase(Locale.ROOT).startsWith(token))
        .collect(Collectors.toList());
  }
}
