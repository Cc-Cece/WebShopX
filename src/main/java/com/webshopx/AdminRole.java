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
      AdminPermission.HOMEPAGE_MANAGE)),
  MARKET_MODERATOR(EnumSet.of(AdminPermission.MARKET_MANAGE)),
  SUPPORT_ADMIN(EnumSet.of(AdminPermission.USER_SUPPORT, AdminPermission.ORDER_VIEW)),
  AUDITOR(EnumSet.of(AdminPermission.AUDIT_VIEW));

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
