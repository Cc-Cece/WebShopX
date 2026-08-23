package com.webshopx.loader;

import com.webshopx.AuthService;
import com.webshopx.AdminService;
import com.webshopx.AdminAuditService;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.PlayerPresenceService;
import com.webshopx.RedeemCodeService;
import com.webshopx.SchemaProvider;
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

  private SharedDatabaseRuntime(
      DatabaseManager database, AuthService authentication, PlayerPresenceService presence,
      WalletService wallet, RedeemCodeService redeemCodes, AdminService administration,
      AdminAuditService audit) {
    this.database = database;
    this.authentication = authentication;
    this.presence = presence;
    this.wallet = wallet;
    this.redeemCodes = redeemCodes;
    this.administration = administration;
    this.audit = audit;
  }

  static SharedDatabaseRuntime start(Path dataDirectory) {
    DatabaseSettings settings = new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 2,
        dataDirectory.resolve("webshopx.db").toString(), "WAL", "NORMAL",
        5_000, 5, List.of(10, 50, 100, 250, 500));
    DatabaseManager database = new DatabaseManager(
        Logger.getLogger("com.webshopx.loader.database"), settings);
    database.start();
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    int tokenLength = Integer.getInteger("webshopx.auth.token-length", 48);
    int sessionHours = Integer.getInteger("webshopx.auth.session-hours", 24);
    AuthService authentication = new AuthService(
        database, () -> new AuthService.SessionSettings(tokenLength, sessionHours));
    String serverId = System.getProperty("webshopx.server-id", "standalone");
    int presenceTtl = Integer.getInteger("webshopx.presence.ttl-seconds", 120);
    PlayerPresenceService presence = new PlayerPresenceService(database,
        () -> new PlayerPresenceService.PresenceSettings(serverId, presenceTtl));
    WalletService wallet = new WalletService(
        database, WalletService.ExchangePolicy::disabled, null, null);
    RedeemCodeService redeemCodes = new RedeemCodeService(database, wallet);
    AdminService administration = new AdminService(database, authentication, wallet);
    AdminAuditService audit = new AdminAuditService(database);
    return new SharedDatabaseRuntime(
        database, authentication, presence, wallet, redeemCodes, administration, audit);
  }

  AuthService authentication() { return authentication; }
  PlayerPresenceService presence() { return presence; }
  WalletService wallet() { return wallet; }
  RedeemCodeService redeemCodes() { return redeemCodes; }
  AdminService administration() { return administration; }
  AdminAuditService audit() { return audit; }

  @Override public void close() {
    presence.markServerOffline(presence.currentServerId());
    database.close();
  }
}
