package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.core.WebShopXCoreRuntime;
import com.google.gson.JsonParser;
import com.webshopx.CurrencyType;
import com.webshopx.AdminService;
import com.webshopx.platform.CapabilitySnapshot.Capability;
import com.webshopx.platform.CapabilitySnapshot.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;

class LoaderRuntimeTest {
  @TempDir Path temporaryDirectory;
  @AfterEach void stop() { LoaderRuntime.stop(); }

  @Test void startsOnceAndFailsClosedForUnimplementedCapabilities() throws Exception {
    System.setProperty("webshopx.data-dir", temporaryDirectory.toString());
    WebShopXCoreRuntime first = LoaderRuntime.start("fabric", "1.20.1", "0.19.3");
    WebShopXCoreRuntime second = LoaderRuntime.start("fabric", "1.20.1", "0.19.3");
    assertEquals(first, second);
    assertEquals(WebShopXCoreRuntime.State.READY, first.state());
    assertEquals(Status.UNSUPPORTED, first.platform().capabilities().state(Capability.ECONOMY).status());
    assertEquals(Status.AVAILABLE,
        first.platform().capabilities().state(Capability.PERMISSION).status());
    assertEquals(Status.UNSUPPORTED,
        first.platform().capabilities().state(Capability.CLIENT_ENHANCEMENT).status());
    assertEquals(Status.AVAILABLE,
        first.platform().capabilities().state(Capability.MOD_ITEM_CODEC).status());
    assertTrue(Files.readString(temporaryDirectory.resolve("health.json")).contains("\"state\": \"READY\""));
    var auth = LoaderRuntime.authentication().orElseThrow();
    var created = auth.setPasswordFromGame(UUID.randomUUID(), "Loader_User", "loader-secret");
    assertEquals("Loader_User", auth.login("Loader_User", "loader-secret").user().username());
    var wallet = LoaderRuntime.wallet().orElseThrow();
    assertEquals(25L, wallet.adjustBalance(
        created.userId(), CurrencyType.SHOP_COIN, 25L, "TEST", "test-credit").shopCoin());
    LoaderRuntime.runtimeConfig().orElseThrow().update(
        "exchange",
        JsonParser.parseString(
            "{\"shopToGame\":{\"enabled\":true,\"ratio\":2},"
                + "\"gameToShop\":{\"enabled\":false,\"ratio\":0}}")
            .getAsJsonObject());
    var exchanged = wallet.exchange(
        created.userId(), CurrencyType.SHOP_COIN, CurrencyType.GAME_COIN, 5L, "test-exchange");
    assertEquals(20L, exchanged.shopCoin());
    assertEquals(10L, exchanged.gameCoin());
    var administration = LoaderRuntime.administration().orElseThrow();
    administration.ensureBootstrapAdmin(new AdminService.AdminBootstrapSettings(
        true, "Loader_Admin", "loader-admin-secret", "SUPER_ADMIN"));
    assertTrue(administration.login("Loader_Admin", "loader-admin-secret").admin().isSuperAdmin());
    assertEquals(20L, wallet.adjustBalance(
        created.userId(), CurrencyType.SHOP_COIN, 25L, "TEST", "test-credit").shopCoin());
    LoaderRuntime.stop();
    assertTrue(Files.readString(temporaryDirectory.resolve("health.json")).contains("\"state\": \"STOPPED\""));
    first.close();
    assertEquals(WebShopXCoreRuntime.State.STOPPED, first.state());
    assertFalse(LoaderRuntime.active().isPresent());
    assertTrue(LoaderRuntime.authentication().isEmpty());
  }
}
