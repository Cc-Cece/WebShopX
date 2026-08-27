package com.webshopx.loader;

import com.google.gson.JsonObject;
import com.webshopx.AdminAuditService;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.NotificationService;
import com.webshopx.PlayerPresenceService;
import com.webshopx.RedeemCodeService;
import com.webshopx.RefundPolicyService;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceCheckoutAdapter;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedContentService;
import com.webshopx.SharedMarketEscrowService;
import com.webshopx.SharedPromotionService;
import com.webshopx.SharedRuntimeConfigService;
import com.webshopx.WalletService;
import com.webshopx.core.JdbcEventInbox;
import com.webshopx.platform.PlatformPorts.PlatformEvent;
import java.sql.SQLException;
import java.time.Clock;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.logging.Logger;

/** Loader-owned standalone database with a forward-only compatibility marker. */
final class SharedDatabaseRuntime implements AutoCloseable {
  private final DatabaseManager database;
  private final AuthService authentication;
  private final PlayerPresenceService presence;
  private final WalletService wallet;
  private final RedeemCodeService redeemCodes;
  private final AdminService administration;
  private final AdminAuditService audit;
  private final SharedCommerceService commerce;
  private final SharedContentService content;
  private final NotificationService notifications;
  private final SharedPromotionService promotions;
  private final RefundPolicyService refundPolicies;
  private final SharedRuntimeConfigService runtimeConfig;
  private final LoaderPaymentProviderRegistry paymentProviders;
  private final JdbcEventInbox eventInbox;

  private SharedDatabaseRuntime(
      DatabaseManager database,
      AuthService authentication,
      PlayerPresenceService presence,
      WalletService wallet,
      RedeemCodeService redeemCodes,
      AdminService administration,
      AdminAuditService audit,
      SharedCommerceService commerce,
      SharedContentService content,
      NotificationService notifications,
      SharedPromotionService promotions,
      RefundPolicyService refundPolicies,
      SharedRuntimeConfigService runtimeConfig,
      LoaderPaymentProviderRegistry paymentProviders,
      JdbcEventInbox eventInbox) {
    this.database = database;
    this.authentication = authentication;
    this.presence = presence;
    this.wallet = wallet;
    this.redeemCodes = redeemCodes;
    this.administration = administration;
    this.audit = audit;
    this.commerce = commerce;
    this.content = content;
    this.notifications = notifications;
    this.promotions = promotions;
    this.refundPolicies = refundPolicies;
    this.runtimeConfig = runtimeConfig;
    this.paymentProviders = paymentProviders;
    this.eventInbox = eventInbox;
  }

  static SharedDatabaseRuntime start(Path dataDirectory) {
    DbType type = DbType.fromRaw(System.getProperty("webshopx.database.type", "sqlite"));
    DatabaseSettings settings =
        new DatabaseSettings(
            type,
            System.getProperty("webshopx.database.host", "127.0.0.1"),
            Integer.getInteger("webshopx.database.port", 3306),
            System.getProperty("webshopx.database.schema", "webshopx"),
            System.getProperty("webshopx.database.username", "webshopx"),
            System.getProperty("webshopx.database.password", ""),
            Boolean.getBoolean("webshopx.database.use-ssl"),
            Boolean.getBoolean("webshopx.database.allow-public-key-retrieval"),
            System.getProperty("webshopx.database.server-rsa-public-key-file", ""),
            Integer.getInteger("webshopx.database.pool-size", 4),
            dataDirectory.resolve("webshopx.db").toString(),
            "WAL",
            "NORMAL",
            5_000,
            5,
            List.of(10, 50, 100, 250, 500));
    DatabaseManager database =
        new DatabaseManager(Logger.getLogger("com.webshopx.loader.database"), settings);
    database.start();
    SchemaProvider.forType(type).ensureSchema(database, ZoneOffset.UTC);
    SharedRuntimeConfigService runtimeConfig = new SharedRuntimeConfigService(database);
    AuthService authentication =
        new AuthService(
            database,
            () -> {
              JsonObject config = runtimeConfig.read("webshop_runtime").config();
              return new AuthService.SessionSettings(
                  configInt(
                      config,
                      "accessTokenLength",
                      Integer.getInteger("webshopx.auth.token-length", 48),
                      16,
                      256),
                  configInt(
                      config,
                      "sessionExpireHours",
                      Integer.getInteger("webshopx.auth.session-hours", 24),
                      1,
                      24 * 365));
            });
    String serverId = System.getProperty("webshopx.server-id", "standalone");
    int presenceTtl = Integer.getInteger("webshopx.presence.ttl-seconds", 120);
    PlayerPresenceService presence =
        new PlayerPresenceService(
            database, () -> new PlayerPresenceService.PresenceSettings(serverId, presenceTtl));
    WalletService wallet =
        new WalletService(database, () -> exchangePolicy(runtimeConfig), null, null);
    RedeemCodeService redeemCodes = new RedeemCodeService(database, wallet);
    AdminService administration = new AdminService(database, authentication, wallet);
    administration.ensureBootstrapAdmin(
        new AdminService.AdminBootstrapSettings(
            Boolean.parseBoolean(System.getProperty("webshopx.admin.bootstrap.enabled", "false")),
            System.getProperty("webshopx.admin.bootstrap.username", "admin"),
            System.getProperty("webshopx.admin.bootstrap.password", ""),
            System.getProperty("webshopx.admin.bootstrap.role", "SUPER_ADMIN")));
    AdminAuditService audit = new AdminAuditService(database);
    SharedCommerceService commerce = new SharedCommerceService(database, wallet);
    LoaderPaymentProviderRegistry paymentProviders =
        LoaderPaymentProviderRegistry.discover(
            commerce,
            serverId,
            Thread.currentThread().getContextClassLoader(),
            Logger.getLogger("com.webshopx.loader.payment"));
    SharedContentService content = new SharedContentService(database);
    NotificationService notifications = new NotificationService(database);
    SharedPromotionService promotions =
        new SharedPromotionService(
            database, wallet, new SharedCommerceCheckoutAdapter(commerce, serverId));
    RefundPolicyService refundPolicies = new RefundPolicyService(database);
    JdbcEventInbox eventInbox = new JdbcEventInbox(database::getConnection, Clock.systemUTC());
    try {
      eventInbox.initialize();
    } catch (SQLException failure) {
      database.close();
      throw new IllegalStateException("Cannot initialize durable cluster event inbox", failure);
    }
    return new SharedDatabaseRuntime(
        database,
        authentication,
        presence,
        wallet,
        redeemCodes,
        administration,
        audit,
        commerce,
        content,
        notifications,
        promotions,
        refundPolicies,
        runtimeConfig,
        paymentProviders,
        eventInbox);
  }

  AuthService authentication() {
    return authentication;
  }

  PlayerPresenceService presence() {
    return presence;
  }

  WalletService wallet() {
    return wallet;
  }

  RedeemCodeService redeemCodes() {
    return redeemCodes;
  }

  AdminService administration() {
    return administration;
  }

  AdminAuditService audit() {
    return audit;
  }

  SharedCommerceService commerce() {
    return commerce;
  }

  SharedMarketEscrowService marketEscrow(
      com.webshopx.platform.PlatformPorts.InventoryGateway inventories) {
    return new SharedMarketEscrowService(database, commerce, inventories);
  }

  SharedContentService content() {
    return content;
  }

  NotificationService notifications() {
    return notifications;
  }

  SharedPromotionService promotions() {
    return promotions;
  }

  RefundPolicyService refundPolicies() {
    return refundPolicies;
  }

  SharedRuntimeConfigService runtimeConfig() {
    return runtimeConfig;
  }

  boolean admitEvent(PlatformEvent event) {
    try {
      return eventInbox.admit(event);
    } catch (SQLException failure) {
      throw new IllegalStateException("Cannot admit cluster event", failure);
    }
  }

  private static WalletService.ExchangePolicy exchangePolicy(
      SharedRuntimeConfigService runtimeConfig) {
    JsonObject root = runtimeConfig.read("exchange").config();
    return new WalletService.ExchangePolicy(
        exchangeDirection(root, "shopToGame"), exchangeDirection(root, "gameToShop"));
  }

  private static WalletService.ExchangeDirection exchangeDirection(JsonObject root, String key) {
    if (!root.has(key) || !root.get(key).isJsonObject()) {
      return new WalletService.ExchangeDirection(false, 0D);
    }
    JsonObject direction = root.getAsJsonObject(key);
    boolean enabled = direction.has("enabled") && direction.get("enabled").getAsBoolean();
    double ratio = direction.has("ratio") ? direction.get("ratio").getAsDouble() : 0D;
    return new WalletService.ExchangeDirection(
        enabled && Double.isFinite(ratio) && ratio > 0D,
        Double.isFinite(ratio) && ratio > 0D ? ratio : 0D);
  }

  private static int configInt(
      JsonObject config, String key, int fallback, int minimum, int maximum) {
    if (!config.has(key) || config.get(key).isJsonNull()) return fallback;
    try {
      return Math.max(minimum, Math.min(maximum, config.get(key).getAsInt()));
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  @Override
  public void close() {
    paymentProviders.close();
    presence.markServerOffline(presence.currentServerId());
    database.close();
  }
}
