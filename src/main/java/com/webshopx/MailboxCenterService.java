package com.webshopx;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * User-facing aggregate over all platform-custody delivery sources.
 *
 * <p>The web client intentionally receives a stable mailbox DTO instead of table-specific rows.
 */
class MailboxCenterService {
  private static final int MAX_PAGE_SIZE = 100;

  private final OrderService orderService;
  private final Supplier<PluginSettings> settingsSupplier;

  MailboxCenterService(OrderService orderService, Supplier<PluginSettings> settingsSupplier) {
    this.orderService = orderService;
    this.settingsSupplier = settingsSupplier;
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
      RefundEligibility eligibility = refundEligibility(order, now);
      entries.add(new MailboxEntry(
          encodeEntryId(order.orderNo()),
          order.orderNo().startsWith("MKT-") ? "MARKET_ITEM" : order.productType(),
          order.orderNo().startsWith("MKT-") ? "MARKET" : "ORDER",
          order.orderNo(),
          order.productTitle(),
          order.itemMaterial(),
          order.quantity(),
          deliveredQuantity,
          eligibility.refundable() ? Math.max(0, order.refundQuantity()) : 0,
          deliveryStatus(order, deliveredQuantity),
          order.createdAt(),
          true,
          eligibility.refundable(),
          eligibility.reason(),
          order.refundDeadline()));
    }
    return List.copyOf(entries);
  }

  int count(long userId) {
    return list(userId, MAX_PAGE_SIZE, null).size();
  }

  OrderService.RefundResult refund(long userId, String entryId) {
    String orderNo = decodeOrderNo(entryId);
    return orderService.refundOrder(userId, orderNo);
  }

  private RefundEligibility refundEligibility(OrderService.OrderView order, LocalDateTime now) {
    if (!settingsSupplier.get().refundUndeliveredEnabled()) {
      return new RefundEligibility(false, "REFUND_DISABLED");
    }
    if (order.refundQuantity() <= 0) {
      return new RefundEligibility(false, "NO_REFUNDABLE_QUANTITY");
    }
    if (order.refundDeadline() != null && now.isAfter(order.refundDeadline())) {
      return new RefundEligibility(false, "REFUND_EXPIRED");
    }
    if (order.earnedQuantity() > 0 && order.refundQuantity() < order.quantity()
        && !isItemLike(order.productType())) {
      return new RefundEligibility(false, "PARTIAL_REFUND_NOT_ALLOWED");
    }
    return new RefundEligibility(true, null);
  }

  private boolean isItemLike(String productType) {
    String normalized = productType == null ? "" : productType.toUpperCase(Locale.ROOT);
    return normalized.contains("ITEM") || normalized.contains("MATERIAL");
  }

  private boolean isTerminal(String status) {
    return "REFUNDED".equalsIgnoreCase(status)
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
      LocalDateTime refundDeadline) {
  }

  private record RefundEligibility(boolean refundable, String reason) {
  }
}
