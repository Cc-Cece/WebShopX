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
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeInventoryGatewayTest {
  @TempDir Path temporaryDirectory;
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

  @Test
  void offlinePlayerDataIsVersionedBackedUpAndAtomicallyReplaced() throws Exception {
    UUID id = UUID.randomUUID();
    System.setProperty("webshopx.world-dir", temporaryDirectory.toString());
    Files.writeString(temporaryDirectory.resolve("server.properties"), "level-name=fixture-world\n");
    Path playerFile = temporaryDirectory.resolve("fixture-world/playerdata/" + id + ".dat");
    CompoundTag itemTag = new CompoundTag(
        "{id:\"minecraft:diamond\",Count:3,custom:\"offline-data\"}");
    itemTag.putByte("Slot", (byte) 4);
    ListTag inventory = new ListTag();
    inventory.add(itemTag);
    CompoundTag root = new CompoundTag();
    root.put("Inventory", inventory);
    NbtIo.install(playerFile.toFile(), root);

    PlatformIdentity identity = new PlatformIdentity(
        "forge", "forge", "1.20.1", "test", "server", "sha256:test");
    NativePlayerDirectory directory = new NativePlayerDirectory("server");
    LoaderScheduler scheduler = new LoaderScheduler();
    scheduler.bind(new DirectServer());
    NativeItemCodec codec = new NativeItemCodec(identity, scheduler::nativeServer, Clock.systemUTC());
    codec.encode(new ItemStack("minecraft:stone", 1, "bootstrap"), identity);
    NativeInventoryGateway gateway = new NativeInventoryGateway(directory, scheduler, codec, identity);

    InventorySnapshot before = success(gateway.snapshot(id, true).toCompletableFuture().get());
    assertEquals(35, before.freeSlots());
    assertEquals("minecraft:diamond", before.items().get(0).registryId());
    ItemEnvelope stone = success(codec.encode(
        new ItemStack("minecraft:stone", 8, "offline-insert"), identity));
    InventoryMutationResult applied = success(gateway.compareAndApply(new InventoryMutation(
        "offline-op", id, before.version(), List.of(stone), before.items()))
        .toCompletableFuture().get());
    assertEquals(List.of(stone), applied.inserted());
    assertEquals(35, success(gateway.snapshot(id, true).toCompletableFuture().get()).freeSlots());
    assertEquals(true, Files.isRegularFile(playerFile.resolveSibling(
        playerFile.getFileName() + ".webshopx.bak")));
    scheduler.close();
    System.clearProperty("webshopx.world-dir");
  }

  private static final class DirectServer implements Executor {
    @Override public void execute(Runnable action) { action.run(); }
  }

  @SuppressWarnings("unchecked")
  private static <T> T success(PlatformResult<T> result) {
    return ((PlatformResult.Success<T>) assertInstanceOf(PlatformResult.Success.class, result)).value();
  }
}
