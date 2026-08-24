package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.webshopx.SharedCommerceService.ProductInput;
import com.webshopx.SharedCommerceService.ProductKind;
import com.webshopx.SharedCommerceService.PurchaseRequest;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "webshopx.mariadb.integration", matches = "true")
class MariaDbCommerceIntegrationTest {
  private DatabaseManager first;
  private DatabaseManager second;

  @AfterEach void close() {
    if (first != null) first.close();
    if (second != null) second.close();
  }

  @Test void twoNodesShareSchemaWalletOrdersAndIdempotency() {
    DatabaseSettings settings = new DatabaseSettings(
        DbType.MARIADB, System.getProperty("webshopx.mariadb.host", "127.0.0.1"),
        Integer.getInteger("webshopx.mariadb.port", 3306),
        System.getProperty("webshopx.mariadb.schema", "webshopx_it"),
        System.getProperty("webshopx.mariadb.user", "webshopx_it"),
        System.getProperty("webshopx.mariadb.password", "integration-secret"),
        false, false, "", 4, "", "WAL", "NORMAL", 5_000, 3, List.of(10, 50));
    first = new DatabaseManager(null, settings);
    first.start();
    SchemaProvider.forType(DbType.MARIADB).ensureSchema(first, ZoneOffset.UTC);
    second = new DatabaseManager(null, settings);
    second.start();
    SchemaProvider.forType(DbType.MARIADB).ensureSchema(second, ZoneOffset.UTC);

    UUID player = UUID.randomUUID();
    AuthService auth = new AuthService(first, () -> new AuthService.SessionSettings(40, 2));
    long userId = auth.setPasswordFromGame(player, "MariaBuyer", "maria-secret").userId();
    WalletService firstWallet = new WalletService(
        first, WalletService.ExchangePolicy::disabled, null, null);
    WalletService secondWallet = new WalletService(
        second, WalletService.ExchangePolicy::disabled, null, null);
    firstWallet.adjustBalance(userId, CurrencyType.SHOP_COIN, 500, "TEST", "cluster-seed");
    SharedCommerceService firstCommerce = new SharedCommerceService(first, firstWallet);
    SharedCommerceService secondCommerce = new SharedCommerceService(second, secondWallet);
    var product = firstCommerce.createProduct(new ProductInput(
        "CLUSTER_ITEM", "Cluster Item", null, CurrencyType.SHOP_COIN, 60,
        ProductKind.GIVE_ITEM, "", "minecraft:emerald", 4, true));
    var purchase = secondCommerce.purchase(new PurchaseRequest(
        userId, player, product.id(), 2, "cluster-purchase-1", "forge-b"));
    assertEquals(purchase, firstCommerce.purchase(new PurchaseRequest(
        userId, player, product.id(), 2, "cluster-purchase-1", "forge-b")));
    assertEquals(380, firstWallet.getBalance(userId).shopCoin());
    assertEquals(1, firstCommerce.pendingDeliveries(player, "forge-b").size());
    assertEquals(2, secondCommerce.products(false).get(0).stockRemaining());

    UUID rivalOneId = UUID.randomUUID();
    UUID rivalTwoId = UUID.randomUUID();
    long rivalOne = auth.setPasswordFromGame(
        rivalOneId, "MariaRivalOne", "rival-secret").userId();
    long rivalTwo = auth.setPasswordFromGame(
        rivalTwoId, "MariaRivalTwo", "rival-secret").userId();
    firstWallet.adjustBalance(rivalOne, CurrencyType.SHOP_COIN, 100, "TEST", "rival-one");
    firstWallet.adjustBalance(rivalTwo, CurrencyType.SHOP_COIN, 100, "TEST", "rival-two");
    var scarce = firstCommerce.createProduct(new ProductInput(
        "SCARCE_ITEM", "Scarce", null, CurrencyType.SHOP_COIN, 100,
        ProductKind.GIVE_ITEM, "", "minecraft:diamond", 1, true));
    CompletableFuture<Object> one = CompletableFuture.supplyAsync(() -> purchaseOrFailure(
        firstCommerce, new PurchaseRequest(
            rivalOne, rivalOneId, scarce.id(), 1, "race-one", "fabric-a")));
    CompletableFuture<Object> two = CompletableFuture.supplyAsync(() -> purchaseOrFailure(
        secondCommerce, new PurchaseRequest(
            rivalTwo, rivalTwoId, scarce.id(), 1, "race-two", "forge-b")));
    Object firstResult = one.join();
    Object secondResult = two.join();
    assertEquals(1, (firstResult instanceof SharedCommerceService.Purchase ? 1 : 0)
        + (secondResult instanceof SharedCommerceService.Purchase ? 1 : 0));
    assertEquals(100, firstWallet.getBalance(rivalOne).shopCoin()
        + secondWallet.getBalance(rivalTwo).shopCoin());

    var perUserLimited =
        firstCommerce.createProduct(
            new ProductInput(
                "CLUSTER_LIMITED",
                "Cluster Limited",
                null,
                CurrencyType.SHOP_COIN,
                10,
                ProductKind.COMMAND,
                "say limited",
                null,
                null,
                1,
                true));
    CompletableFuture<Object> limitOne =
        CompletableFuture.supplyAsync(
            () ->
                purchaseOrFailure(
                    firstCommerce,
                    new PurchaseRequest(
                        userId, player, perUserLimited.id(), 1, "limit-node-one", "fabric-a")));
    CompletableFuture<Object> limitTwo =
        CompletableFuture.supplyAsync(
            () ->
                purchaseOrFailure(
                    secondCommerce,
                    new PurchaseRequest(
                        userId, player, perUserLimited.id(), 1, "limit-node-two", "forge-b")));
    Object limitFirstResult = limitOne.join();
    Object limitSecondResult = limitTwo.join();
    assertEquals(
        1,
        (limitFirstResult instanceof SharedCommerceService.Purchase ? 1 : 0)
            + (limitSecondResult instanceof SharedCommerceService.Purchase ? 1 : 0));
    assertEquals(370, firstWallet.getBalance(userId).shopCoin());
  }

  private static Object purchaseOrFailure(
      SharedCommerceService commerce, PurchaseRequest request) {
    try {
      return commerce.purchase(request);
    } catch (ServiceException conflict) {
      return conflict;
    }
  }
}
