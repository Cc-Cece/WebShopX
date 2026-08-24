package com.webshopx.core;

import com.webshopx.AdminAuditService;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.CurrencyType;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.NotificationService;
import com.webshopx.RedeemCodeService;
import com.webshopx.RefundPolicyService;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceCheckoutAdapter;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedContentService;
import com.webshopx.SharedMarketEscrowService;
import com.webshopx.SharedPromotionService;
import com.webshopx.WalletService;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.testkit.InMemoryInventoryGateway;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

/** Process fixture used by the CI Chromium acceptance job. */
public final class BrowserAcceptanceFixtureMain {
  private BrowserAcceptanceFixtureMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    Path directory = Path.of(args[1]).toAbsolutePath();
    String host = args.length > 2 ? args[2] : "127.0.0.1";
    Files.createDirectories(directory);
    Path stopFile = directory.resolve("stop");
    Files.deleteIfExists(stopFile);
    DatabaseManager database =
        new DatabaseManager(
            null,
            new DatabaseSettings(
                DbType.SQLITE,
                "",
                0,
                "",
                "",
                "",
                false,
                false,
                "",
                2,
                directory.resolve("browser.db").toString(),
                "WAL",
                "NORMAL",
                5_000,
                3,
                List.of(10, 25, 50)));
    SharedHttpApi api = null;
    try {
      database.start();
      SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
      AuthService auth = new AuthService(database, () -> new AuthService.SessionSettings(40, 2));
      WalletService wallets =
          new WalletService(database, WalletService.ExchangePolicy::disabled, null, null);
      SharedCommerceService commerce = new SharedCommerceService(database, wallets);
      AdminService admin = new AdminService(database, auth, wallets);
      UUID browserUser = UUID.nameUUIDFromBytes(
          "webshopx-browser-admin".getBytes(StandardCharsets.UTF_8));
      long browserUserId = auth
          .setPasswordFromGame(browserUser, "BrowserAdmin", "browser-secret")
          .userId();
      UUID browserViewer = UUID.nameUUIDFromBytes(
          "webshopx-browser-viewer".getBytes(StandardCharsets.UTF_8));
      auth.setPasswordFromGame(browserViewer, "BrowserViewer", "viewer-secret");
      wallets.adjustBalance(
          browserUserId, CurrencyType.SHOP_COIN, 5_000, "BROWSER_FIXTURE", "initial-wallet");
      commerce.createProduct(
          new SharedCommerceService.ProductInput(
              "BROWSER_STONE",
              "Browser Acceptance Stone",
              "Visible product used by the real browser journey",
              CurrencyType.SHOP_COIN,
              50,
              SharedCommerceService.ProductKind.GIVE_ITEM,
              "",
              "minecraft:stone",
              4,
              true));
      admin.ensureBootstrapAdmin(
          new AdminService.AdminBootstrapSettings(
              true, "BrowserAdmin", "browser-secret", "SUPER_ADMIN"));
      EnumMap<CapabilitySnapshot.Capability, CapabilitySnapshot.CapabilityState> states =
          new EnumMap<>(CapabilitySnapshot.Capability.class);
      states.put(
          CapabilitySnapshot.Capability.HTTP_API,
          CapabilitySnapshot.CapabilityState.available("browser acceptance fixture"));
      api =
          new SharedHttpApi(
              host,
              port,
              "",
              auth,
              wallets,
              commerce,
              new SharedMarketEscrowService(database, commerce, new InMemoryInventoryGateway(36)),
              new SharedContentService(database),
              new SharedPromotionService(
                  database, wallets, new SharedCommerceCheckoutAdapter(commerce, "browser-node")),
              new RedeemCodeService(database, wallets),
              new NotificationService(database),
              admin,
              new AdminAuditService(database),
              new RefundPolicyService(database),
              new com.webshopx.SharedRuntimeConfigService(database),
              new PlatformIdentity(
                  "fixture", "fixture", "ci", "ci", "browser-node", "sha256:browser-fixture"),
              new CapabilitySnapshot(Instant.now(), states));
      api.start();
      System.out.println("BROWSER_FIXTURE_READY http://" + host + ":" + api.port());
      System.out.flush();
      while (!Files.exists(stopFile)) {
        Thread.sleep(100L);
      }
    } finally {
      if (api != null) api.close();
      database.close();
    }
  }
}
