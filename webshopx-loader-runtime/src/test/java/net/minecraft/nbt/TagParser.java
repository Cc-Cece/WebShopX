package net.minecraft.nbt;

public final class TagParser {
  private TagParser() { }
  public static CompoundTag parseTag(String value) { return new CompoundTag(value); }
}
