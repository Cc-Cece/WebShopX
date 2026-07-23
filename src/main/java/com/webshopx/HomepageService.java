package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Database-backed, versioned homepage document store. Visual section semantics stay in the web UI. */
final class HomepageService {
  private static final int MAX_DOCUMENT_BYTES = 512 * 1024;
  private static final int MAX_SECTIONS = 40;
  private static final int MAX_REVISIONS = 50;
  private final DatabaseManager databaseManager;
  private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();

  HomepageService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
    ensureSchema();
  }

  JsonObject publicDocument() {
    return databaseManager.withConnection(connection -> {
      Revision revision = findLatest(connection, "PUBLISHED");
      return revision == null ? disabledDocument() : revision.document().deepCopy();
    });
  }

  JsonObject draftState() {
    return databaseManager.inTransaction(connection -> {
      Revision draft = findLatest(connection, "DRAFT");
      if (draft == null) {
        Revision published = findLatest(connection, "PUBLISHED");
        JsonObject document = published == null ? disabledDocument() : published.document().deepCopy();
        draft = insertRevision(connection, "DRAFT", document, "system", null);
      }
      JsonObject response = revisionJson(draft, true);
      Revision published = findLatest(connection, "PUBLISHED");
      if (published != null) response.addProperty("publishedRevisionId", published.id());
      return response;
    });
  }

  JsonObject saveDraft(JsonObject document, String author) {
    validate(document);
    return databaseManager.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM homepage_revisions WHERE status = 'DRAFT'")) {
        statement.executeUpdate();
      }
      return revisionJson(insertRevision(connection, "DRAFT", document, author, null), true);
    });
  }

  JsonObject publish(String author) {
    return databaseManager.inTransaction(connection -> {
      Revision draft = findLatest(connection, "DRAFT");
      if (draft == null) throw new ServiceException("not_found", "No homepage draft to publish");
      validate(draft.document());
      try (PreparedStatement archive = connection.prepareStatement(
          "UPDATE homepage_revisions SET status = 'ARCHIVED' WHERE status = 'PUBLISHED'")) {
        archive.executeUpdate();
      }
      String now = Instant.now().toString();
      try (PreparedStatement statement = connection.prepareStatement(
          "UPDATE homepage_revisions SET status = 'PUBLISHED', created_by = ?, published_at = ? WHERE id = ?")) {
        statement.setString(1, safeAuthor(author));
        statement.setString(2, now);
        statement.setString(3, draft.id());
        statement.executeUpdate();
      }
      trimHistory(connection);
      return revisionJson(new Revision(draft.id(), "PUBLISHED", draft.document(), safeAuthor(author),
          draft.createdAt(), now), true);
    });
  }

  JsonObject revisions() {
    return databaseManager.withConnection(connection -> {
      JsonArray items = new JsonArray();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id, status, schema_version, created_by, created_at, published_at "
              + "FROM homepage_revisions ORDER BY created_at DESC LIMIT 50");
           ResultSet results = statement.executeQuery()) {
        while (results.next()) {
          JsonObject item = new JsonObject();
          item.addProperty("id", results.getString("id"));
          item.addProperty("status", results.getString("status"));
          item.addProperty("schemaVersion", results.getInt("schema_version"));
          item.addProperty("createdBy", results.getString("created_by"));
          item.addProperty("createdAt", results.getString("created_at"));
          String publishedAt = results.getString("published_at");
          if (publishedAt != null) item.addProperty("publishedAt", publishedAt);
          items.add(item);
        }
      }
      JsonObject response = new JsonObject();
      response.add("items", items);
      return response;
    });
  }

  JsonObject restore(String revisionId, String author) {
    if (revisionId == null || revisionId.isBlank()) throw new ServiceException("bad_request", "Missing revision id");
    return databaseManager.inTransaction(connection -> {
      Revision source = findById(connection, revisionId);
      if (source == null) throw new ServiceException("not_found", "Homepage revision not found");
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM homepage_revisions WHERE status = 'DRAFT'")) {
        statement.executeUpdate();
      }
      return revisionJson(insertRevision(connection, "DRAFT", source.document(), author, null), true);
    });
  }

  JsonObject recordAsset(String storedName, String originalName, String mimeType, long size, String sha256,
      String author) {
    String id = UUID.randomUUID().toString();
    String createdAt = Instant.now().toString();
    databaseManager.inTransaction(connection -> {
      try (PreparedStatement statement = connection.prepareStatement(
          "INSERT INTO homepage_assets (id, stored_name, original_name, mime_type, size_bytes, sha256, created_by, created_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
        statement.setString(1, id); statement.setString(2, storedName); statement.setString(3, originalName);
        statement.setString(4, mimeType); statement.setLong(5, size); statement.setString(6, sha256);
        statement.setString(7, safeAuthor(author)); statement.setString(8, createdAt); statement.executeUpdate();
      }
      return null;
    });
    JsonObject asset = new JsonObject();
    asset.addProperty("id", id); asset.addProperty("url", "/home-assets/" + storedName);
    asset.addProperty("name", originalName); asset.addProperty("mimeType", mimeType);
    asset.addProperty("size", size); asset.addProperty("createdAt", createdAt);
    return asset;
  }

  JsonObject assets() {
    return databaseManager.withConnection(connection -> {
      JsonArray items = new JsonArray();
      try (PreparedStatement statement = connection.prepareStatement(
          "SELECT id, stored_name, original_name, mime_type, size_bytes, created_by, created_at "
              + "FROM homepage_assets ORDER BY created_at DESC LIMIT 200"); ResultSet rs = statement.executeQuery()) {
        while (rs.next()) {
          JsonObject item = new JsonObject(); item.addProperty("id", rs.getString("id"));
          item.addProperty("url", "/home-assets/" + rs.getString("stored_name"));
          item.addProperty("name", rs.getString("original_name")); item.addProperty("mimeType", rs.getString("mime_type"));
          item.addProperty("size", rs.getLong("size_bytes")); item.addProperty("createdBy", rs.getString("created_by"));
          item.addProperty("createdAt", rs.getString("created_at")); items.add(item);
        }
      }
      JsonObject response = new JsonObject(); response.add("items", items); return response;
    });
  }

  private void ensureSchema() {
    databaseManager.inTransaction(connection -> {
      execute(connection, "CREATE TABLE IF NOT EXISTS homepage_revisions ("
          + "id VARCHAR(36) PRIMARY KEY, status VARCHAR(16) NOT NULL, schema_version INTEGER NOT NULL, "
          + "document_json MEDIUMTEXT NOT NULL, created_by VARCHAR(128) NOT NULL, "
          + "created_at VARCHAR(40) NOT NULL, published_at VARCHAR(40) NULL)");
      execute(connection, "CREATE TABLE IF NOT EXISTS homepage_assets ("
          + "id VARCHAR(36) PRIMARY KEY, stored_name VARCHAR(255) NOT NULL UNIQUE, original_name VARCHAR(255) NOT NULL, "
          + "mime_type VARCHAR(80) NOT NULL, size_bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL, "
          + "created_by VARCHAR(128) NOT NULL, created_at VARCHAR(40) NOT NULL)");
      return null;
    });
  }

  private void validate(JsonObject document) {
    if (document == null) throw new ServiceException("bad_request", "Homepage document is required");
    String serialized = gson.toJson(document);
    if (serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)
      throw new ServiceException("bad_request", "Homepage document is too large");
    int schemaVersion = document.has("schemaVersion") ? document.get("schemaVersion").getAsInt() : 0;
    if (schemaVersion != 2) throw new ServiceException("bad_request", "Unsupported homepage schema version");
    JsonArray sections = document.has("sections") && document.get("sections").isJsonArray()
        ? document.getAsJsonArray("sections") : null;
    if (sections == null || sections.size() > MAX_SECTIONS)
      throw new ServiceException("bad_request", "Homepage sections are invalid");
    Set<String> ids = new HashSet<>();
    for (JsonElement element : sections) {
      if (!element.isJsonObject()) throw new ServiceException("bad_request", "Homepage section must be an object");
      JsonObject section = element.getAsJsonObject();
      String id = string(section, "id"); String type = string(section, "type");
      if (id.isBlank() || id.length() > 80 || type.isBlank() || type.length() > 40 || !ids.add(id))
        throw new ServiceException("bad_request", "Homepage section id or type is invalid");
    }
    validateUrls(document);
  }

  private void validateUrls(JsonElement element) {
    if (element == null || element.isJsonNull()) return;
    if (element.isJsonArray()) { for (JsonElement child : element.getAsJsonArray()) validateUrls(child); return; }
    if (!element.isJsonObject()) return;
    for (var entry : element.getAsJsonObject().entrySet()) {
      JsonElement value = entry.getValue(); String key = entry.getKey().toLowerCase(Locale.ROOT);
      if ((key.endsWith("url") || key.endsWith("href")) && value.isJsonPrimitive()) {
        String url = value.getAsString().trim();
        if (!url.isEmpty() && (!url.startsWith("/") || url.startsWith("//"))
            && !url.startsWith("https://") && !url.startsWith("http://")
            && !url.startsWith("mailto:") && !url.startsWith("#"))
          throw new ServiceException("bad_request", "Homepage URL is not allowed");
      } else validateUrls(value);
    }
  }

  private Revision insertRevision(Connection connection, String status, JsonObject document, String author, String publishedAt)
      throws SQLException {
    String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
    try (PreparedStatement statement = connection.prepareStatement(
        "INSERT INTO homepage_revisions (id, status, schema_version, document_json, created_by, created_at, published_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      statement.setString(1,id); statement.setString(2,status); statement.setInt(3,2);
      statement.setString(4,gson.toJson(document)); statement.setString(5,safeAuthor(author));
      statement.setString(6,now); statement.setString(7,publishedAt); statement.executeUpdate();
    }
    return new Revision(id,status,document.deepCopy(),safeAuthor(author),now,publishedAt);
  }

  private Revision findLatest(Connection connection, String status) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id, status, document_json, created_by, created_at, published_at FROM homepage_revisions "
            + "WHERE status = ? ORDER BY created_at DESC LIMIT 1")) {
      statement.setString(1,status); try (ResultSet rs=statement.executeQuery()) { return rs.next()?readRevision(rs):null; }
    }
  }

  private Revision findById(Connection connection, String id) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT id, status, document_json, created_by, created_at, published_at FROM homepage_revisions WHERE id = ?")) {
      statement.setString(1,id); try (ResultSet rs=statement.executeQuery()) { return rs.next()?readRevision(rs):null; }
    }
  }

  private Revision readRevision(ResultSet rs) throws SQLException {
    return new Revision(rs.getString("id"),rs.getString("status"),JsonParser.parseString(rs.getString("document_json")).getAsJsonObject(),
        rs.getString("created_by"),rs.getString("created_at"),rs.getString("published_at"));
  }

  private JsonObject revisionJson(Revision revision, boolean includeDocument) {
    JsonObject result=new JsonObject(); result.addProperty("id",revision.id()); result.addProperty("status",revision.status());
    result.addProperty("createdBy",revision.createdBy()); result.addProperty("createdAt",revision.createdAt());
    if(revision.publishedAt()!=null)result.addProperty("publishedAt",revision.publishedAt());
    if(includeDocument)result.add("document",revision.document().deepCopy()); return result;
  }

  private void trimHistory(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "DELETE FROM homepage_revisions WHERE status = 'ARCHIVED' AND id NOT IN "
            + "(SELECT id FROM (SELECT id FROM homepage_revisions WHERE status = 'ARCHIVED' "
            + "ORDER BY created_at DESC LIMIT " + MAX_REVISIONS + ") retained_revisions)")) {
      statement.executeUpdate();
    }
  }

  private JsonObject disabledDocument() { JsonObject result=new JsonObject(); result.addProperty("schemaVersion",2); result.addProperty("enabled",false); result.add("site",new JsonObject()); result.add("theme",new JsonObject()); result.add("sections",new JsonArray()); return result; }
  private String string(JsonObject object,String key){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString().trim():"";}
  private String safeAuthor(String author){return author==null||author.isBlank()?"system":author.substring(0,Math.min(128,author.length()));}
  private void execute(Connection connection,String sql)throws SQLException{try(PreparedStatement statement=connection.prepareStatement(sql)){statement.execute();}}
  private record Revision(String id,String status,JsonObject document,String createdBy,String createdAt,String publishedAt){}
}
