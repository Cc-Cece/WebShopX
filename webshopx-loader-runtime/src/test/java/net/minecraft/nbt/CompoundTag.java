package net.minecraft.nbt;

import java.util.HashMap;
import java.util.Map;

public final class CompoundTag {
  private String snbt = "{}";
  private final Map<String, Object> values = new HashMap<>();
  public CompoundTag() { }
  public CompoundTag(String snbt) { this.snbt = snbt; }
  public void value(String value) { snbt = value; }
  public Object put(String key, Object value) { return values.put(key, value); }
  public ListTag getList(String key, int ignoredType) {
    return values.get(key) instanceof ListTag list ? list : new ListTag();
  }
  public void putByte(String key, byte value) { values.put(key, value); }
  public byte getByte(String key) {
    return values.get(key) instanceof Number number ? number.byteValue() : 0;
  }
  @Override public String toString() { return snbt; }
}
