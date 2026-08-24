package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformResult;
import com.webshopx.platform.SupplyInventoryGateway.SupplyLocation;
import com.webshopx.platform.SupplyInventoryGateway.SupplySnapshot;
import com.webshopx.platform.SupplyInventoryGateway.SupplyWithdrawal;
import com.webshopx.platform.SupplyInventoryGateway.SupplyWithdrawalRequest;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

class NativeSupplyInventoryGatewayTest {
  @Test
  void snapshotsWithdrawsAndReplaysAgainstNativeContainer() throws Exception {
    FixtureContainer container = new FixtureContainer(
        new ItemStack("minecraft:diamond", 3, "same-data"),
        new ItemStack("minecraft:diamond", 5, "same-data"),
        new ItemStack("minecraft:diamond", 4, "different-data"));
    FixtureServer server = new FixtureServer(container);
    LoaderScheduler scheduler = new LoaderScheduler();
    scheduler.bind(server);
    PlatformIdentity identity = new PlatformIdentity(
        "fabric", "fabric", "1.20.1", "test", "node-a", "sha256:test");
    NativeItemCodec codec = new NativeItemCodec(identity, scheduler::nativeServer, Clock.systemUTC());
    NativeSupplyInventoryGateway gateway = new NativeSupplyInventoryGateway(scheduler, codec, identity);
    SupplyLocation location = new SupplyLocation("minecraft:overworld", 4, 65, 8);

    SupplySnapshot before = success(gateway.snapshot(location).toCompletableFuture().get());
    assertEquals(3, before.items().size());
    ItemEnvelope template = before.items().get(0);
    SupplyWithdrawalRequest request = new SupplyWithdrawalRequest(
        "native-supply-1", location, before.version(), template, 6);
    SupplyWithdrawal applied = success(gateway.compareAndWithdraw(request).toCompletableFuture().get());
    assertEquals(6, applied.removedQuantity());
    assertEquals(6, applied.removed().count());
    assertEquals(0, container.getItem(0).getCount());
    assertEquals(2, container.getItem(1).getCount());
    assertEquals(4, container.getItem(2).getCount());
    assertEquals(1, container.changed);

    assertEquals(applied, success(gateway.compareAndWithdraw(request).toCompletableFuture().get()));
    assertEquals(2, container.getItem(1).getCount());
    assertInstanceOf(
        PlatformResult.Conflict.class,
        gateway.compareAndWithdraw(new SupplyWithdrawalRequest(
            "native-supply-stale", location, before.version(), template, 1))
            .toCompletableFuture().get());
    scheduler.close();
  }

  @SuppressWarnings("unchecked")
  private static <T> T success(PlatformResult<T> result) {
    return ((PlatformResult.Success<T>) assertInstanceOf(PlatformResult.Success.class, result))
        .value();
  }

  public static final class FixtureServer implements Executor {
    private final List<FixtureLevel> levels;

    FixtureServer(FixtureContainer container) {
      levels = List.of(new FixtureLevel(container));
    }

    public Iterable<FixtureLevel> getAllLevels() {
      return levels;
    }

    @Override
    public void execute(Runnable action) {
      action.run();
    }
  }

  public static final class FixtureLevel {
    private final FixtureContainer container;

    FixtureLevel(FixtureContainer container) {
      this.container = container;
    }

    public FixtureDimension dimension() {
      return new FixtureDimension();
    }

    public FixtureContainer getBlockEntity(BlockPos ignored) {
      return container;
    }
  }

  public static final class FixtureDimension {
    public String location() {
      return "minecraft:overworld";
    }
  }

  public static final class FixtureContainer {
    private final ItemStack[] stacks;
    private int changed;

    FixtureContainer(ItemStack... stacks) {
      this.stacks = stacks;
    }

    public int getContainerSize() {
      return stacks.length;
    }

    public ItemStack getItem(int slot) {
      return stacks[slot];
    }

    public void setItem(int slot, ItemStack stack) {
      stacks[slot] = stack;
    }

    public void setChanged() {
      changed++;
    }
  }
}
