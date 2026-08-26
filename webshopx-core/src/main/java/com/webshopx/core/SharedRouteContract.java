package com.webshopx.core;

import java.util.LinkedHashSet;
import java.util.Set;

/** Canonical external HTTP route contract shared by Paper and Loader adapters. */
public final class SharedRouteContract {
  private static final Set<String> ROUTES =
      Set.of(
          "/",
          "/health",
          "/home-assets/",
          "/textures/",
          "/visual-packs/",
          "/api/auth/login",
          "/api/auth/logout",
          "/api/auth/me",
          "/api/wallet",
          "/api/wallet/exchange",
          "/api/wallet/ledger",
          "/api/recharge/cancel",
          "/api/recharge/create",
          "/api/recharge/redirect",
          "/api/recharge/status",
          "/api/redeem/use",
          "/api/products",
          "/api/products/price-trend",
          "/api/products/quote",
          "/api/orders",
          "/api/orders/delivery-status",
          "/api/orders/discard",
          "/api/orders/list",
          "/api/orders/policy",
          "/api/orders/refund",
          "/api/mailbox/",
          "/api/mailbox/count",
          "/api/mailbox/list",
          "/api/notifications/list",
          "/api/notifications/mark-read",
          "/api/notifications/unread-count",
          "/api/homepage",
          "/api/homepage/status",
          "/api/inventory/discard",
          "/api/inventory/fulfill",
          "/api/inventory/list",
          "/api/inventory/matches",
          "/api/inventory/reconcile",
          "/api/inventory/snapshot",
          "/api/leaderboard/config",
          "/api/leaderboard/list",
          "/api/locales/",
          "/api/meta/currency",
          "/api/meta/locales",
          "/api/meta/market-tags",
          "/api/meta/material-overrides",
          "/api/meta/materials",
          "/api/meta/version",
          "/api/market/auction-display-settings",
          "/api/market/auction-insights",
          "/api/market/bid",
          "/api/market/buy",
          "/api/market/icon/upload",
          "/api/market/listings",
          "/api/market/listings/create",
          "/api/market/pause",
          "/api/market/price",
          "/api/market/price-trend",
          "/api/market/quote",
          "/api/market/remark",
          "/api/market/resume",
          "/api/market/sell-to-buy",
          "/api/market/settings",
          "/api/market/supply/inspect",
          "/api/market/supply/reconcile",
          "/api/market/supply/refresh",
          "/api/market/unlist",
          "/api/admin/auth/login",
          "/api/admin/auth/logout",
          "/api/admin/auth/me",
          "/api/admin/overview/stats",
          "/api/admin/audit/list",
          "/api/admin/admin-users/active",
          "/api/admin/admin-users/list",
          "/api/admin/admin-users/meta",
          "/api/admin/admin-users/upsert",
          "/api/admin/economy/currency",
          "/api/admin/economy/exchange",
          "/api/admin/economy/leaderboard",
          "/api/admin/economy/market",
          "/api/admin/economy/payment-provider-config",
          "/api/admin/economy/recharge-payment",
          "/api/admin/economy/settings",
          "/api/admin/group-buy/consume",
          "/api/admin/homepage/assets",
          "/api/admin/homepage/draft",
          "/api/admin/homepage/publish",
          "/api/admin/homepage/restore",
          "/api/admin/homepage/revisions",
          "/api/admin/locales",
          "/api/admin/locales/action",
          "/api/admin/locales/default",
          "/api/admin/locales/upload",
          "/api/admin/market/limitation-config",
          "/api/admin/market/listings",
          "/api/admin/market/supply/reconcile",
          "/api/admin/market/supply/unknown",
          "/api/admin/market/tags-config",
          "/api/admin/market/unlist",
          "/api/admin/inventory/reconcile",
          "/api/admin/inventory/unknown",
          "/api/admin/material-overrides/delete",
          "/api/admin/material-overrides/icon",
          "/api/admin/material-overrides/list",
          "/api/admin/material-overrides/upsert",
          "/api/admin/notifications/announce",
          "/api/admin/orders/list",
          "/api/admin/orders/deliveries/reconcile",
          "/api/admin/orders/deliveries/unknown",
          "/api/admin/products/active",
          "/api/admin/products/from-inventory",
          "/api/admin/products/icon",
          "/api/admin/products/list",
          "/api/admin/products/refund-policy",
          "/api/admin/products/reset-limit",
          "/api/admin/products/snapshot-history",
          "/api/admin/products/snapshot-rollback",
          "/api/admin/products/upsert",
          "/api/admin/redeem/create",
          "/api/admin/redeem/list",
          "/api/admin/refund-policy",
          "/api/admin/system/auction-display",
          "/api/admin/system/broadcast",
          "/api/admin/system/home-link",
          "/api/admin/system/logging",
          "/api/admin/system/maintenance",
          "/api/admin/system/market",
          "/api/admin/system/notification",
          "/api/admin/system/offline-inventory",
          "/api/admin/system/update",
          "/api/admin/system/webshop",
          "/api/admin/users/list",
          "/api/admin/users/logout",
          "/api/admin/users/lookup",
          "/api/admin/users/migrate-uuid",
          "/api/admin/users/reset-password",
          "/api/admin/users/unbind",
          "/api/admin/users/visual-permission",
          "/api/admin/users/wallet-adjust",
          "/api/admin/visual/settings",
          "/api/admin/visual-packs",
          "/api/admin/visual-packs/delete",
          "/api/admin/visual-packs/download",
          "/api/admin/visual-packs/move",
          "/api/admin/visual-packs/state",
          "/api/admin/visual-packs/upload",
          "/api/cart",
          "/api/cart/clear",
          "/api/cart/lines/add",
          "/api/cart/lines/remove",
          "/api/cart/lines/update",
          "/api/checkout/quote",
          "/api/checkout/submit",
          "/api/checkouts/refund",
          "/api/coupons/claim",
          "/api/coupons/mine",
          "/api/membership/me",
          "/api/membership/redeem",
          "/api/seller/promotions/action",
          "/api/seller/promotions/create",
          "/api/seller/promotions/list",
          "/api/admin/promotions/action",
          "/api/admin/promotions/create",
          "/api/admin/promotions/emergency-stop",
          "/api/admin/promotions/list",
          "/api/admin/promotions/publish",
          "/api/admin/coupons/grant",
          "/api/admin/coupons/templates",
          "/api/admin/coupons/templates/create",
          "/api/admin/membership/codes/create",
          "/api/admin/membership/grant",
          "/api/admin/membership/plans",
          "/api/admin/membership/plans/create",
          "/api/admin/membership/plans/publish",
          "/api/admin/membership/products/bind",
          "/api/admin/membership/revoke");

  private SharedRouteContract() {}

  public static Set<String> routes() {
    return ROUTES;
  }

  public static void requireDeclared(String path) {
    if (!ROUTES.contains(path)) {
      throw new IllegalArgumentException("HTTP route is absent from the shared contract: " + path);
    }
  }

  /** Fails adapter startup when a declared public route was silently omitted or added. */
  public static void requireComplete(
      String adapter, Set<String> registeredRoutes, Set<String> intentionalOmissions) {
    Set<String> registered = Set.copyOf(registeredRoutes);
    Set<String> omissions = Set.copyOf(intentionalOmissions);
    if (!ROUTES.containsAll(omissions)) {
      Set<String> undeclaredOmissions = new LinkedHashSet<>(omissions);
      undeclaredOmissions.removeAll(ROUTES);
      throw new IllegalArgumentException(
          "Adapter omissions are absent from the shared contract: " + undeclaredOmissions);
    }
    Set<String> expected = new LinkedHashSet<>(ROUTES);
    expected.removeAll(omissions);
    Set<String> missing = new LinkedHashSet<>(expected);
    missing.removeIf(route -> registered.stream().anyMatch(prefix -> covers(prefix, route)));
    Set<String> unexpected = new LinkedHashSet<>(registered);
    unexpected.removeAll(expected);
    if (!missing.isEmpty() || !unexpected.isEmpty()) {
      throw new IllegalStateException(
          "Incomplete shared HTTP adapter " + adapter
              + ": missing=" + missing + ", unexpected=" + unexpected);
    }
  }

  private static boolean covers(String registeredPrefix, String declaredRoute) {
    if (registeredPrefix.equals(declaredRoute)) return true;
    if (registeredPrefix.equals("/")) return false;
    if (!declaredRoute.startsWith(registeredPrefix)) return false;
    return registeredPrefix.endsWith("/")
        || declaredRoute.charAt(registeredPrefix.length()) == '/';
  }
}
