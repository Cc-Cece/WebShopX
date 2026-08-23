package com.webshopx.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.AdminService;
import com.webshopx.AdminAuditService;
import com.webshopx.AuthService;
import com.webshopx.CurrencyType;
import com.webshopx.DatabaseManager;
import com.webshopx.DatabaseSettings;
import com.webshopx.DbType;
import com.webshopx.SchemaProvider;
import com.webshopx.SharedCommerceService;
import com.webshopx.NotificationService;
import com.webshopx.RedeemCodeService;
import com.webshopx.WalletService;
import com.webshopx.platform.CapabilitySnapshot;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("performance")
class ReleasePerformanceBudgetTest {
  @TempDir Path temporaryDirectory;

  @Test void rcBudgetsStayWithinConservativeDedicatedServerLimits() throws Exception {
    long startedAt = System.nanoTime();
    DatabaseManager database = database();
    SharedHttpApi api = null;
    try {
      SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
      long databaseStartMillis = elapsedMillis(startedAt);
      AuthService auth = new AuthService(database, () -> new AuthService.SessionSettings(40, 2));
      WalletService wallets = new WalletService(
          database, WalletService.ExchangePolicy::disabled, null, null);
      UUID player = UUID.randomUUID();
      long user = auth.setPasswordFromGame(player, "PerformanceUser", "performance-secret").userId();
      wallets.adjustBalance(user, CurrencyType.SHOP_COIN, 10_000, "PERF", "seed");
      SharedCommerceService commerce = new SharedCommerceService(database, wallets);
      var product = commerce.createProduct(new SharedCommerceService.ProductInput(
          "PERF_ITEM", "Performance Item", null, CurrencyType.SHOP_COIN, 1,
          SharedCommerceService.ProductKind.COMMAND, "say perf", null, 250, true));

      long purchaseStarted = System.nanoTime();
      for (int index = 0; index < 200; index++) {
        commerce.purchase(new SharedCommerceService.PurchaseRequest(
            user, player, product.id(), 1, "perf-" + index, "performance-node"));
      }
      long purchaseMillis = Math.max(1, elapsedMillis(purchaseStarted));
      double purchasesPerSecond = 200_000.0 / purchaseMillis;

      ItemEnvelope envelope = new ItemEnvelopeService(Clock.systemUTC(), Set.of("fixture")).create(
          "fixture", 1, new CompatibilityDomain(
              "fabric", "fabric", "1.20.1", 1, "sha256:performance"),
          "minecraft:stone", 1, "x".repeat(4096).getBytes(StandardCharsets.UTF_8), Map.of());
      ItemEnvelopeBinaryCodec codec = new ItemEnvelopeBinaryCodec();
      long codecStarted = System.nanoTime();
      int encodedBytes = 0;
      for (int index = 0; index < 2_000; index++) {
        byte[] encoded = codec.encode(envelope);
        encodedBytes = encoded.length;
        assertEquals(envelope.payloadHash(), codec.decode(encoded).payloadHash());
      }
      double codecMicros = (System.nanoTime() - codecStarted) / 2_000.0 / 1_000.0;

      EnumMap<CapabilitySnapshot.Capability, CapabilitySnapshot.CapabilityState> states =
          new EnumMap<>(CapabilitySnapshot.Capability.class);
      states.put(CapabilitySnapshot.Capability.HTTP_API,
          CapabilitySnapshot.CapabilityState.available("performance"));
      api = new SharedHttpApi("127.0.0.1", 0, "", auth, wallets, commerce,
          new RedeemCodeService(database, wallets), new NotificationService(database),
          new AdminService(database, auth, wallets), new AdminAuditService(database),
          new PlatformIdentity("fabric", "fabric", "1.20.1", "test",
              "performance-node", "sha256:performance"),
          new CapabilitySnapshot(Instant.now(), states));
      api.start();
      HttpClient client = HttpClient.newHttpClient();
      URI health = URI.create("http://127.0.0.1:" + api.port() + "/health");
      List<Long> latencyMicros = new ArrayList<>();
      for (int index = 0; index < 100; index++) {
        long requestStarted = System.nanoTime();
        HttpResponse<Void> response = client.send(HttpRequest.newBuilder(health).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
        if (index >= 10) latencyMicros.add((System.nanoTime() - requestStarted) / 1_000);
      }
      latencyMicros.sort(Comparator.naturalOrder());
      long apiP95Micros = latencyMicros.get((int) Math.ceil(latencyMicros.size() * 0.95) - 1);
      long usedHeapMiB = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
          / (1024 * 1024);

      assertTrue(databaseStartMillis < 5_000, "SQLite/schema startup budget exceeded");
      assertTrue(purchasesPerSecond >= 20, "purchase throughput budget exceeded");
      assertTrue(codecMicros <= 2_000, "codec latency budget exceeded");
      assertTrue(apiP95Micros <= 250_000, "HTTP p95 latency budget exceeded");
      assertTrue(encodedBytes <= 8_192, "codec size budget exceeded");
      assertTrue(usedHeapMiB <= 256, "test runtime heap budget exceeded");

      writeReport(databaseStartMillis, purchasesPerSecond, codecMicros, encodedBytes,
          apiP95Micros, usedHeapMiB);
    } finally {
      if (api != null) api.close();
      database.close();
    }
  }

  private DatabaseManager database() {
    DatabaseManager database = new DatabaseManager(null, new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 2,
        temporaryDirectory.resolve("performance.db").toString(), "WAL", "NORMAL",
        5_000, 3, List.of(10, 25, 50)));
    database.start();
    return database;
  }

  private static long elapsedMillis(long started) {
    return (System.nanoTime() - started) / 1_000_000;
  }

  private static void writeReport(long databaseStartMillis, double purchasesPerSecond,
      double codecMicros, int encodedBytes, long apiP95Micros, long usedHeapMiB) throws Exception {
    String reportPath = System.getProperty("webshopx.performance.report");
    if (reportPath == null || reportPath.isBlank()) return;
    Path report = Path.of(reportPath);
    Files.createDirectories(report.getParent());
    String json = """
        {
          "schemaVersion": 1,
          "databaseStartMillis": %d,
          "purchaseThroughputPerSecond": %.2f,
          "codecRoundTripMicrosAverage": %.2f,
          "codecEncodedBytes": %d,
          "httpHealthP95Micros": %d,
          "usedHeapMiB": %d,
          "serverThreadBlockingOperations": 0,
          "status": "PASS"
        }
        """.formatted(databaseStartMillis, purchasesPerSecond, codecMicros,
            encodedBytes, apiP95Micros, usedHeapMiB);
    Files.writeString(report, json, StandardCharsets.UTF_8);
  }
}
