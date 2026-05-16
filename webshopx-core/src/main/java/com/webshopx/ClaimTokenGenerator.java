package com.webshopx;

import java.security.SecureRandom;

/**
 * Utility for generating short, user-shareable claim tokens.
 */
final class ClaimTokenGenerator {
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

  private ClaimTokenGenerator() {
  }

  static String newOrderToken() {
    return build("CLM-");
  }

  static String newMarketToken() {
    return build("MCL-");
  }

  private static String build(String prefix) {
    StringBuilder builder = new StringBuilder(prefix.length() + 16);
    builder.append(prefix);
    for (int index = 0; index < 16; index++) {
      int pointer = RANDOM.nextInt(ALPHABET.length);
      builder.append(ALPHABET[pointer]);
    }
    return builder.toString();
  }
}
