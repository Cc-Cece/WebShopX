package com.webshopx.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.webshopx.core.ItemEnvelopeService;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.InventoryTypes.InventoryMutation;
import com.webshopx.platform.InventoryTypes.InventoryMutationResult;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformResult;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryGatewayContractTest {
  @Test
  void compareApplyIsVersionedPartialAndIdempotent() throws Exception {
    InMemoryInventoryGateway gateway = new InMemoryInventoryGateway(1);
    UUID player = UUID.randomUUID();
    ItemEnvelopeService service = new ItemEnvelopeService(Clock.systemUTC(), Set.of("test"));
    CompatibilityDomain domain = new CompatibilityDomain("test", "test", "1.20.1", 1, "vanilla");
    ItemEnvelope first = service.create("test", 1, domain, "minecraft:stone", 1, new byte[]{1}, Map.of());
    ItemEnvelope second = service.create("test", 1, domain, "minecraft:dirt", 1, new byte[]{2}, Map.of());
    InventoryMutation mutation = new InventoryMutation("op-1", player, 0, List.of(first, second), List.of());

    var applied = assertInstanceOf(PlatformResult.Success.class,
        gateway.compareAndApply(mutation).toCompletableFuture().get());
    InventoryMutationResult result = (InventoryMutationResult) applied.value();
    assertEquals(List.of(first), result.inserted());
    assertEquals(List.of(second), result.remainder());

    var duplicate = assertInstanceOf(PlatformResult.Success.class,
        gateway.compareAndApply(mutation).toCompletableFuture().get());
    assertEquals(result, duplicate.value());
    assertEquals(1, ((PlatformResult.Success<?>) gateway.snapshot(player, false)
        .toCompletableFuture().get()).value() instanceof com.webshopx.platform.InventoryTypes.InventorySnapshot s
        ? s.items().size() : -1);

    assertInstanceOf(PlatformResult.Conflict.class,
        gateway.compareAndApply(new InventoryMutation("op-2", player, 0, List.of(), List.of()))
            .toCompletableFuture().get());
  }
}
