package net.minecraft.nbt;

public final class CompoundTag {
  private String snbt = "{}";
  public CompoundTag() { }
  public CompoundTag(String snbt) { this.snbt = snbt; }
  public void value(String value) { snbt = value; }
  @Override public String toString() { return snbt; }
}
