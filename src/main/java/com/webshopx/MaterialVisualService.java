package com.webshopx;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

class MaterialVisualService {
  private static final int DEFAULT_LIMIT = 200;
  private static final int MAX_LIMIT = 5000;

  private final DatabaseManager databaseManager;

  MaterialVisualService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  List<MaterialVisualEntry> listAll() {
    return list(null, MAX_LIMIT);
  }

  List<MaterialVisualEntry> list(String keyword, int requestedLimit) {
    int limit = normalizeLimit(requestedLimit);
    return databaseManager.withConnection(connection -> {
      String normalizedKeyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
      StringBuilder sql = new StringBuilder(
          """
          SELECT material_key, display_name_override, icon_path, updated_by, updated_at
          FROM material_visual_overrides
          """);
      List<Object> params = new ArrayList<>();
      if (!normalizedKeyword.isBlank()) {
        sql.append(" WHERE LOWER(material_key) LIKE ? OR LOWER(COALESCE(display_name_override, '')) LIKE ?");
        String pattern = "%" + normalizedKeyword + "%";
        params.add(pattern);
        params.add(pattern);
      }
      sql.append(" ORDER BY material_key ASC LIMIT ?");
      params.add(limit);

      try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
        for (int i = 0; i < params.size(); i++) {
          statement.setObject(i + 1, params.get(i));
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          List<MaterialVisualEntry> rows = new ArrayList<>();
          while (resultSet.next()) {
            rows.add(readEntry(resultSet));
          }
          return rows;
        }
      }
    });
  }

  Optional<MaterialVisualEntry> findByMaterialKey(String materialKey) {
    String normalized = normalizeMaterialKey(materialKey);
    if (normalized.isEmpty()) {
      return Optional.empty();
    }
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT material_key, display_name_override, icon_path, updated_by, updated_at
          FROM material_visual_overrides
          WHERE material_key = ?
          LIMIT 1
          """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalized);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return Optional.empty();
          }
          return Optional.of(readEntry(resultSet));
        }
      }
    });
  }

  MaterialVisualEntry upsert(String materialKey, String displayNameOverride, String iconPath, String updatedBy) {
    String normalizedMaterial = normalizeMaterialKey(materialKey);
    if (normalizedMaterial.isEmpty()) {
      throw new ServiceException("bad_request", "Invalid material key");
    }
    String normalizedDisplayName = normalizeDisplayName(displayNameOverride);
    String normalizedIconPath = normalizeIconPath(iconPath);
    String normalizedUpdatedBy = normalizeUpdatedBy(updatedBy);
    return databaseManager.inTransaction(connection -> {
      String sql =
          databaseManager.dbType().isSqlite()
              ? """
              INSERT INTO material_visual_overrides (material_key, display_name_override, icon_path, updated_by)
              VALUES (?, ?, ?, ?)
              ON CONFLICT(material_key) DO UPDATE SET
                display_name_override = excluded.display_name_override,
                icon_path = excluded.icon_path,
                updated_by = excluded.updated_by,
                updated_at = CURRENT_TIMESTAMP
              """
              : """
              INSERT INTO material_visual_overrides (material_key, display_name_override, icon_path, updated_by)
              VALUES (?, ?, ?, ?)
              ON DUPLICATE KEY UPDATE
                display_name_override = VALUES(display_name_override),
                icon_path = VALUES(icon_path),
                updated_by = VALUES(updated_by),
                updated_at = CURRENT_TIMESTAMP
              """;
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalizedMaterial);
        statement.setString(2, normalizedDisplayName);
        statement.setString(3, normalizedIconPath);
        statement.setString(4, normalizedUpdatedBy);
        statement.executeUpdate();
      }
      return readByMaterialKey(connection, normalizedMaterial);
    });
  }

  boolean delete(String materialKey) {
    String normalized = normalizeMaterialKey(materialKey);
    if (normalized.isEmpty()) {
      return false;
    }
    return databaseManager.withConnection(connection -> {
      String sql = "DELETE FROM material_visual_overrides WHERE material_key = ?";
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setString(1, normalized);
        return statement.executeUpdate() > 0;
      }
    });
  }

  private MaterialVisualEntry readByMaterialKey(java.sql.Connection connection, String materialKey)
      throws SQLException {
    String sql = """
        SELECT material_key, display_name_override, icon_path, updated_by, updated_at
        FROM material_visual_overrides
        WHERE material_key = ?
        LIMIT 1
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, materialKey);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("not_found", "Material override not found");
        }
        return readEntry(resultSet);
      }
    }
  }

  private int normalizeLimit(int requestedLimit) {
    if (requestedLimit <= 0) {
      return DEFAULT_LIMIT;
    }
    return Math.min(requestedLimit, MAX_LIMIT);
  }

  private String normalizeMaterialKey(String value) {
    return String.valueOf(value == null ? "" : value)
        .trim()
        .toUpperCase(Locale.ROOT)
        .replace("MINECRAFT:", "")
        .replaceAll("[^A-Z0-9]+", "_")
        .replaceAll("^_+|_+$", "");
  }

  private String normalizeDisplayName(String value) {
    String text = String.valueOf(value == null ? "" : value).trim();
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > 128) {
      return text.substring(0, 128);
    }
    return text;
  }

  private String normalizeIconPath(String value) {
    String text = String.valueOf(value == null ? "" : value).trim();
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > 255) {
      return text.substring(0, 255);
    }
    return text;
  }

  private String normalizeUpdatedBy(String value) {
    String text = String.valueOf(value == null ? "" : value).trim();
    if (text.isBlank()) {
      return null;
    }
    if (text.length() > 64) {
      return text.substring(0, 64);
    }
    return text;
  }

  private MaterialVisualEntry readEntry(ResultSet resultSet) throws SQLException {
    java.sql.Timestamp updatedAt = resultSet.getTimestamp("updated_at");
    return new MaterialVisualEntry(
        resultSet.getString("material_key"),
        resultSet.getString("display_name_override"),
        resultSet.getString("icon_path"),
        resultSet.getString("updated_by"),
        updatedAt == null ? null : updatedAt.toLocalDateTime());
  }

  record MaterialVisualEntry(
      String materialKey,
      String displayNameOverride,
      String iconPath,
      String updatedBy,
      LocalDateTime updatedAt) {}
}
