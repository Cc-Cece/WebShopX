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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

class AdminService {
  private final DatabaseManager databaseManager;
  private final AuthService authService;
  private final WalletService walletService;

  AdminService(DatabaseManager databaseManager, AuthService authService, WalletService walletService) {
    this.databaseManager = databaseManager;
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
            "商品与商城",
            List.of(
                permissionDefinition(
                    AdminPermission.PRODUCT_MANAGE,
                    "商品管理",
                    "创建、编辑、启用或停用官方商品"),
                permissionDefinition(
                    AdminPermission.PRODUCT_ZERO_PRICE,
                    "允许零价商品",
                    "允许将任意商品价格设置为 0，仍不允许负数"),
                permissionDefinition(
                    AdminPermission.REDEEM_MANAGE,
                    "兑换码管理",
                    "创建和查看兑换码"))),
        new PermissionGroup(
            "orders_users",
            "订单与用户",
            List.of(
                permissionDefinition(
                    AdminPermission.ORDER_VIEW,
                    "订单查看",
                    "查看官方订单和玩家市场订单"),
                permissionDefinition(
                    AdminPermission.USER_SUPPORT,
                    "用户支持",
                    "查询用户、重置密码、解绑、强制下线与调整余额"))),
        new PermissionGroup(
            "market",
            "玩家市场",
            List.of(
                permissionDefinition(
                    AdminPermission.MARKET_MANAGE,
                    "市场管理",
                    "查看并强制下架玩家市场商品"))),
        new PermissionGroup(
            "economy",
            "经济设置",
            List.of(
                permissionDefinition(
                    AdminPermission.ECONOMY_MANAGE,
                    "经济设置",
                    "修改兑换比例、手续费、税率与 Vault 对接相关设置"))),
        new PermissionGroup(
            "audit",
            "审计",
            List.of(
                permissionDefinition(
                    AdminPermission.AUDIT_VIEW,
                    "查看审计日志",
                    "查看后台操作的审计记录"))));
  }

  List<PermissionTemplate> listPermissionTemplates() {
    return List.of(
        new PermissionTemplate(
            "SHOP_ADMIN",
            "商城运营",
            "适合管理商品、零价商品、兑换码，并查看订单。",
            false,
            sortedPermissionCodes(AdminRole.SHOP_ADMIN.permissions())),
        new PermissionTemplate(
            "SUPPORT_ADMIN",
            "客服支持",
            "适合处理账号支持并查看订单。",
            false,
            sortedPermissionCodes(AdminRole.SUPPORT_ADMIN.permissions())),
        new PermissionTemplate(
            "MARKET_MODERATOR",
            "市场管理",
            "适合巡查和处理玩家市场。",
            false,
            sortedPermissionCodes(AdminRole.MARKET_MODERATOR.permissions())),
        new PermissionTemplate(
            "AUDITOR",
            "审计只读",
            "只查看审计日志，不执行写操作。",
            false,
            sortedPermissionCodes(AdminRole.AUDITOR.permissions())),
        new PermissionTemplate(
            "SUPER_ADMIN",
            "超级管理员",
            "拥有全部权限，并可管理其他管理员。",
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
      String idAsText = databaseManager.castAsText("u.id");
      String sql = "SELECT u.id, u.username, u.bound_uuid, u.auth_state, u.created_at, "
          + "COALESCE(w.shop_coin, 0) AS shop_coin, "
          + "COALESCE(w.game_coin, 0) AS game_coin "
          + "FROM web_users u "
          + "LEFT JOIN wallets w ON w.user_id = u.id "
          + "WHERE (? IS NULL OR u.username LIKE ? OR u.bound_uuid LIKE ? OR "
          + idAsText
          + " LIKE ?) "
          + "ORDER BY u.created_at DESC, u.id DESC "
          + "LIMIT ?";
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
    String sql = databaseManager.isSqlite()
        ? """
            INSERT INTO web_admins (user_id, role, active, is_super_admin, permissions_json, template_key)
            VALUES (?, ?, TRUE, ?, ?, ?)
            ON CONFLICT(user_id) DO UPDATE SET
              role = excluded.role,
              active = TRUE,
              is_super_admin = excluded.is_super_admin,
              permissions_json = excluded.permissions_json,
              template_key = excluded.template_key
            """
        : """
            INSERT INTO web_admins (user_id, role, active, is_super_admin, permissions_json, template_key)
            VALUES (?, ?, TRUE, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              role = VALUES(role),
              active = TRUE,
              is_super_admin = VALUES(is_super_admin),
              permissions_json = VALUES(permissions_json),
              template_key = VALUES(template_key)
            """;
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
}
