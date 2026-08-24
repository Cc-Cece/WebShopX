package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.webshopx.core.ItemEnvelopeService;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import com.webshopx.platform.SupplyInventoryGateway;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedSupplyServiceTest {
  @TempDir Path directory;
  private DatabaseManager database;
  private SharedCommerceService commerce;
  private ItemEnvelope template;
  private long listingId;
  private long sellerId;

  @BeforeEach
  void start() {
    database = new DatabaseManager(null, new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 2,
        directory.resolve("supply.db").toString(), "WAL", "NORMAL", 5_000, 3, List.of(10, 25)));
    database.start();
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    WalletService wallets = new WalletService(
        database, WalletService.ExchangePolicy::disabled, null, null);
    commerce = new SharedCommerceService(database, wallets);
    UUID sellerUuid = UUID.randomUUID();
    sellerId = new AuthService(database, () -> new AuthService.SessionSettings(40, 2))
        .setPasswordFromGame(sellerUuid, "SupplySeller", "supply-secret").userId();
    template = new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture"))
        .create(
            "fixture", 1,
            new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:test"),
            "minecraft:diamond", 1, new byte[] {1, 2, 3}, Map.of());
    listingId = commerce.createListing(new SharedCommerceService.ListingRequest(
        sellerId, sellerUuid, CurrencyType.SHOP_COIN, 10, 1, template, null)).id();
    database.withConnection(connection -> {
      try (var statement = connection.prepareStatement(
          "UPDATE market_listings SET source_mode='SUPPLY',supply_world='minecraft:overworld',"
              + "supply_x=1,supply_y=64,supply_z=2,supply_batch_size=5,supply_max_stock=10,"
              + "quantity=0,status='SUPPLY_EMPTY' WHERE id=?")) {
        statement.setLong(1, listingId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  @AfterEach
  void stop() {
    if (database != null) database.close();
  }

  @Test
  void refreshIsIdempotentAndNeverExceedsTransitCapacity() {
    FixtureGateway gateway = new FixtureGateway(template, 12);
    SharedSupplyService supply = commerce.supplyService(gateway, "fabric-a");

    var first = supply.refresh(listingId, sellerId, "supply-test-one");
    assertEquals(5, first.loadedAmount());
    assertEquals(5, first.currentStock());
    assertEquals("ACTIVE", first.status());
    assertEquals(first, supply.refresh(listingId, sellerId, "supply-test-one"));
    assertEquals(7, gateway.quantity);

    var second = supply.refresh(listingId, sellerId, null);
    assertEquals(5, second.loadedAmount());
    assertEquals(10, second.currentStock());
    assertEquals(2, gateway.quantity);
    var full = supply.refresh(listingId, sellerId, null);
    assertEquals(0, full.loadedAmount());
    assertEquals(10, full.currentStock());
  }

  @Test
  void createsSupplyListingFromAuthorizedInspectionAndReplaysCreation() {
    FixtureGateway gateway = new FixtureGateway(template, 12);
    SharedSupplyService supply = commerce.supplyService(gateway, "fabric-a");
    UUID owner = database.withConnection(connection -> {
      try (var statement = connection.prepareStatement(
          "SELECT bound_uuid FROM web_users WHERE id=?")) {
        statement.setLong(1, sellerId);
        try (var result = statement.executeQuery()) {
          result.next();
          return UUID.fromString(result.getString(1));
        }
      }
    });
    var request = new SharedSupplyService.SupplyCreateRequest(
        sellerId, owner, CurrencyType.SHOP_COIN, 25,
        new SupplyInventoryGateway.SupplyLocation("minecraft:overworld", 3, 64, 7),
        template.payloadHash(), 5, 10, true, "create-supply-one", "fixture");

    var created = supply.create(request);
    assertEquals("SUPPLY_EMPTY", commerce.listing(listingId).status());
    assertEquals(5, created.listing().quantity());
    assertEquals("ACTIVE", created.listing().status());
    assertEquals(7, gateway.quantity);
    gateway.inspectAvailable = false;
    var replay = supply.create(request);
    assertEquals(created.listing().id(), replay.listing().id());
    assertEquals(created.refresh(), replay.refresh());
    assertEquals(7, gateway.quantity);
  }

  private static final class FixtureGateway implements SupplyInventoryGateway {
    private final ItemEnvelope template;
    private int quantity;
    private long version = 1;
    private boolean inspectAvailable = true;

    private FixtureGateway(ItemEnvelope template, int quantity) {
      this.template = template;
      this.quantity = quantity;
    }

    @Override
    public synchronized CompletionStage<PlatformResult<SupplySnapshot>> snapshot(
        SupplyLocation location) {
      return CompletableFuture.completedFuture(PlatformResult.success(
          new SupplySnapshot(version, quantity == 0 ? List.of() : List.of(withCount(quantity)))));
    }

    @Override
    public synchronized CompletionStage<PlatformResult<SupplySnapshot>> inspect(
        UUID playerId, SupplyLocation location) {
      if (!inspectAvailable) {
        return SupplyInventoryGateway.unavailable("player offline").snapshot(location);
      }
      return snapshot(location);
    }

    @Override
    public synchronized CompletionStage<PlatformResult<SupplyWithdrawal>> compareAndWithdraw(
        SupplyWithdrawalRequest request) {
      if (request.expectedVersion() != version) {
        return CompletableFuture.completedFuture(
            new PlatformResult.Conflict<>(request.operationId(), Long.toString(version)));
      }
      int removed = Math.min(quantity, request.maximumQuantity());
      quantity -= removed;
      version++;
      return CompletableFuture.completedFuture(PlatformResult.success(
          new SupplyWithdrawal(version, removed == 0 ? null : withCount(removed), removed)));
    }

    private ItemEnvelope withCount(int count) {
      return new ItemEnvelope(
          template.schemaVersion(), template.codec(), template.codecVersion(),
          template.compatibilityDomain(), template.registryId(), count,
          template.payloadEncoding(), template.payload(), template.payloadHash(),
          template.summary(), template.createdAt());
    }
  }
}
