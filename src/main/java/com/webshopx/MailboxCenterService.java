package com.webshopx;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * User-facing aggregate over all platform-custody delivery sources.
 *
 * <p>The web client intentionally receives a stable mailbox DTO instead of table-specific rows.
 */
class MailboxCenterService {
  private static final int MAX_PAGE_SIZE = 100;

  private final OrderService orderService;
  private final MailboxService mailboxService;
  private final DeliveryService deliveryService;
  private final OfflineInventoryFeatureService offlineInventoryFeatureService;
  private final ConcurrentHashMap<String, ReentrantLock> operationLocks = new ConcurrentHashMap<>();

  MailboxCenterService(
      OrderService orderService,
      MailboxService mailboxService,
      DeliveryService deliveryService,
      OfflineInventoryFeatureService offlineInventoryFeatureService) {
    this.orderService = orderService;
    this.mailboxService = mailboxService;
    this.deliveryService = deliveryService;
    this.offlineInventoryFeatureService = offlineInventoryFeatureService;
  }

  List<MailboxEntry> list(long userId, int limit, Long cursor) {
    int pageSize = Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
    List<OrderService.OrderView> orders = orderService.listOrdersForUser(userId, pageSize, cursor);
    LocalDateTime now = LocalDateTime.now();
    List<MailboxEntry> entries = new ArrayList<>();
    for (OrderService.OrderView order : orders) {
      int deliveredQuantity = Math.max(0, order.earnedQuantity());
      int remainingQuantity = Math.max(0, order.quantity() - deliveredQuantity);
      if (remainingQuantity <= 0 || isTerminal(order.status())) {
        continue;
      }
      OrderService.RefundEligibility eligibility =
          orderService.refundEligibility(userId, order.orderNo());
      entries.add(new MailboxEntry(
          encodeEntryId(order.orderNo()),
          order.orderNo().startsWith("MKT-") ? "MARKET_ITEM" : order.productType(),
          order.orderNo().startsWith("MKT-") ? "MARKET" : "ORDER",
          order.orderNo(),
          order.productTitle(),
          order.itemMaterial(),
          order.quantity(),
          deliveredQuantity,
          eligibility.refundable() ? Math.max(0, eligibility.refundableQuantity()) : 0,
          deliveryStatus(order, deliveredQuantity),
          order.createdAt(),
          true,
          eligibility.refundable(),
          eligibility.reason(),
          order.refundDeadline(),
          null,
          eligibility.refundAmount(),
          order.currency().name(),
          "SNAPSHOT",
          eligibility.reason() == null
              || !"PARTIAL_REFUND_NOT_ALLOWED".equals(eligibility.reason()),
          order.orderNo(),
          "/orders?order=" + order.orderNo(),
          null,
          false,
          "REFUND_IN_PROGRESS".equals(eligibility.reason()),
          order.productTitle(),
          null,
          order.itemMetaJson()));
    }
    for (MailboxService.StandaloneMailboxItem item
        : mailboxService.listStandalonePending(userId, pageSize)) {
      String material = item.item() == null ? null : item.item().getType().name();
      entries.add(new MailboxEntry(
          "MAILBOX:" + item.id(),
          "ITEM",
          item.sourceType() == null ? "DELIVERY" : item.sourceType().toUpperCase(Locale.ROOT),
          item.sourceRef() == null ? "MAILBOX-" + item.id() : item.sourceRef(),
          material == null ? "UNKNOWN" : material,
          material,
          item.quantity(),
          item.deliveredQuantity(),
          0,
          item.deliveredQuantity() > 0 ? "PARTIAL" : "MAILBOX",
          item.createdAt(),
          true,
          false,
          "PRODUCT_NOT_REFUNDABLE",
          null,
          item.reason(),
          0L,
          null,
          "DISABLED",
          false,
          item.sourceRef(),
          null,
          item.reason(),
          false,
          false,
          material,
          null,
          item.itemMetaJson()));
    }
    entries.sort(Comparator.comparing(
        MailboxEntry::createdAt,
        Comparator.nullsLast(Comparator.reverseOrder())));
    return List.copyOf(entries.subList(0, Math.min(pageSize, entries.size())));
  }

  int count(long userId) {
    return orderService.countMailboxOrdersForUser(userId)
        + mailboxService.countStandalonePending(userId);
  }

  OrderService.RefundResult refund(long userId, String entryId, String idempotencyKey) {
    return withOperationLock(entryId, "REFUND_IN_PROGRESS", () -> {
      String orderNo = decodeOrderNo(entryId);
      return orderService.refundOrder(userId, orderNo, idempotencyKey);
    });
  }

  ClaimResult claim(long userId, String entryId) {
    return withOperationLock(entryId, "DELIVERY_IN_PROGRESS", () -> {
      OrderService.OrderView order = null;
      Long mailboxId = null;
      if (entryId != null && entryId.startsWith("ORDER:")) {
        String orderNo = decodeOrderNo(entryId);
        order = orderService.listOrdersForUser(userId, MAX_PAGE_SIZE, null).stream()
            .filter(candidate -> candidate.orderNo().equalsIgnoreCase(orderNo))
            .findFirst()
            .orElseThrow(() ->
                new ServiceException("mailbox_entry_missing", "Mailbox entry was not found"));
      } else if (entryId != null && entryId.startsWith("MAILBOX:")) {
        try {
          mailboxId = Long.parseLong(entryId.substring("MAILBOX:".length()));
        } catch (NumberFormatException exception) {
          throw new ServiceException("mailbox_entry_missing", "Mailbox entry was not found");
        }
      } else {
        throw new ServiceException("mailbox_entry_missing", "Mailbox entry was not found");
      }

      Long resolvedMailboxId = mailboxId;
      Player player = order == null
          ? Bukkit.getOnlinePlayers().stream()
              .filter(candidate -> mailboxBelongsTo(candidate, userId, resolvedMailboxId))
              .findFirst().orElse(null)
          : Bukkit.getPlayer(order.mcUuid());
      if (player == null || !player.isOnline()) {
        if (!offlineInventoryFeatureService.state().enabled()) {
          throw new ServiceException(
              "offline_delivery_disabled", "Log in to the game before collecting this entry");
        }
        throw new ServiceException(
            "target_server_unavailable",
            "Offline delivery is enabled, but this entry requires an active game server");
      }

      int success = 0;
      int failed = 0;
      if (order != null) {
        MailboxService.MailboxClaimSummary mailboxSummary = mailboxService.claimEntry(
            player,
            userId,
            null,
            order.orderNo().startsWith("MKT-") ? "MARKET" : "ORDER",
            order.orderNo());
        success += mailboxSummary.success();
        failed += mailboxSummary.failed();
        if (order.claimToken() != null && !order.claimToken().isBlank()) {
          DeliveryService.ClaimSummary deliverySummary =
              deliveryService.claimPending(player, order.claimToken());
          success += deliverySummary.success();
          failed += deliverySummary.failed();
        }
      } else {
        MailboxService.MailboxClaimSummary summary =
            mailboxService.claimEntry(player, userId, mailboxId, null, null);
        success += summary.success();
        failed += summary.failed();
      }
      if (success <= 0) {
        throw new ServiceException(
            failed > 0 ? "delivery_failed" : "already_delivered",
            failed > 0 ? "Delivery failed" : "Mailbox entry was already delivered");
      }
      return new ClaimResult(entryId, success, failed);
    });
  }

  private boolean mailboxBelongsTo(Player player, long userId, Long mailboxId) {
    if (player == null || mailboxId == null) {
      return false;
    }
    return mailboxService.listStandalonePending(userId, MAX_PAGE_SIZE).stream()
        .anyMatch(item -> item.id() == mailboxId
            && player.getUniqueId().toString().equalsIgnoreCase(item.targetUuid()));
  }

  private <T> T withOperationLock(
      String entryId, String busyCode, java.util.function.Supplier<T> operation) {
    ReentrantLock lock = operationLocks.computeIfAbsent(entryId, ignored -> new ReentrantLock());
    if (!lock.tryLock()) {
      throw new ServiceException(busyCode, "Another mailbox operation is in progress");
    }
    try {
      return operation.get();
    } finally {
      lock.unlock();
      if (!lock.hasQueuedThreads()) {
        operationLocks.remove(entryId, lock);
      }
    }
  }
  private boolean isTerminal(String status) {
    return "REFUNDED".equalsIgnoreCase(status)
        || "PARTIALLY_REFUNDED".equalsIgnoreCase(status)
        || "CANCELLED".equalsIgnoreCase(status)
        || "RECYCLED".equalsIgnoreCase(status);
  }

  private String deliveryStatus(OrderService.OrderView order, int deliveredQuantity) {
    if (deliveredQuantity <= 0) {
      return "MAILBOX";
    }
    if (deliveredQuantity < order.quantity()) {
      return "PARTIAL";
    }
    return "DELIVERED";
  }

  private String encodeEntryId(String orderNo) {
    return "ORDER:" + orderNo;
  }

  private String decodeOrderNo(String entryId) {
    if (entryId == null || !entryId.startsWith("ORDER:") || entryId.length() <= 6) {
      throw new ServiceException("mailbox_entry_missing", "Mailbox entry was not found");
    }
    return entryId.substring(6);
  }

  record MailboxEntry(
      String id,
      String type,
      String sourceType,
      String sourceRef,
      String title,
      String material,
      int quantity,
      int deliveredQuantity,
      int refundableQuantity,
      String status,
      LocalDateTime createdAt,
      boolean collectible,
      boolean refundable,
      String refundReason,
      LocalDateTime refundDeadline,
      String reason,
      long refundAmount,
      String refundCurrency,
      String refundPolicy,
      boolean partialRefundAllowed,
      String sourceOrderNo,
      String sourceRoute,
      String lastDeliveryError,
      boolean deliveryInProgress,
      boolean refundInProgress,
      String displayName,
      String iconUrl,
      String itemMetaJson) {
  }

  record ClaimResult(String entryId, int success, int failed) {
  }
}
