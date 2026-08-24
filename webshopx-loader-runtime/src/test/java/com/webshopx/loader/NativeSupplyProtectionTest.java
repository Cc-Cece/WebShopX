package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.AuthService;
import com.webshopx.CurrencyType;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceService;
import com.webshopx.WalletService;
import com.webshopx.core.ItemEnvelopeService;
import com.webshopx.platform.CompatibilityDomain;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class NativeSupplyProtectionTest {
  @TempDir Path directory;
  private DatabaseManager database;
  private SharedCommerceService commerce;
  private UUID owner;
  private long listingId;

  @BeforeEach
  void start() {
    database = new DatabaseManager(null, new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 2,
        directory.resolve("protection.db").toString(), "WAL", "NORMAL", 5_000, 3,
        List.of(10, 25)));
    database.start();
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    commerce = new SharedCommerceService(
        database, new WalletService(database, WalletService.ExchangePolicy::disabled, null, null));
    owner = UUID.randomUUID();
    long ownerUserId = new AuthService(database, () -> new AuthService.SessionSettings(40, 2))
        .setPasswordFromGame(owner, "SupplyOwner", "supply-secret").userId();
    var item = new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture")).create(
        "fixture", 1, new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:test"),
        "minecraft:diamond", 1, new byte[] {1}, Map.of());
    listingId = commerce.createListing(new SharedCommerceService.ListingRequest(
        ownerUserId, owner, CurrencyType.SHOP_COIN, 10, 1, item, null)).id();
    updateProtection(true);
  }

  @AfterEach
  void stop() {
    if (database != null) database.close();
  }

  @Test
  void protectsInteractionAndDestructionWithFailClosedReflection() {
    FakeLevel level = new FakeLevel();
    FakePosition position = new FakePosition(1, 64, 2);
    assertFalse(NativeSupplyProtection.denied(
        commerce, new FakePlayer(owner), level, position,
        NativeSupplyProtection.Action.INTERACT));
    assertTrue(NativeSupplyProtection.denied(
        commerce, new FakePlayer(UUID.randomUUID()), level, position,
        NativeSupplyProtection.Action.INTERACT));
    assertTrue(NativeSupplyProtection.denied(
        commerce, new FakePlayer(owner), level, position,
        NativeSupplyProtection.Action.BREAK));
    assertFalse(NativeSupplyProtection.denied(
        commerce, new FakePlayer(UUID.randomUUID()), level, new FakePosition(8, 64, 2),
        NativeSupplyProtection.Action.INTERACT));
    assertTrue(NativeSupplyProtection.denied(
        commerce, new FakePlayer(owner), level, new Object(),
        NativeSupplyProtection.Action.INTERACT));

    updateProtection(false);
    assertFalse(NativeSupplyProtection.denied(
        commerce, new FakePlayer(UUID.randomUUID()), level, position,
        NativeSupplyProtection.Action.INTERACT));
    assertTrue(NativeSupplyProtection.denied(
        commerce, new FakePlayer(owner), level, position,
        NativeSupplyProtection.Action.EXPLOSION));
  }

  private void updateProtection(boolean accessProtected) {
    database.withConnection(connection -> {
      try (var statement = connection.prepareStatement(
          "UPDATE market_listings SET source_mode='SUPPLY',status='ACTIVE',"
              + "supply_world='minecraft:overworld',supply_x=1,supply_y=64,supply_z=2,"
              + "supply_access_protected=? WHERE id=?")) {
        statement.setBoolean(1, accessProtected);
        statement.setLong(2, listingId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  public record FakePlayer(UUID id) {
    public UUID getUUID() {
      return id;
    }
  }

  public record FakePosition(int x, int y, int z) {
    public int getX() {
      return x;
    }

    public int getY() {
      return y;
    }

    public int getZ() {
      return z;
    }
  }

  public static final class FakeLevel {
    public FakeDimension dimension() {
      return new FakeDimension();
    }
  }

  public static final class FakeDimension {
    public String location() {
      return "minecraft:overworld";
    }
  }
}
