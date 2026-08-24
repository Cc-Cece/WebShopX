package net.minecraft.world.item;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;

public final class ItemStack {
  public static final ItemStack EMPTY = new ItemStack("minecraft:air", 0, "");
  private static final Pattern ID = Pattern.compile("id:\"([^\"]+)\"");
  private static final Pattern COUNT = Pattern.compile("Count:([0-9]+)");
  private static final Pattern CUSTOM = Pattern.compile("custom:\"([^\"]*)\"");
  private final String id;
  private int count;
  private final String custom;

  public ItemStack(String id, int count, String custom) {
    this.id = id;
    this.count = count;
    this.custom = custom;
  }

  public boolean isEmpty() {
    return count == 0 || id.equals("minecraft:air");
  }

  public int getCount() {
    return count;
  }

  public ItemStack copy() {
    return new ItemStack(id, count, custom);
  }

  public void setCount(int count) {
    this.count = count;
  }

  public CompoundTag save(CompoundTag target) {
    target.value("{id:\"" + id + "\",Count:" + count + ",custom:\"" + custom + "\"}");
    return target;
  }

  public static ItemStack of(CompoundTag source) {
    Matcher id = ID.matcher(source.toString());
    Matcher count = COUNT.matcher(source.toString());
    Matcher custom = CUSTOM.matcher(source.toString());
    if (!id.find() || !count.find()) return EMPTY;
    return new ItemStack(
        id.group(1), Integer.parseInt(count.group(1)), custom.find() ? custom.group(1) : "");
  }
}
