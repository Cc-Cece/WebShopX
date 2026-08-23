package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.webshopx.SharedCommerceService.ListingRequest;
import com.webshopx.SharedCommerceService.ProductInput;
import com.webshopx.SharedCommerceService.ProductKind;
import com.webshopx.SharedCommerceService.PurchaseRequest;
import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.webshopx.core.ItemEnvelopeService;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Production-shaped SQLite upgrade, reconciliation and cold-backup rollback rehearsal. */
class MigrationRollbackRehearsalTest {
  @TempDir Path temporaryDirectory;

  @Test void migrationIsIdempotentAndBackupRestoreReconcilesCriticalState() throws Exception {
    Path live = temporaryDirectory.resolve("live.db");
    Path backup = temporaryDirectory.resolve("pre-migration.db");
    Snapshot expected;
    DatabaseManager database = database(live);
    try {
      ensureTwice(database);
      expected = seedAndSnapshot(database);
    } finally {
      database.close();
    }
    Files.copy(live, backup);

    DatabaseManager upgraded = database(live);
    try {
      ensureTwice(upgraded);
      assertEquals(expected, snapshot(upgraded));
      try (Connection connection = upgraded.getConnection(); Statement statement = connection.createStatement()) {
        statement.executeUpdate("UPDATE wallets SET shop_coin = shop_coin + 999");
        statement.executeUpdate("UPDATE orders SET status = 'DELIVERED'");
      }
    } finally {
      upgraded.close();
    }

    Files.copy(backup, live, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    DatabaseManager restored = database(live);
    try {
      ensureTwice(restored);
      assertEquals(expected, snapshot(restored));
    } finally {
      restored.close();
    }
  }

  private static DatabaseManager database(Path file) {
    DatabaseManager database = new DatabaseManager(null, new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 1,
        file.toString(), "WAL", "FULL", 5_000, 3, List.of(10, 25, 50)));
    database.start();
    return database;
  }

  private static void ensureTwice(DatabaseManager database) {
    SchemaProvider provider = SchemaProvider.forType(DbType.SQLITE);
    provider.ensureSchema(database, ZoneOffset.UTC);
    provider.ensureSchema(database, ZoneOffset.UTC);
  }

  private static Snapshot seedAndSnapshot(DatabaseManager database) throws Exception {
    AuthService auth = new AuthService(database, () -> new AuthService.SessionSettings(40, 2));
    WalletService wallets = new WalletService(
        database, WalletService.ExchangePolicy::disabled, null, null);
    SharedCommerceService commerce = new SharedCommerceService(database, wallets);
    UUID buyerId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    long buyer = auth.setPasswordFromGame(buyerId, "MigrationBuyer", "migration-secret").userId();
    long seller = auth.setPasswordFromGame(sellerId, "MigrationSeller", "migration-secret").userId();
    wallets.adjustBalance(buyer, CurrencyType.SHOP_COIN, 500, "MIGRATION", "seed");
    ItemEnvelope item = envelope();
    commerce.createListing(new ListingRequest(
        seller, sellerId, CurrencyType.SHOP_COIN, 75, 1, item, "migration-fixture"));
    var product = commerce.createProduct(new ProductInput(
        "MIGRATION_ITEM", "Migration Item", null, CurrencyType.SHOP_COIN, 50,
        ProductKind.GIVE_ITEM, "", "minecraft:stone", 2, true));
    commerce.purchase(new PurchaseRequest(
        buyer, buyerId, product.id(), 1, "migration-order", "fabric-a"));
    return snapshot(database);
  }

  private static ItemEnvelope envelope() {
    byte[] payload = "production-shaped-native-payload".getBytes(StandardCharsets.UTF_8);
    return new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture")).create(
        "fixture", 1, new CompatibilityDomain(
            "fabric", "fabric", "1.20.1", 1, "sha256:migration"),
        "minecraft:diamond", 1, payload, Map.of("source", "migration"));
  }

  private static Snapshot snapshot(DatabaseManager database) throws Exception {
    try (Connection connection = database.getConnection(); Statement statement = connection.createStatement()) {
      long users = scalar(statement, "SELECT COUNT(*) FROM web_users");
      long walletTotal = scalar(statement, "SELECT COALESCE(SUM(shop_coin), 0) FROM wallets");
      long ledgerTotal = scalar(statement,
          "SELECT COALESCE(SUM(CASE WHEN currency = 'SHOP_COIN' THEN delta ELSE 0 END), 0) FROM wallet_ledger");
      long pendingOrders = scalar(statement, "SELECT COUNT(*) FROM orders WHERE status = 'PENDING'");
      long pendingDeliveries = scalar(statement,
          "SELECT COUNT(*) FROM delivery_queue WHERE status = 'PENDING'");
      String envelopeHash;
      try (ResultSet rows = statement.executeQuery(
          "SELECT raw_item_blob FROM market_listings WHERE raw_item_blob IS NOT NULL LIMIT 1")) {
        rows.next();
        envelopeHash = new ItemEnvelopeBinaryCodec().decode(rows.getBytes(1)).payloadHash();
      }
      return new Snapshot(users, walletTotal, ledgerTotal, pendingOrders, pendingDeliveries, envelopeHash);
    }
  }

  private static long scalar(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      result.next();
      return result.getLong(1);
    }
  }

  private record Snapshot(long users, long walletTotal, long ledgerTotal,
                          long pendingOrders, long pendingDeliveries, String envelopeHash) { }
}
