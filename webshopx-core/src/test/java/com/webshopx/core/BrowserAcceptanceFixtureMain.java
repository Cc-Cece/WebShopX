package com.webshopx.core;

import com.webshopx.AdminAuditService;
import com.webshopx.AdminService;
import com.webshopx.AuthService;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.NotificationService;
import com.webshopx.RedeemCodeService;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceCheckoutAdapter;
import com.webshopx.SharedCommerceService;
import com.webshopx.SharedContentService;
import com.webshopx.SharedPromotionService;
import com.webshopx.WalletService;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.PlatformIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;

/** Process fixture used by the CI Chromium acceptance job. */
public final class BrowserAcceptanceFixtureMain {
  private BrowserAcceptanceFixtureMain() {}

  public static void main(String[] args) throws Exception {
    int port = Integer.parseInt(args[0]);
    Path directory = Path.of(args[1]).toAbsolutePath();
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
      EnumMap<CapabilitySnapshot.Capability, CapabilitySnapshot.CapabilityState> states =
          new EnumMap<>(CapabilitySnapshot.Capability.class);
      states.put(
          CapabilitySnapshot.Capability.HTTP_API,
          CapabilitySnapshot.CapabilityState.available("browser acceptance fixture"));
      api =
          new SharedHttpApi(
              "127.0.0.1",
              port,
              "",
              auth,
              wallets,
              commerce,
              new SharedContentService(database),
              new SharedPromotionService(
                  database, wallets, new SharedCommerceCheckoutAdapter(commerce, "browser-node")),
              new RedeemCodeService(database, wallets),
              new NotificationService(database),
              admin,
              new AdminAuditService(database),
              new PlatformIdentity(
                  "fixture", "fixture", "ci", "ci", "browser-node", "sha256:browser-fixture"),
              new CapabilitySnapshot(Instant.now(), states));
      api.start();
      System.out.println("BROWSER_FIXTURE_READY http://127.0.0.1:" + api.port());
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
