package com.webshopx.core;

import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded deterministic persistence format for complete ItemEnvelope values. */
public final class ItemEnvelopeBinaryCodec {
  private static final int MAGIC = 0x57584945;
  private static final int FORMAT = 1;

  public byte[] encode(ItemEnvelope value) {
    try {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(buffer)) {
        output.writeInt(MAGIC);
        output.writeInt(FORMAT);
        output.writeInt(value.schemaVersion());
        output.writeUTF(value.codec());
        output.writeInt(value.codecVersion());
        CompatibilityDomain domain = value.compatibilityDomain();
        output.writeUTF(domain.platform());
        output.writeUTF(domain.loader());
        output.writeUTF(domain.minecraftVersion());
        output.writeInt(domain.itemCodecVersion());
        output.writeUTF(domain.modpackFingerprint());
        output.writeUTF(value.registryId());
        output.writeInt(value.count());
        output.writeUTF(value.payloadEncoding().name());
        byte[] payload = value.payload();
        output.writeInt(payload.length);
        output.write(payload);
        output.writeUTF(value.payloadHash());
        output.writeInt(value.summary().size());
        for (Map.Entry<String, String> entry : value.summary().entrySet()) {
          output.writeUTF(entry.getKey());
          output.writeUTF(entry.getValue());
        }
        output.writeLong(value.createdAt().getEpochSecond());
        output.writeInt(value.createdAt().getNano());
      }
      return buffer.toByteArray();
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public ItemEnvelope decode(byte[] encoded) {
    if (encoded == null || encoded.length > ItemEnvelope.MAX_PAYLOAD_BYTES + 256 * 1024) {
      throw new IllegalArgumentException("invalid envelope blob size");
    }
    try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
      if (input.readInt() != MAGIC || input.readInt() != FORMAT) {
        throw new IllegalArgumentException("unknown envelope blob format");
      }
      int schema = input.readInt();
      String codec = input.readUTF();
      int codecVersion = input.readInt();
      CompatibilityDomain domain = new CompatibilityDomain(
          input.readUTF(), input.readUTF(), input.readUTF(), input.readInt(), input.readUTF());
      String registryId = input.readUTF();
      int count = input.readInt();
      ItemEnvelope.PayloadEncoding payloadEncoding =
          ItemEnvelope.PayloadEncoding.valueOf(input.readUTF());
      int payloadLength = input.readInt();
      if (payloadLength < 0 || payloadLength > ItemEnvelope.MAX_PAYLOAD_BYTES) {
        throw new IllegalArgumentException("invalid native payload size");
      }
      byte[] payload = input.readNBytes(payloadLength);
      if (payload.length != payloadLength) throw new IllegalArgumentException("truncated native payload");
      String hash = input.readUTF();
      int summarySize = input.readInt();
      if (summarySize < 0 || summarySize > 1024) throw new IllegalArgumentException("invalid summary size");
      Map<String, String> summary = new LinkedHashMap<>();
      for (int index = 0; index < summarySize; index++) {
        summary.put(input.readUTF(), input.readUTF());
      }
      Instant createdAt = Instant.ofEpochSecond(input.readLong(), input.readInt());
      if (input.read() != -1) throw new IllegalArgumentException("trailing envelope data");
      return new ItemEnvelope(schema, codec, codecVersion, domain, registryId, count,
          payloadEncoding, payload, hash, summary, createdAt);
    } catch (IOException | IllegalArgumentException failure) {
      throw new IllegalArgumentException("invalid envelope blob", failure);
    }
  }
}
