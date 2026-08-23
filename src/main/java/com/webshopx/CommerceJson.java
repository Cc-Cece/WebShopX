package com.webshopx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.time.Instant;

/** Shared JSON codec for promotion and checkout snapshots. */
final class CommerceJson {
  private CommerceJson() {
  }

  static Gson create() {
    return new GsonBuilder()
        .disableHtmlEscaping()
        .registerTypeAdapter(Instant.class, new InstantAdapter())
        .create();
  }

  private static final class InstantAdapter extends TypeAdapter<Instant> {
    @Override
    public void write(JsonWriter writer, Instant value) throws IOException {
      if (value == null) {
        writer.nullValue();
      } else {
        writer.value(value.toString());
      }
    }

    @Override
    public Instant read(JsonReader reader) throws IOException {
      if (reader.peek() == com.google.gson.stream.JsonToken.NULL) {
        reader.nextNull();
        return null;
      }
      return Instant.parse(reader.nextString());
    }
  }
}
