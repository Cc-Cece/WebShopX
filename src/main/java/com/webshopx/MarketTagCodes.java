package com.webshopx;

import java.text.Normalizer;
import java.util.Locale;

final class MarketTagCodes {
  private static final int MAX_CODE_POINTS = 64;

  private MarketTagCodes() {
  }

  static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    String source = Normalizer.normalize(raw, Normalizer.Form.NFKC)
        .strip()
        .toLowerCase(Locale.ROOT);
    if (source.isEmpty()) {
      return null;
    }

    StringBuilder result = new StringBuilder();
    boolean pendingSeparator = false;
    for (int offset = 0; offset < source.length();) {
      int codePoint = source.codePointAt(offset);
      offset += Character.charCount(codePoint);
      int type = Character.getType(codePoint);
      boolean wordCharacter = Character.isLetterOrDigit(codePoint)
          || type == Character.NON_SPACING_MARK
          || type == Character.COMBINING_SPACING_MARK;
      if (wordCharacter || codePoint == '-' || codePoint == '_') {
        if (pendingSeparator && result.length() > 0 && result.charAt(result.length() - 1) != '_') {
          result.append('_');
        }
        result.appendCodePoint(codePoint);
        pendingSeparator = false;
      } else {
        pendingSeparator = true;
      }
    }

    String normalized = result.toString().replaceAll("^_+|_+$", "");
    if (normalized.isEmpty()) {
      return null;
    }
    int count = normalized.codePointCount(0, normalized.length());
    if (count > MAX_CODE_POINTS) {
      normalized = normalized.substring(0, normalized.offsetByCodePoints(0, MAX_CODE_POINTS))
          .replaceAll("_+$", "");
    }
    return normalized.isEmpty() ? null : normalized;
  }
}
