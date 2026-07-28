package com.webshopx;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * User-facing aggregate over all platform-custody delivery sources.
 *
 * <p>The web client intentionally receives a stable mailbox DTO instead of table-specific rows.
 */
class MailboxCenterService {
  private static final int MAX_PAGE_SIZE = 100;

  private final OrderService orderService;
  private final MailboxService mailboxService;

  MailboxCenterService(OrderService orderService, MailboxService mailboxService) {
    this.orderService = orderService;
    this.mailboxService = mailboxService;
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
          null));
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
          item.reason()));
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
    String orderNo = decodeOrderNo(entryId);
    return orderService.refundOrder(userId, orderNo, idempotencyKey);
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
      String reason) {
  }
}
