package net.minecraft.server.level;

import com.mojang.authlib.GameProfile;
import java.util.Arrays;
import net.minecraft.world.item.ItemStack;

public final class ServerPlayer {
  private final GameProfile profile;
  private final Inventory inventory;
  public ServerPlayer(GameProfile profile, int slots) {
    this.profile = profile;
    this.inventory = new Inventory(slots);
  }
  public Inventory getInventory() { return inventory; }
  public static final class Inventory {
    private final ItemStack[] slots;
    private Inventory(int size) {
      slots = new ItemStack[size];
      Arrays.fill(slots, ItemStack.EMPTY);
    }
    public int getContainerSize() { return slots.length; }
    public ItemStack getItem(int slot) { return slots[slot]; }
    public void setItem(int slot, ItemStack item) { slots[slot] = item; }
    public void setChanged() { }
  }
}
