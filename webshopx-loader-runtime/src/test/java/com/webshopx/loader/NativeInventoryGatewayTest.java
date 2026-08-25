package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.mojang.authlib.GameProfile;
import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.InventoryTypes.InventoryRemoval;
import com.webshopx.platform.InventoryTypes.InventorySnapshot;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
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
    PlatformIdentity identity =
        new PlatformIdentity("fabric", "fabric", "1.20.1", "test", "server", "sha256:test");
    NativePlayerDirectory directory = new NativePlayerDirectory("server");
    ServerPlayer player = new ServerPlayer(new GameProfile(id, "InventoryUser"), 41);
    player.getInventory().setItem(0, new ItemStack("minecraft:diamond_sword", 3, "mod-data"));
    directory.joined(player).orElseThrow();
    LoaderScheduler scheduler = new LoaderScheduler();
    scheduler.bind((Executor) Runnable::run);
    NativeItemCodec codec =
        new NativeItemCodec(identity, scheduler::nativeServer, Clock.systemUTC());
    NativeInventoryGateway gateway =
        new NativeInventoryGateway(directory, scheduler, codec, identity);

    InventorySnapshot before = success(gateway.snapshot(id, false).toCompletableFuture().get());
    assertEquals(1, before.items().size());
    assertEquals(35, before.freeSlots());
    ItemEnvelope sword = before.items().get(0);
    assertEquals("minecraft:diamond_sword", sword.registryId());
    assertEquals(
        "mod-data", new String(sword.payload()).contains("mod-data") ? "mod-data" : "missing");
    ItemEnvelope stone =
        success(codec.encode(new ItemStack("minecraft:stone", 32, "nested"), identity));
    ItemEnvelope resizedStone =
        new ItemEnvelope(
            stone.schemaVersion(),
            stone.codec(),
            stone.codecVersion(),
            stone.compatibilityDomain(),
            stone.registryId(),
            7,
            stone.payloadEncoding(),
            stone.payload(),
            stone.payloadHash(),
            stone.summary(),
            stone.createdAt());

    InventoryMutation mutation =
        new InventoryMutation(
            "native-op",
            id,
            before.version(),
            List.of(resizedStone),
            List.of(new InventoryRemoval(sword, 2)));
    InventoryMutationResult applied =
        success(gateway.compareAndApply(mutation).toCompletableFuture().get());
    assertEquals(List.of(resizedStone), applied.inserted());
    assertEquals(2, applied.removed().get(0).count());
    assertEquals(List.of(), applied.remainder());
    assertEquals(applied, success(gateway.compareAndApply(mutation).toCompletableFuture().get()));
    assertEquals(
        1,
        success(gateway.snapshot(id, false).toCompletableFuture().get()).items().stream()
            .filter(item -> item.registryId().equals("minecraft:diamond_sword"))
            .findFirst()
            .orElseThrow()
            .count());
    assertEquals(
        7,
        success(gateway.snapshot(id, false).toCompletableFuture().get()).items().stream()
            .filter(item -> item.registryId().equals("minecraft:stone"))
            .findFirst()
            .orElseThrow()
            .count());
    assertInstanceOf(
        PlatformResult.Conflict.class,
        gateway
            .compareAndApply(
                new InventoryMutation("stale", id, before.version(), List.of(), List.of()))
            .toCompletableFuture()
            .get());
    scheduler.close();
  }

  @Test
  void offlinePlayerDataIsVersionedBackedUpAndAtomicallyReplaced() throws Exception {
    UUID id = UUID.randomUUID();
    System.setProperty("webshopx.world-dir", temporaryDirectory.toString());
    Files.writeString(
        temporaryDirectory.resolve("server.properties"), "level-name=fixture-world\n");
    Path playerFile = temporaryDirectory.resolve("fixture-world/playerdata/" + id + ".dat");
    CompoundTag itemTag =
        new CompoundTag("{id:\"minecraft:diamond\",Count:3,custom:\"offline-data\"}");
    itemTag.putByte("Slot", (byte) 4);
    ListTag inventory = new ListTag();
    inventory.add(itemTag);
    CompoundTag armorTag =
        new CompoundTag("{id:\"minecraft:diamond_chestplate\",Count:1,custom:\"armor-data\"}");
    armorTag.putByte("Slot", (byte) 100);
    inventory.add(armorTag);
    CompoundTag root = new CompoundTag();
    root.put("Inventory", inventory);
    NbtIo.install(playerFile.toFile(), root);

    PlatformIdentity identity =
        new PlatformIdentity("forge", "forge", "1.20.1", "test", "server", "sha256:test");
    NativePlayerDirectory directory = new NativePlayerDirectory("server");
    LoaderScheduler scheduler = new LoaderScheduler();
    scheduler.bind(new DirectServer());
    NativeItemCodec codec =
        new NativeItemCodec(identity, scheduler::nativeServer, Clock.systemUTC());
    codec.encode(new ItemStack("minecraft:stone", 1, "bootstrap"), identity);
    NativeInventoryGateway gateway =
        new NativeInventoryGateway(directory, scheduler, codec, identity);

    InventorySnapshot before = success(gateway.snapshot(id, true).toCompletableFuture().get());
    assertEquals(35, before.freeSlots());
    assertEquals(1, before.items().size());
    assertEquals("minecraft:diamond", before.items().get(0).registryId());
    ItemEnvelope stone =
        success(codec.encode(new ItemStack("minecraft:stone", 8, "offline-insert"), identity));
    InventoryMutationResult applied =
        success(
            gateway
                .compareAndApply(
                    new InventoryMutation(
                        "offline-op",
                        id,
                        before.version(),
                        List.of(stone),
                        List.of(new InventoryRemoval(before.items().get(0), 2))))
                .toCompletableFuture()
                .get());
    assertEquals(List.of(stone), applied.inserted());
    assertEquals(
        applied,
        success(
            gateway
                .compareAndApply(
                    new InventoryMutation(
                        "offline-op",
                        id,
                        before.version(),
                        List.of(stone),
                        List.of(new InventoryRemoval(before.items().get(0), 2))))
                .toCompletableFuture()
                .get()));
    InventorySnapshot after = success(gateway.snapshot(id, true).toCompletableFuture().get());
    assertEquals(34, after.freeSlots());
    assertEquals(
        1,
        after.items().stream()
            .filter(item -> item.registryId().equals("minecraft:diamond"))
            .findFirst()
            .orElseThrow()
            .count());
    assertEquals(
        true,
        Files.isRegularFile(playerFile.resolveSibling(playerFile.getFileName() + ".webshopx.bak")));
    assertEquals(
        true,
        root.getList("Inventory", 10).stream()
            .map(CompoundTag.class::cast)
            .anyMatch(tag -> tag.getByte("Slot") == (byte) 100 && tag.toString().contains("armor-data")));
    scheduler.close();
    System.clearProperty("webshopx.world-dir");
  }

  private static final class DirectServer implements Executor {
    @Override
    public void execute(Runnable action) {
      action.run();
    }
  }

  @SuppressWarnings("unchecked")
  private static <T> T success(PlatformResult<T> result) {
    return ((PlatformResult.Success<T>) assertInstanceOf(PlatformResult.Success.class, result))
        .value();
  }
}
