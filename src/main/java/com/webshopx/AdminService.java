package com.webshopx;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class AdminService {
  private final DatabaseManager databaseManager;
  private final SqlProvider sqlProvider;
  private final AuthService authService;
  private final WalletService walletService;

  AdminService(DatabaseManager databaseManager, AuthService authService, WalletService walletService) {
    this.databaseManager = databaseManager;
    this.sqlProvider = databaseManager.sqlProvider();
    this.authService = authService;
    this.walletService = walletService;
  }

  void ensureBootstrapAdmin(PluginSettings.AdminBootstrapSettings settings) {
    if (settings == null || !settings.enabled()) {
      return;
    }
    String username = settings.username();
    String password = settings.password();
    AdminRole role = AdminRole.fromRaw(settings.role());
    if (username == null || username.isBlank()) {
      return;
    }

    databaseManager.inTransaction(connection -> {
      Long userId = findUserIdByUsername(connection, username);
      if (userId == null) {
        userId = authService.createUserForAdminBootstrap(connection, username, password);
      }
      upsertAdminAccess(
          connection,
          userId,
          role == AdminRole.SUPER_ADMIN,
          role.permissions(),
          role.name());
      return null;
    });
  }

  AdminLoginResult login(String identifier, String password) {
    AuthService.AuthResult result = authService.login(identifier, password);
    AdminRecord adminRecord = readAdminRecord(result.user().id());
    if (adminRecord == null || !adminRecord.active()) {
      authService.logout(result.sessionToken());
      throw new ServiceException("not_admin", "Admin permission required");
    }
    return new AdminLoginResult(result, adminUserFrom(result.user(), adminRecord));
  }

  AdminUser requireAdmin(AuthService.AuthUser user, AdminPermission permission) {
    AdminRecord record = readAdminRecord(user.id());
    if (record == null || !record.active()) {
      throw new ServiceException("forbidden", "Admin permission required");
    }
    if (permission != null && !record.allows(permission)) {
      throw new ServiceException("forbidden", "Admin permission denied");
    }
    return adminUserFrom(user, record);
  }

  AdminUser requireSuperAdmin(AuthService.AuthUser user) {
    AdminUser admin = getAdminUser(user);
    if (!admin.isSuperAdmin()) {
      throw new ServiceException("forbidden", "Super admin permission required");
    }
    return admin;
  }

  AdminUser getAdminUser(AuthService.AuthUser user) {
    AdminRecord record = readAdminRecord(user.id());
    if (record == null || !record.active()) {
      throw new ServiceException("forbidden", "Admin permission required");
    }
    return adminUserFrom(user, record);
  }

  Optional<UserSupportView> lookupUser(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return Optional.empty();
    }
    return databaseManager.withConnection(connection -> {
      UserRow userRow = findUserByIdentifier(connection, identifier);
      if (userRow == null) {
        return Optional.empty();
      }
      WalletService.WalletBalance balance = walletService.getBalance(userRow.id());
      return Optional.of(new UserSupportView(
          userRow.id(),
          userRow.username(),
          userRow.boundUuid(),
          userRow.authState(),
          userRow.createdAt(),
          balance.shopCoin(),
          balance.gameCoin()));
    });
  }

  List<AdminAccessView> listAdmins() {
    return databaseManager.withConnection(this::readAdminViews);
  }

  AdminAccessView upsertAdmin(
      long actorUserId,
      String identifier,
      boolean superAdmin,
      Set<AdminPermission> permissions,
      String templateKey) {
    return databaseManager.inTransaction(connection -> {
      UserRow targetUser = findUserByIdentifier(connection, identifier);
      if (targetUser == null) {
        throw new ServiceException("not_found", "User not found");
      }
      if (superAdmin && actorUserId == targetUser.id()) {
        // Allow keeping self as super admin without extra restrictions.
      }
      Set<AdminPermission> normalizedPermissions = normalizePermissions(superAdmin, permissions);
      String normalizedTemplateKey = normalizeTemplateKey(templateKey);
      upsertAdminAccess(
          connection,
          targetUser.id(),
          superAdmin,
          normalizedPermissions,
          normalizedTemplateKey);
      return readAdminView(connection, targetUser.id());
    });
  }

  AdminAccessView setAdminActive(long actorUserId, long targetUserId, boolean active) {
    return databaseManager.inTransaction(connection -> {
      AdminAccessView existing = readAdminView(connection, targetUserId);
      if (existing == null) {
        throw new ServiceException("not_found", "Admin user not found");
      }
      if (actorUserId == targetUserId && !active) {
        throw new ServiceException("forbidden", "You cannot disable your own admin account");
      }
      if (!active && existing.isSuperAdmin() && countActiveSuperAdmins(connection) <= 1) {
        throw new ServiceException("forbidden", "At least one active super admin must remain");
      }
      String sql = "UPDATE web_admins SET active = ? WHERE user_id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setBoolean(1, active);
        statement.setLong(2, targetUserId);
        statement.executeUpdate();
      }
      return readAdminView(connection, targetUserId);
    });
  }

  List<PermissionGroup> listPermissionGroups() {
    return List.of(
        new PermissionGroup(
            "catalog",
            "admin.permissions.group.catalog",
            List.of(
                permissionDefinition(
                    AdminPermission.PRODUCT_MANAGE,
                    "admin.permissions.permission.PRODUCT_MANAGE.label",
                    "admin.permissions.permission.PRODUCT_MANAGE.description"),
                permissionDefinition(
                    AdminPermission.PRODUCT_ZERO_PRICE,
                    "admin.permissions.permission.PRODUCT_ZERO_PRICE.label",
                    "admin.permissions.permission.PRODUCT_ZERO_PRICE.description"),
                permissionDefinition(
                    AdminPermission.REDEEM_MANAGE,
                    "admin.permissions.permission.REDEEM_MANAGE.label",
                    "admin.permissions.permission.REDEEM_MANAGE.description"))),
        new PermissionGroup(
            "orders_users",
            "admin.permissions.group.orders_users",
            List.of(
                permissionDefinition(
                    AdminPermission.ORDER_VIEW,
                    "admin.permissions.permission.ORDER_VIEW.label",
                    "admin.permissions.permission.ORDER_VIEW.description"),
                permissionDefinition(
                    AdminPermission.USER_SUPPORT,
                    "admin.permissions.permission.USER_SUPPORT.label",
                    "admin.permissions.permission.USER_SUPPORT.description"))),
        new PermissionGroup(
            "market",
            "admin.permissions.group.market",
            List.of(
                permissionDefinition(
                    AdminPermission.MARKET_MANAGE,
                    "admin.permissions.permission.MARKET_MANAGE.label",
                    "admin.permissions.permission.MARKET_MANAGE.description"))),
        new PermissionGroup(
            "economy",
            "admin.permissions.group.economy",
            List.of(
                permissionDefinition(
                    AdminPermission.ECONOMY_MANAGE,
                    "admin.permissions.permission.ECONOMY_MANAGE.label",
                    "admin.permissions.permission.ECONOMY_MANAGE.description"))),
        new PermissionGroup(
            "audit",
            "admin.permissions.group.audit",
            List.of(
                permissionDefinition(
                    AdminPermission.AUDIT_VIEW,
                    "admin.permissions.permission.AUDIT_VIEW.label",
                    "admin.permissions.permission.AUDIT_VIEW.description"))),
        new PermissionGroup(
            "homepage",
            "admin.permissions.group.homepage",
            List.of(
                permissionDefinition(
                    AdminPermission.HOMEPAGE_MANAGE,
                    "admin.permissions.permission.HOMEPAGE_MANAGE.label",
                    "admin.permissions.permission.HOMEPAGE_MANAGE.description"))));
  }

  List<PermissionTemplate> listPermissionTemplates() {
    return List.of(
        new PermissionTemplate(
            "SHOP_ADMIN",
            "admin.permissions.template.SHOP_ADMIN.label",
            "admin.permissions.template.SHOP_ADMIN.description",
            false,
            sortedPermissionCodes(AdminRole.SHOP_ADMIN.permissions())),
        new PermissionTemplate(
            "SUPPORT_ADMIN",
            "admin.permissions.template.SUPPORT_ADMIN.label",
            "admin.permissions.template.SUPPORT_ADMIN.description",
            false,
            sortedPermissionCodes(AdminRole.SUPPORT_ADMIN.permissions())),
        new PermissionTemplate(
            "MARKET_MODERATOR",
            "admin.permissions.template.MARKET_MODERATOR.label",
            "admin.permissions.template.MARKET_MODERATOR.description",
            false,
            sortedPermissionCodes(AdminRole.MARKET_MODERATOR.permissions())),
        new PermissionTemplate(
            "AUDITOR",
            "admin.permissions.template.AUDITOR.label",
            "admin.permissions.template.AUDITOR.description",
            false,
            sortedPermissionCodes(AdminRole.AUDITOR.permissions())),
        new PermissionTemplate(
            "SUPER_ADMIN",
            "admin.permissions.template.SUPER_ADMIN.label",
            "admin.permissions.template.SUPER_ADMIN.description",
            true,
            sortedPermissionCodes(EnumSet.allOf(AdminPermission.class))));
  }

  void resetPassword(long userId, String newPassword) {
    authService.resetPassword(userId, newPassword);
    authService.logoutAllSessions(userId);
  }

  void unbindUser(long userId) {
    databaseManager.withConnection(connection -> {
      String sql = "UPDATE web_users SET bound_uuid = NULL WHERE id = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        statement.executeUpdate();
      }
      return null;
    });
  }

  UuidMigrationResult migrateUserUuid(long userId, UUID expectedOldUuid, UUID newUuid) {
    if (expectedOldUuid == null || newUuid == null) {
      throw new ServiceException("bad_request", "Both oldUuid and newUuid are required");
    }
    if (expectedOldUuid.equals(newUuid)) {
      throw new ServiceException("bad_request", "The new UUID must differ from the old UUID");
    }

    UuidMigrationResult result = databaseManager.inTransaction(connection -> {
      UUID currentUuid = lockBoundUuid(connection, userId);
      if (currentUuid == null) {
        throw new ServiceException("uuid_not_bound", "The user does not have a bound UUID");
      }
      if (!currentUuid.equals(expectedOldUuid)) {
        throw new ServiceException("uuid_mismatch", "The user's bound UUID has changed; refresh and try again");
      }
      ensureUuidAvailable(connection, userId, newUuid);

      String oldValue = expectedOldUuid.toString();
      String newValue = newUuid.toString();
      Map<String, Integer> migrated = new LinkedHashMap<>();
      migrated.put("orders", executeUpdate(connection,
          "UPDATE orders SET mc_uuid = ? WHERE user_id = ? AND mc_uuid = ? AND status = 'PENDING'",
          newValue, userId, oldValue));
      migrated.put("deliveries", executeUpdate(connection,
          "UPDATE delivery_queue SET mc_uuid = ? WHERE mc_uuid = ? AND status IN ('PENDING', 'WAIT_CLAIM') "
              + "AND order_id IN (SELECT id FROM orders WHERE user_id = ?)",
          newValue, oldValue, userId));
      migrated.put("marketDeliveries", executeUpdate(connection,
          "UPDATE market_item_deliveries SET target_uuid = ? WHERE target_user_id = ? AND target_uuid = ? "
              + "AND status IN ('PENDING', 'WAIT_CLAIM')",
          newValue, userId, oldValue));
      migrated.put("mailboxItems", executeUpdate(connection,
          "UPDATE mailbox_items SET target_uuid = ? WHERE user_id = ? AND target_uuid = ? AND status = 'PENDING'",
          newValue, userId, oldValue));
      migrated.put("marketListings", executeUpdate(connection,
          "UPDATE market_listings SET seller_uuid = ? WHERE seller_user_id = ? AND seller_uuid = ? "
              + "AND status IN ('ACTIVE', 'PAUSED')",
          newValue, userId, oldValue));
      migrated.put("marketBuyers", executeUpdate(connection,
          "UPDATE market_listings SET buyer_uuid = ? WHERE buyer_user_id = ? AND buyer_uuid = ? "
              + "AND status IN ('ACTIVE', 'PAUSED')",
          newValue, userId, oldValue));
      migrated.put("auctionLeaders", executeUpdate(connection,
          "UPDATE market_listings SET auction_highest_bidder_uuid = ? "
              + "WHERE auction_highest_bidder_user_id = ? AND auction_highest_bidder_uuid = ? "
              + "AND status IN ('ACTIVE', 'PAUSED')",
          newValue, userId, oldValue));
      migrated.put("leadingBids", executeUpdate(connection,
          "UPDATE market_bids SET bidder_uuid = ? WHERE bidder_user_id = ? AND bidder_uuid = ? AND status = 'LEADING'",
          newValue, userId, oldValue));
      migrated.put("presence", executeUpdate(connection,
          "DELETE FROM player_presence WHERE mc_uuid = ?", oldValue));

      int accountUpdates = executeUpdate(connection,
          "UPDATE web_users SET bound_uuid = ? WHERE id = ? AND bound_uuid = ?",
          newValue, userId, oldValue);
      if (accountUpdates != 1) {
        throw new ServiceException("uuid_mismatch", "The user's bound UUID changed during migration");
      }
      return new UuidMigrationResult(userId, expectedOldUuid, newUuid, Map.copyOf(migrated));
    });
    authService.logoutAllSessions(userId);
    return result;
  }

  private UUID lockBoundUuid(Connection connection, long userId) throws SQLException {
    String sql = "SELECT bound_uuid FROM web_users WHERE id = ?" + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("not_found", "User not found");
        }
        String value = resultSet.getString("bound_uuid");
        return value == null ? null : UUID.fromString(value);
      }
    }
  }

  private void ensureUuidAvailable(Connection connection, long userId, UUID uuid) throws SQLException {
    String sql = "SELECT id FROM web_users WHERE bound_uuid = ? AND id <> ?" + sqlProvider.forUpdateClause();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, uuid.toString());
      statement.setLong(2, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (resultSet.next()) {
          throw new ServiceException("uuid_in_use", "The new UUID is already bound to another user");
        }
      }
    }
  }

  private int executeUpdate(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      return statement.executeUpdate();
    }
  }

  void forceLogout(long userId) {
    authService.logoutAllSessions(userId);
  }

  WalletService.WalletBalance adjustWallet(long userId, CurrencyType currency, long delta, String reason) {
    String normalizedReason = reason == null || reason.isBlank() ? "ADMIN_ADJUST" : reason.trim();
    if (normalizedReason.length() > 32) {
      normalizedReason = normalizedReason.substring(0, 32);
    }
    String bizId = "admin:" + userId + ":" + System.currentTimeMillis();
    return walletService.adjustBalance(userId, currency, delta, normalizedReason, bizId);
  }

  java.util.List<UserListItem> listUsers(String keyword, int limit) {
    int normalizedLimit = Math.max(1, Math.min(limit, 300));
    String likeKeyword = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim() + "%";
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT u.id, u.username, u.bound_uuid, u.auth_state, u.created_at,
                 COALESCE(w.shop_coin, 0) AS shop_coin,
                 COALESCE(w.game_coin, 0) AS game_coin
          FROM web_users u
          LEFT JOIN wallets w ON w.user_id = u.id
          WHERE (? IS NULL OR u.username LIKE ? OR u.bound_uuid LIKE ? OR %s LIKE ?)
          ORDER BY u.created_at DESC, u.id DESC
          LIMIT ?
          """.formatted(sqlProvider.castAsText("u.id"));
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, likeKeyword);
        statement.setString(2, likeKeyword);
        statement.setString(3, likeKeyword);
        statement.setString(4, likeKeyword);
        statement.setInt(5, normalizedLimit);
        try (ResultSet resultSet = statement.executeQuery()) {
          java.util.List<UserListItem> rows = new java.util.ArrayList<>();
          while (resultSet.next()) {
            String boundUuidRaw = resultSet.getString("bound_uuid");
            UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
            rows.add(new UserListItem(
                resultSet.getLong("id"),
                resultSet.getString("username"),
                boundUuid,
                resultSet.getString("auth_state"),
                resultSet.getTimestamp("created_at").toLocalDateTime(),
                resultSet.getLong("shop_coin"),
                resultSet.getLong("game_coin")));
          }
          return rows;
        }
      }
    });
  }

  private PermissionDefinition permissionDefinition(
      AdminPermission permission,
      String label,
      String description) {
    return new PermissionDefinition(permission.name(), label, description);
  }

  private List<String> sortedPermissionCodes(Set<AdminPermission> permissions) {
    List<String> values = new ArrayList<>();
    for (AdminPermission permission : permissions) {
      values.add(permission.name());
    }
    Collections.sort(values);
    return values;
  }

  private String normalizeTemplateKey(String templateKey) {
    if (templateKey == null || templateKey.isBlank()) {
      return null;
    }
    return templateKey.trim().toUpperCase(Locale.ROOT);
  }

  private Set<AdminPermission> normalizePermissions(boolean superAdmin, Set<AdminPermission> permissions) {
    if (superAdmin) {
      return EnumSet.allOf(AdminPermission.class);
    }
    EnumSet<AdminPermission> normalized = permissions == null || permissions.isEmpty()
        ? EnumSet.noneOf(AdminPermission.class)
        : EnumSet.copyOf(permissions);
    if (normalized.isEmpty()) {
      throw new ServiceException("bad_request", "At least one permission must be selected");
    }
    return normalized;
  }

  private Long findUserIdByUsername(Connection connection, String username) throws SQLException {
    String sql = "SELECT id FROM web_users WHERE username = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, username.trim());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return resultSet.getLong("id");
      }
    }
  }

  private void upsertAdminAccess(
      Connection connection,
      long userId,
      boolean superAdmin,
      Set<AdminPermission> permissions,
      String templateKey) throws SQLException {
    String sql = sqlProvider.upsertAdminAccessSql();
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      statement.setString(2, superAdmin ? AdminRole.SUPER_ADMIN.name() : "CUSTOM");
      statement.setBoolean(3, superAdmin);
      statement.setString(4, permissionsToJson(permissions));
      statement.setString(5, templateKey);
      statement.executeUpdate();
    }
  }

  private AdminUser adminUserFrom(AuthService.AuthUser user, AdminRecord record) {
    return new AdminUser(
        user.id(),
        user.username(),
        user.boundUuid(),
        record.superAdmin(),
        record.permissions(),
        record.roleLabel(),
        record.templateKey());
  }

  private AdminRecord readAdminRecord(long userId) {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT role, active, is_super_admin, permissions_json, template_key
          FROM web_admins
          WHERE user_id = ?
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, userId);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          return mapAdminRecord(resultSet);
        }
      }
    });
  }

  private List<AdminAccessView> readAdminViews(Connection connection) throws SQLException {
    String sql = """
        SELECT a.user_id, a.role, a.active, a.is_super_admin, a.permissions_json, a.template_key,
               a.created_at, a.updated_at,
               u.username, u.bound_uuid
        FROM web_admins a
        JOIN web_users u ON u.id = a.user_id
        ORDER BY a.is_super_admin DESC, a.active DESC, a.updated_at DESC, a.user_id DESC
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      List<AdminAccessView> rows = new ArrayList<>();
      while (resultSet.next()) {
        rows.add(mapAdminView(resultSet));
      }
      return rows;
    }
  }

  private AdminAccessView readAdminView(Connection connection, long userId) throws SQLException {
    String sql = """
        SELECT a.user_id, a.role, a.active, a.is_super_admin, a.permissions_json, a.template_key,
               a.created_at, a.updated_at,
               u.username, u.bound_uuid
        FROM web_admins a
        JOIN web_users u ON u.id = a.user_id
        WHERE a.user_id = ?
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        return mapAdminView(resultSet);
      }
    }
  }

  private int countActiveSuperAdmins(Connection connection) throws SQLException {
    String sql = "SELECT COUNT(*) FROM web_admins WHERE active = TRUE AND is_super_admin = TRUE";
    try (PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet resultSet = statement.executeQuery()) {
      if (!resultSet.next()) {
        return 0;
      }
      return resultSet.getInt(1);
    }
  }

  private AdminRecord mapAdminRecord(ResultSet resultSet) throws SQLException {
    String roleRaw = resultSet.getString("role");
    boolean active = resultSet.getBoolean("active");
    boolean superAdmin = resultSet.getBoolean("is_super_admin")
        || "SUPER_ADMIN".equalsIgnoreCase(roleRaw);
    String permissionsJson = resultSet.getString("permissions_json");
    String templateKey = resultSet.getString("template_key");
    Set<AdminPermission> permissions = resolvePermissions(roleRaw, superAdmin, permissionsJson);
    String roleLabel = resolveRoleLabel(roleRaw, superAdmin, templateKey);
    return new AdminRecord(superAdmin, permissions, roleLabel, templateKey, active);
  }

  private AdminAccessView mapAdminView(ResultSet resultSet) throws SQLException {
    AdminRecord record = mapAdminRecord(resultSet);
    String boundUuidRaw = resultSet.getString("bound_uuid");
    UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
    return new AdminAccessView(
        resultSet.getLong("user_id"),
        resultSet.getString("username"),
        boundUuid,
        record.superAdmin(),
        record.active(),
        record.roleLabel(),
        record.templateKey(),
        sortedPermissionCodes(record.permissions()),
        resultSet.getTimestamp("created_at").toLocalDateTime(),
        resultSet.getTimestamp("updated_at").toLocalDateTime());
  }

  private String resolveRoleLabel(String roleRaw, boolean superAdmin, String templateKey) {
    if (superAdmin) {
      return AdminRole.SUPER_ADMIN.name();
    }
    if (templateKey != null && !templateKey.isBlank()) {
      return templateKey.trim().toUpperCase(Locale.ROOT);
    }
    if (roleRaw != null && !roleRaw.isBlank() && !"CUSTOM".equalsIgnoreCase(roleRaw)) {
      return roleRaw.trim().toUpperCase(Locale.ROOT);
    }
    return "CUSTOM";
  }

  private Set<AdminPermission> resolvePermissions(
      String roleRaw,
      boolean superAdmin,
      String permissionsJson) {
    if (superAdmin) {
      return EnumSet.allOf(AdminPermission.class);
    }
    if (permissionsJson != null && !permissionsJson.isBlank()) {
      return parsePermissionsJson(permissionsJson);
    }
    AdminRole legacyRole = AdminRole.fromRaw(roleRaw);
    return legacyRole.permissions();
  }

  private Set<AdminPermission> parsePermissionsJson(String permissionsJson) {
    EnumSet<AdminPermission> permissions = EnumSet.noneOf(AdminPermission.class);
    try {
      JsonArray array = JsonParser.parseString(permissionsJson).getAsJsonArray();
      array.forEach(element -> {
        if (!element.isJsonNull()) {
          permissions.add(AdminPermission.valueOf(element.getAsString().trim().toUpperCase(Locale.ROOT)));
        }
      });
      return permissions;
    } catch (RuntimeException exception) {
      return EnumSet.noneOf(AdminPermission.class);
    }
  }

  private String permissionsToJson(Set<AdminPermission> permissions) {
    JsonArray array = new JsonArray();
    List<String> codes = sortedPermissionCodes(permissions);
    for (String code : codes) {
      array.add(code);
    }
    return array.toString();
  }

  private UserRow findUserByIdentifier(Connection connection, String identifier) throws SQLException {
    String trimmed = identifier.trim();
    Long userId = tryParseLong(trimmed);
    UUID uuid = tryParseUuid(trimmed);

    String sql = "SELECT id, username, bound_uuid, auth_state, created_at FROM web_users "
        + "WHERE id = ? OR username = ? OR bound_uuid = ? LIMIT 1";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, userId == null ? -1L : userId);
      statement.setString(2, trimmed);
      statement.setString(3, uuid == null ? null : uuid.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return null;
        }
        String boundUuidRaw = resultSet.getString("bound_uuid");
        UUID boundUuid = boundUuidRaw == null ? null : UUID.fromString(boundUuidRaw);
        return new UserRow(
            resultSet.getLong("id"),
            resultSet.getString("username"),
            boundUuid,
            resultSet.getString("auth_state"),
            resultSet.getTimestamp("created_at").toLocalDateTime());
      }
    }
  }

  private UUID tryParseUuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }

  private Long tryParseLong(String raw) {
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  record AdminLoginResult(AuthService.AuthResult authResult, AdminUser admin) {
  }

  record AdminUser(
      long userId,
      String username,
      UUID boundUuid,
      boolean isSuperAdmin,
      Set<AdminPermission> permissions,
      String roleLabel,
      String templateKey) {

    AdminUser {
      permissions = Collections.unmodifiableSet(EnumSet.copyOf(permissions));
    }

    boolean allows(AdminPermission permission) {
      return permission == null || isSuperAdmin || permissions.contains(permission);
    }

    List<String> permissionCodes() {
      List<String> codes = new ArrayList<>();
      for (AdminPermission permission : permissions) {
        codes.add(permission.name());
      }
      Collections.sort(codes);
      return codes;
    }
  }

  private record AdminRecord(
      boolean superAdmin,
      Set<AdminPermission> permissions,
      String roleLabel,
      String templateKey,
      boolean active) {

    boolean allows(AdminPermission permission) {
      return permission == null || superAdmin || permissions.contains(permission);
    }
  }

  record AdminAccessView(
      long userId,
      String username,
      UUID boundUuid,
      boolean isSuperAdmin,
      boolean active,
      String roleLabel,
      String templateKey,
      List<String> permissions,
      LocalDateTime createdAt,
      LocalDateTime updatedAt) {
  }

  record PermissionDefinition(String code, String label, String description) {
  }

  record PermissionGroup(String key, String label, List<PermissionDefinition> permissions) {
  }

  record PermissionTemplate(
      String key,
      String label,
      String description,
      boolean superAdmin,
      List<String> permissions) {
  }

  private record UserRow(
      long id,
      String username,
      UUID boundUuid,
      String authState,
      LocalDateTime createdAt) {
  }

  record UserSupportView(
      long userId,
      String username,
      UUID boundUuid,
      String authState,
      LocalDateTime createdAt,
      long shopCoin,
      long gameCoin) {
  }

  record UserListItem(
      long userId,
      String username,
      UUID boundUuid,
      String authState,
      LocalDateTime createdAt,
      long shopCoin,
      long gameCoin) {
  }

  record UuidMigrationResult(
      long userId,
      UUID oldUuid,
      UUID newUuid,
      Map<String, Integer> migrated) {
  }
}
