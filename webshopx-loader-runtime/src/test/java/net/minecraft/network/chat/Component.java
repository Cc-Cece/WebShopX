package net.minecraft.network.chat;

public interface Component {
  String text();

  static Component literal(String text) {
    return () -> text;
  }
}
