package com.webshopx;

import com.google.gson.JsonObject;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
  private final RechargeService rechargeService;
  private final AdminService adminService;
  private final MarketService marketService;
  private final MarketGuiService marketGuiService;
  private final DeliveryService deliveryService;
  private final MailboxService mailboxService;
  private final MailboxGuiService mailboxGuiService;
  private final RuntimeConfigService runtimeConfigService;
  private final HomepageService homepageService;
  private final MessageService messageService;
  private final SchedulerBridge schedulerBridge;
  private final Supplier<PluginSettings> settingsSupplier;

  ShopCommand(
      WebShopPlugin plugin,
      AuthService authService,
      RechargeService rechargeService,
      AdminService adminService,
      MarketService marketService,
      MarketGuiService marketGuiService,
      DeliveryService deliveryService,
      MailboxService mailboxService,
      MailboxGuiService mailboxGuiService,
      RuntimeConfigService runtimeConfigService,
      HomepageService homepageService,
      MessageService messageService,
      SchedulerBridge schedulerBridge,
      Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.authService = authService;
    this.rechargeService = rechargeService;
    this.adminService = adminService;
    this.marketService = marketService;
    this.marketGuiService = marketGuiService;
    this.deliveryService = deliveryService;
    this.mailboxService = mailboxService;
    this.mailboxGuiService = mailboxGuiService;
    this.runtimeConfigService = runtimeConfigService;
    this.homepageService = homepageService;
    this.messageService = messageService;
    this.schedulerBridge = schedulerBridge;
    this.settingsSupplier = settingsSupplier;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (args.length == 0) {
      if (sender instanceof Player player) {
        marketGuiService.openMainMenu(player);
      } else {
        sendHelp(sender);
      }
      return true;
    }

    String subCommand = args[0].toLowerCase(Locale.ROOT);
    return switch (subCommand) {
      case "help" -> {
        sendHelp(sender);
        yield true;
      }
      case "password" -> handlePassword(sender, args);
      case "home" -> handleHome(sender);
      case "gui" -> handleGui(sender);
      case "market" -> handleMarket(sender, args);
      case "claim" -> handleClaim(sender, args);
      case "mailbox" -> handleMailbox(sender, args);
      case "reload" -> handleReload(sender);
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
      options.add("home");
      options.add("gui");
      options.add("market");
      options.add("claim");
      options.add("mailbox");
      if (sender.hasPermission("webshop.admin")) {
        options.add("reload");
        options.add("recharge");
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
        return filterByPrefix(List.of("collect"), args[1]);
      }
      return List.of();
    }

    if (top.equals("recharge")) {
      if (args.length == 2) {
        return sender.hasPermission("webshop.admin")
            ? filterByPrefix(List.of("fix"), args[1])
            : List.of();
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

  private boolean handleHome(CommandSender sender) {
    JsonObject homepage = homepageService.publicDocument();
    if (!homepage.has("enabled") || !homepage.get("enabled").getAsBoolean()) {
      sender.sendMessage(msg(sender, "command.home.not_configured"));
      return true;
    }
    String shopUrl = homepage.has("homeUrl") ? homepage.get("homeUrl").getAsString().trim() : "";
    if (shopUrl.startsWith("/")) {
      String publicUrl = settingsSupplier.get().embeddedWebSettings().publicUrl();
      shopUrl = publicUrl.isBlank() ? "" : publicUrl.replaceAll("/+$", "") + shopUrl;
    }
    if (shopUrl.isBlank()) {
      String publicUrl = settingsSupplier.get().embeddedWebSettings().publicUrl();
      if (!publicUrl.isBlank()) {
        shopUrl = publicUrl.replaceAll("/+$", "") + "/home";
      }
    }
    if (shopUrl.isBlank()) {
      sender.sendMessage(msg(sender, "command.home.not_configured"));
      return true;
    }
    sender.sendMessage(Component.text(
            messageService.get(sender, "command.home.open_link"),
            NamedTextColor.AQUA)
        .clickEvent(ClickEvent.openUrl(shopUrl)));
    return true;
  }

  private boolean handleGui(CommandSender sender) {
    if (!(sender instanceof Player player)) {
      sender.sendMessage(msg(sender, "command.market.player_only"));
      return true;
    }
    marketGuiService.openMainMenu(player);
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
      mailboxGuiService.open(player);
      return true;
    }
    if (!args[1].equalsIgnoreCase("collect") && !args[1].equalsIgnoreCase("claim")) {
      player.sendMessage(msg(player, "command.mailbox.usage"));
      return true;
    }
    MailboxService.MailboxClaimSummary summary = mailboxService.claimPending(player);
    mailboxGuiService.sendSummary(player, summary);
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
    sender.sendMessage(msg(sender, "command.unknown_subcommand"));
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
