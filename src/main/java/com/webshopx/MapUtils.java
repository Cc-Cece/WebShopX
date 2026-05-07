package com.webshopx;

import java.util.HashMap;
import java.util.Map;

/**
 * Minimal utility to construct maps in Java 8-compatible way.
 */
final class MapUtils {
  private MapUtils() {}

  static Map<String, Object> mapOf(Object... entries) {
    Map<String, Object> map = new HashMap<>();
    if (entries == null) {
      return map;
    }
    for (int i = 0; i + 1 < entries.length; i += 2) {
      Object k = entries[i];
      Object v = entries[i + 1];
      if (k == null) {
        continue;
      }
      map.put(String.valueOf(k), v);
    }
    return map;
  }
}
