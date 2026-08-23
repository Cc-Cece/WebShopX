package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.mojang.authlib.GameProfile;
import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

class NativeInventoryGatewayTest {
  @Test
  void nativeNbtRoundTripAndCompareApplyAreLosslessVersionedAndIdempotent() throws Exception {
    UUID id = UUID.randomUUID();
    PlatformIdentity identity = new PlatformIdentity(
        "fabric", "fabric", "1.20.1", "test", "server", "sha256:test");
    NativePlayerDirectory directory = new NativePlayerDirectory("server");
    ServerPlayer player = new ServerPlayer(new GameProfile(id, "InventoryUser"), 2);
    player.getInventory().setItem(0, new ItemStack("minecraft:diamond_sword", 1, "mod-data"));
    directory.joined(player).orElseThrow();
    LoaderScheduler scheduler = new LoaderScheduler();
    scheduler.bind((Executor) Runnable::run);
    NativeItemCodec codec = new NativeItemCodec(identity, scheduler::nativeServer, Clock.systemUTC());
    NativeInventoryGateway gateway = new NativeInventoryGateway(directory, scheduler, codec, identity);

    InventorySnapshot before = success(gateway.snapshot(id, false).toCompletableFuture().get());
    assertEquals(1, before.items().size());
    ItemEnvelope sword = before.items().get(0);
    assertEquals("minecraft:diamond_sword", sword.registryId());
    assertEquals("mod-data", new String(sword.payload()).contains("mod-data") ? "mod-data" : "missing");
    ItemEnvelope stone = success(codec.encode(
        new ItemStack("minecraft:stone", 32, "nested"), identity));

    InventoryMutation mutation = new InventoryMutation(
        "native-op", id, before.version(), List.of(stone, stone), List.of(sword));
    InventoryMutationResult applied = success(
        gateway.compareAndApply(mutation).toCompletableFuture().get());
    assertEquals(List.of(stone, stone), applied.inserted());
    assertEquals(List.of(sword), applied.removed());
    assertEquals(List.of(), applied.remainder());
    assertEquals(applied, success(gateway.compareAndApply(mutation).toCompletableFuture().get()));
    assertInstanceOf(PlatformResult.Conflict.class,
        gateway.compareAndApply(new InventoryMutation(
            "stale", id, before.version(), List.of(), List.of())).toCompletableFuture().get());
    scheduler.close();
  }

  @SuppressWarnings("unchecked")
  private static <T> T success(PlatformResult<T> result) {
    return ((PlatformResult.Success<T>) assertInstanceOf(PlatformResult.Success.class, result)).value();
  }
}
