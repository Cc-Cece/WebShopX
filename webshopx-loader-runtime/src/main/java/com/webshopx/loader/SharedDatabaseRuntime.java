package com.webshopx.loader;

import com.webshopx.AdminAuditService;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.NotificationService;
import com.webshopx.PlayerPresenceService;
import com.webshopx.RedeemCodeService;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceCheckoutAdapter;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedPromotionService;
import com.webshopx.WalletService;
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
  private final NotificationService notifications;
  private final SharedPromotionService promotions;

  private SharedDatabaseRuntime(
      DatabaseManager database,
      AuthService authentication,
      PlayerPresenceService presence,
      WalletService wallet,
      RedeemCodeService redeemCodes,
      AdminService administration,
      AdminAuditService audit,
      SharedCommerceService commerce,
      NotificationService notifications,
      SharedPromotionService promotions) {
    this.database = database;
    this.authentication = authentication;
    this.presence = presence;
    this.wallet = wallet;
    this.redeemCodes = redeemCodes;
    this.administration = administration;
    this.audit = audit;
    this.commerce = commerce;
    this.notifications = notifications;
    this.promotions = promotions;
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
    int tokenLength = Integer.getInteger("webshopx.auth.token-length", 48);
    int sessionHours = Integer.getInteger("webshopx.auth.session-hours", 24);
    AuthService authentication =
        new AuthService(database, () -> new AuthService.SessionSettings(tokenLength, sessionHours));
    String serverId = System.getProperty("webshopx.server-id", "standalone");
    int presenceTtl = Integer.getInteger("webshopx.presence.ttl-seconds", 120);
    PlayerPresenceService presence =
        new PlayerPresenceService(
            database, () -> new PlayerPresenceService.PresenceSettings(serverId, presenceTtl));
    WalletService wallet =
        new WalletService(database, WalletService.ExchangePolicy::disabled, null, null);
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
    NotificationService notifications = new NotificationService(database);
    SharedPromotionService promotions =
        new SharedPromotionService(
            database, wallet, new SharedCommerceCheckoutAdapter(commerce, serverId));
    return new SharedDatabaseRuntime(
        database,
        authentication,
        presence,
        wallet,
        redeemCodes,
        administration,
        audit,
        commerce,
        notifications,
        promotions);
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

  NotificationService notifications() {
    return notifications;
  }

  SharedPromotionService promotions() {
    return promotions;
  }

  @Override
  public void close() {
    presence.markServerOffline(presence.currentServerId());
    database.close();
  }
}
