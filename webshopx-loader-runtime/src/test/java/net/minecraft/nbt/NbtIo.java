package net.minecraft.nbt;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NbtIo {
  private static final Map<String, CompoundTag> VALUES = new ConcurrentHashMap<>();
  private NbtIo() { }
  public static void install(File file, CompoundTag value) throws IOException {
    Files.createDirectories(file.toPath().getParent());
    Files.write(file.toPath(), new byte[]{1});
    VALUES.put(file.getAbsolutePath(), value);
  }
  public static CompoundTag readCompressed(File file) {
    return VALUES.get(file.getAbsolutePath());
  }
  public static CompoundTag readCompressed(InputStream unsupported) {
    throw new AssertionError("stream overload must not be selected for a playerdata path");
  }
  public static void writeCompressed(CompoundTag value, File file) throws IOException {
    Files.write(file.toPath(), new byte[]{2});
    VALUES.put(file.getAbsolutePath(), value);
  }
  public static void writeCompressed(CompoundTag value, OutputStream unsupported) {
    throw new AssertionError("stream overload must not be selected for a playerdata path");
  }
}
