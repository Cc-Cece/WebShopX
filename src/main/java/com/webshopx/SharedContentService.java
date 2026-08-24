package com.webshopx;

import com.google.gson.JsonObject;
import java.util.Objects;

/** Platform-neutral content-management use cases shared by Paper and Loader HTTP adapters. */
public final class SharedContentService {
  private final HomepageService homepage;

  public SharedContentService(DatabaseManager database) {
    homepage = new HomepageService(Objects.requireNonNull(database, "database"));
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
}
