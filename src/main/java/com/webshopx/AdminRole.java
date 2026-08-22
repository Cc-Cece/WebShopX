package com.webshopx;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

enum AdminRole {
  SUPER_ADMIN(EnumSet.allOf(AdminPermission.class)),
  SHOP_ADMIN(EnumSet.of(
      AdminPermission.REDEEM_MANAGE,
      AdminPermission.PRODUCT_MANAGE,
      AdminPermission.PRODUCT_ZERO_PRICE,
      AdminPermission.ORDER_VIEW,
      AdminPermission.ECONOMY_MANAGE,
      AdminPermission.HOMEPAGE_MANAGE,
      AdminPermission.PROMOTION_VIEW,
      AdminPermission.PROMOTION_MANAGE,
      AdminPermission.COUPON_MANAGE,
      AdminPermission.COUPON_GRANT,
      AdminPermission.COUPON_COMPENSATE,
      AdminPermission.MEMBERSHIP_VIEW,
      AdminPermission.MEMBERSHIP_MANAGE,
      AdminPermission.MEMBERSHIP_GRANT,
      AdminPermission.PROMOTION_FINANCE_VIEW)),
  MARKET_MODERATOR(EnumSet.of(
      AdminPermission.MARKET_MANAGE,
      AdminPermission.PROMOTION_VIEW,
      AdminPermission.SELLER_PROMOTION_MODERATE)),
  SUPPORT_ADMIN(EnumSet.of(
      AdminPermission.USER_SUPPORT,
      AdminPermission.ORDER_VIEW,
      AdminPermission.PROMOTION_VIEW,
      AdminPermission.MEMBERSHIP_VIEW)),
  AUDITOR(EnumSet.of(
      AdminPermission.AUDIT_VIEW,
      AdminPermission.PROMOTION_VIEW,
      AdminPermission.MEMBERSHIP_VIEW,
      AdminPermission.PROMOTION_FINANCE_VIEW));

  private final Set<AdminPermission> permissions;

  AdminRole(Set<AdminPermission> permissions) {
    this.permissions = EnumSet.copyOf(permissions);
  }

  boolean allows(AdminPermission permission) {
    return permissions.contains(permission);
  }

  Set<AdminPermission> permissions() {
    return EnumSet.copyOf(permissions);
  }

  static AdminRole fromRaw(String raw) {
    if (raw == null || raw.isBlank()) {
      return SUPER_ADMIN;
    }
    try {
      return AdminRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new ServiceException("invalid_role", "Admin role is invalid");
    }
  }
}
