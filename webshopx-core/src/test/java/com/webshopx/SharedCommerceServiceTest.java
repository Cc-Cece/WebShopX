package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.webshopx.SharedCommerceService.ListingRequest;
import com.webshopx.SharedCommerceService.MarketBuyRequest;
import com.webshopx.SharedCommerceService.PaymentNotification;
import com.webshopx.SharedCommerceService.PaymentSession;
import com.webshopx.SharedCommerceService.ProductInput;
import com.webshopx.SharedCommerceService.ProductKind;
import com.webshopx.SharedCommerceService.PurchaseRequest;
import com.webshopx.SharedCommerceService.RechargeRequest;
import com.webshopx.core.ItemEnvelopeService;
import com.webshopx.core.ItemEnvelopeBinaryCodec;
import com.google.gson.JsonParser;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedCommerceServiceTest {
  @TempDir Path temporaryDirectory;
  private DatabaseManager database;
  private AuthService auth;
  private WalletService wallets;
  private SharedCommerceService commerce;

  @BeforeEach void start() {
    database = new DatabaseManager(null, new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 1,
        temporaryDirectory.resolve("commerce.db").toString(), "WAL", "NORMAL",
        5_000, 3, List.of(10, 25, 50)));
    database.start();
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    auth = new AuthService(database, () -> new AuthService.SessionSettings(40, 2));
    wallets = new WalletService(database, WalletService.ExchangePolicy::disabled, null, null);
    commerce = new SharedCommerceService(database, wallets);
  }

  @AfterEach void stop() { if (database != null) database.close(); }

  @Test void officialShopMarketAndRechargeAreAtomicAndIdempotent() {
    UUID buyerId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();
    long buyer = auth.setPasswordFromGame(buyerId, "CommerceBuyer", "buyer-secret").userId();
    long seller = auth.setPasswordFromGame(sellerId, "CommerceSeller", "seller-secret").userId();
    wallets.adjustBalance(buyer, CurrencyType.SHOP_COIN, 1_000, "TEST", "seed-buyer");

    var product = commerce.createProduct(new ProductInput(
        "STONE_16", "Stone", null, CurrencyType.SHOP_COIN, 50,
        ProductKind.GIVE_ITEM, "", "minecraft:stone", 10, true));
    var purchase = commerce.purchase(new PurchaseRequest(
        buyer, buyerId, product.id(), 2, "purchase-1", "fabric-a"));
    assertEquals(purchase, commerce.purchase(new PurchaseRequest(
        buyer, buyerId, product.id(), 2, "purchase-1", "fabric-a")));
    assertEquals(900, wallets.getBalance(buyer).shopCoin());
    var delivery = commerce.pendingDeliveries(buyerId, "fabric-a").get(0);
    assertTrue(commerce.claimDelivery(delivery.id(), "fabric-a"));
    assertFalse(commerce.claimDelivery(delivery.id(), "fabric-a"));
    commerce.markDeliveryRetry(delivery.id(), "inventory_full");
    assertEquals(1, commerce.pendingDeliveries(buyerId, "fabric-a").size());
    assertTrue(commerce.claimDelivery(delivery.id(), "fabric-a"));
    commerce.markDelivered(delivery.id(), 2);
    assertEquals(0, commerce.pendingDeliveries(buyerId, "fabric-a").size());

    ItemEnvelope item = envelope("minecraft:diamond", 3, "modded-payload");
    var listing = commerce.createListing(new ListingRequest(
        seller, sellerId, CurrencyType.SHOP_COIN, 75, 2, item, "fixture"));
    var trade = commerce.buyListing(new MarketBuyRequest(
        buyer, buyerId, listing.id(), 2, "trade-1", "fabric-a"));
    assertEquals(trade, commerce.buyListing(new MarketBuyRequest(
        buyer, buyerId, listing.id(), 2, "trade-1", "fabric-a")));
    assertEquals(750, wallets.getBalance(buyer).shopCoin());
    assertEquals(150, wallets.getBalance(seller).shopCoin());

    commerce.registerPaymentProvider(new SharedCommerceService.PaymentProvider() {
      public String id() { return "fixture-pay"; }
      public PaymentSession create(String orderId, long amount, String currency, String description) {
        return new PaymentSession("provider-1", "https://pay.invalid/1", null,
            Instant.parse("2026-08-24T00:00:00Z"));
      }
    });
    var recharge = commerce.createRecharge(new RechargeRequest(
        buyer, buyerId, 200, "CNY", 200, "fixture-pay", "recharge-1", "coins"));
    var credited = commerce.applyPayment(new PaymentNotification(
        "fixture-pay", "provider-1", recharge.orderId(), 200, "CNY", true,
        Instant.parse("2026-08-23T00:00:00Z")));
    assertEquals("CREDITED", credited.status());
    assertEquals(950, wallets.getBalance(buyer).shopCoin());
    commerce.applyPayment(new PaymentNotification(
        "fixture-pay", "provider-1", recharge.orderId(), 200, "CNY", true,
        Instant.parse("2026-08-23T00:00:00Z")));
    assertEquals(950, wallets.getBalance(buyer).shopCoin());
  }

  @Test void failedPurchaseRollsBackWalletAndStock() {
    UUID buyerId = UUID.randomUUID();
    long buyer = auth.setPasswordFromGame(buyerId, "PoorBuyer", "buyer-secret").userId();
    var product = commerce.createProduct(new ProductInput(
        "EXPENSIVE", "Expensive", null, CurrencyType.SHOP_COIN, 500,
        ProductKind.COMMAND, "say paid", null, 1, true));
    ServiceException failure = assertThrows(ServiceException.class, () -> commerce.purchase(
        new PurchaseRequest(buyer, buyerId, product.id(), 1, "purchase-fail", "server")));
    assertEquals("insufficient_funds", failure.code());
    assertEquals(0, wallets.getBalance(buyer).shopCoin());
    assertEquals(1, commerce.products(false).get(0).stockRemaining());
  }

  @Test void snapshotProductsPreserveNativePayloadAcrossVersionRollbackAndDelivery() {
    UUID buyerId = UUID.randomUUID();
    long buyer = auth.setPasswordFromGame(buyerId, "SnapshotBuyer", "buyer-secret").userId();
    wallets.adjustBalance(buyer, CurrencyType.SHOP_COIN, 1_000, "TEST", "snapshot-seed");
    ItemEnvelope first = envelope("fixture:nested_box", 8, "nested-nbt-components-v1");
    var product = commerce.createSnapshotProduct(
        new ProductInput(
            "NATIVE_BOX",
            "Native Box",
            null,
            CurrencyType.SHOP_COIN,
            25,
            ProductKind.SNAPSHOT_ITEM,
            "",
            first.registryId(),
            10,
            true),
        first,
        buyer);
    assertEquals(1, commerce.snapshotVersions(product.id()).get(0).version());

    var purchase = commerce.purchase(new PurchaseRequest(
        buyer, buyerId, product.id(), 3, "snapshot-purchase-1", "fabric-a"));
    var delivery = commerce.pendingDeliveries(buyerId, "fabric-a").get(0);
    ItemEnvelope deliveredTemplate = decodeDeliveryEnvelope(delivery.payloadJson());
    assertEquals(1, deliveredTemplate.count());
    assertEquals(first.payloadHash(), deliveredTemplate.payloadHash());
    assertEquals("nested-nbt-components-v1", new String(
        deliveredTemplate.payload(), java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(75, purchase.total());

    ItemEnvelope second = envelope("fixture:nested_box", 1, "nested-nbt-components-v2");
    commerce.replaceSnapshot(product.id(), second, buyer);
    var versions = commerce.snapshotVersions(product.id());
    assertEquals(2, versions.size());
    assertTrue(versions.get(0).active());
    assertEquals(2, versions.get(0).version());
    commerce.rollbackSnapshot(product.id(), 1, buyer);
    var rolledBack = commerce.snapshotVersions(product.id());
    assertEquals(3, rolledBack.size());
    assertEquals(3, rolledBack.get(0).version());
    assertTrue(rolledBack.get(0).active());
    assertEquals(rolledBack.get(2).snapshotId(), rolledBack.get(0).snapshotId());

    commerce.purchase(new PurchaseRequest(
        buyer, buyerId, product.id(), 1, "snapshot-purchase-2", "fabric-b"));
    ItemEnvelope afterRollback = decodeDeliveryEnvelope(
        commerce.pendingDeliveries(buyerId, "fabric-b").get(0).payloadJson());
    assertEquals(first.payloadHash(), afterRollback.payloadHash());
  }

  private static ItemEnvelope decodeDeliveryEnvelope(String payloadJson) {
    String encoded = JsonParser.parseString(payloadJson).getAsJsonObject()
        .get("envelopeBase64").getAsString();
    return new ItemEnvelopeBinaryCodec().decode(Base64.getDecoder().decode(encoded));
  }

  private static ItemEnvelope envelope(String registryId, int count, String value) {
    ItemEnvelopeService service = new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture"));
    return service.create("fixture", 1,
        new CompatibilityDomain("fabric", "fabric", "1.20.1", 1, "sha256:mods"),
        registryId, count, value.getBytes(java.nio.charset.StandardCharsets.UTF_8), Map.of());
  }
}
