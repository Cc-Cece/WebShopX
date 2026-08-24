package com.webshopx;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Platform-neutral content-management use cases shared by Paper and Loader HTTP adapters. */
public final class SharedContentService {
  private final HomepageService homepage;
  private final MaterialVisualService materialVisuals;

  public SharedContentService(DatabaseManager database) {
    homepage = new HomepageService(Objects.requireNonNull(database, "database"));
    materialVisuals = new MaterialVisualService(database);
  }

  public JsonObject homepage() {
    return homepage.publicDocument();
  }

  public JsonObject homepageDraft() {
    return homepage.draftState();
  }

  public JsonObject saveHomepageDraft(JsonObject document, String author) {
    return homepage.saveDraft(document, author);
  }

  public JsonObject publishHomepage(String author) {
    return homepage.publish(author);
  }

  public JsonObject homepageRevisions() {
    return homepage.revisions();
  }

  public JsonObject restoreHomepage(String revisionId, String author) {
    return homepage.restore(revisionId, author);
  }

  public JsonObject homepageAssets() {
    return homepage.assets();
  }

  public List<JsonObject> materialOverrides() {
    return materialJson(materialVisuals.listAll());
  }

  public List<JsonObject> materialOverrides(String keyword, int limit) {
    return materialJson(materialVisuals.list(keyword, limit));
  }

  public JsonObject upsertMaterialOverride(
      String materialKey, String displayNameOverride, String iconPath, String updatedBy) {
    return materialJson(
        materialVisuals.upsert(materialKey, displayNameOverride, iconPath, updatedBy));
  }

  public boolean deleteMaterialOverride(String materialKey) {
    return materialVisuals.delete(materialKey);
  }

  private static List<JsonObject> materialJson(
      List<MaterialVisualService.MaterialVisualEntry> entries) {
    List<JsonObject> result = new ArrayList<>(entries.size());
    entries.forEach(entry -> result.add(materialJson(entry)));
    return List.copyOf(result);
  }

  private static JsonObject materialJson(MaterialVisualService.MaterialVisualEntry entry) {
    JsonObject result = new JsonObject();
    result.addProperty("materialKey", entry.materialKey());
    if (entry.displayNameOverride() == null) result.add("displayNameOverride", null);
    else result.addProperty("displayNameOverride", entry.displayNameOverride());
    if (entry.iconPath() == null) result.add("iconPath", null);
    else result.addProperty("iconPath", entry.iconPath());
    result.addProperty("updatedBy", entry.updatedBy());
    if (entry.updatedAt() == null) result.add("updatedAt", null);
    else result.addProperty("updatedAt", entry.updatedAt().toString());
    return result;
  }
}
