package com.webshopx.platform;

import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Native block-container boundary used by automatic market supply listings. */
public interface SupplyInventoryGateway {
  static SupplyInventoryGateway unavailable(String reason) {
    return new SupplyInventoryGateway() {
      @Override
      public CompletionStage<PlatformResult<SupplySnapshot>> snapshot(SupplyLocation location) {
        return CompletableFuture.completedFuture(
            new PlatformResult.Unavailable<>("supply_inventory", reason, Duration.ZERO));
      }

      @Override
      public CompletionStage<PlatformResult<SupplyWithdrawal>> compareAndWithdraw(
          SupplyWithdrawalRequest request) {
        return CompletableFuture.completedFuture(
            new PlatformResult.Unavailable<>("supply_inventory", reason, Duration.ZERO));
      }
    };
  }

  CompletionStage<PlatformResult<SupplySnapshot>> snapshot(SupplyLocation location);

  default CompletionStage<PlatformResult<SupplySnapshot>> inspect(
      UUID playerId, SupplyLocation location) {
    return CompletableFuture.completedFuture(new PlatformResult.Unavailable<>(
        "supply_inventory", "authorized supply inspection is unavailable", Duration.ZERO));
  }

  CompletionStage<PlatformResult<SupplyWithdrawal>> compareAndWithdraw(
      SupplyWithdrawalRequest request);

  /** Reads a platform-side idempotency result without performing a new withdrawal. */
  default CompletionStage<PlatformResult<SupplyWithdrawal>> reconcile(
      SupplyWithdrawalRequest request) {
    return CompletableFuture.completedFuture(
        new PlatformResult.UnknownOutcome<>(request.operationId(), false));
  }

  record SupplyLocation(String world, int x, int y, int z) {
    public SupplyLocation {
      if (world == null || world.isBlank()) throw new IllegalArgumentException("world is required");
    }
  }

  record SupplySnapshot(long version, List<ItemEnvelope> items) {
    public SupplySnapshot {
      items = List.copyOf(items);
    }
  }

  record SupplyWithdrawalRequest(
      String operationId,
      SupplyLocation location,
      long expectedVersion,
      ItemEnvelope expectedTemplate,
      int maximumQuantity) {
    public SupplyWithdrawalRequest {
      if (operationId == null || operationId.isBlank()) {
        throw new IllegalArgumentException("operationId is required");
      }
      if (expectedTemplate == null) throw new IllegalArgumentException("expectedTemplate is required");
      if (maximumQuantity < 1) throw new IllegalArgumentException("maximumQuantity must be positive");
    }
  }

  record SupplyWithdrawal(long version, ItemEnvelope removed, int removedQuantity) {
    public SupplyWithdrawal {
      if (removedQuantity < 0) throw new IllegalArgumentException("removedQuantity is invalid");
      if ((removed == null) != (removedQuantity == 0)) {
        throw new IllegalArgumentException("removed item and quantity disagree");
      }
    }
  }
}
