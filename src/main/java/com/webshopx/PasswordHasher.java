package com.webshopx;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

class PasswordHasher {
  private static final int ITERATIONS = 65_536;
  private static final int KEY_LENGTH = 256;
  private static final int SALT_BYTES = 16;

  private final SecureRandom secureRandom = new SecureRandom();

  String newSalt() {
    byte[] salt = new byte[SALT_BYTES];
    secureRandom.nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt);
  }

  String hash(String password, String saltBase64) {
    byte[] salt = Base64.getDecoder().decode(saltBase64);
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
    try {
      SecretKeyFactory keyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
      byte[] encoded = keyFactory.generateSecret(spec).getEncoded();
      return Base64.getEncoder().encodeToString(encoded);
    } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
      throw new IllegalStateException("Password hash algorithm is unavailable", exception);
    } finally {
      spec.clearPassword();
    }
  }

  boolean verify(String password, String saltBase64, String expectedHash) {
    String actualHash = hash(password, saltBase64);
    return constantTimeEquals(expectedHash, actualHash);
  }

  private boolean constantTimeEquals(String expectedHash, String actualHash) {
    if (expectedHash.length() != actualHash.length()) {
      return false;
    }
    int difference = 0;
    for (int index = 0; index < expectedHash.length(); index++) {
      difference |= expectedHash.charAt(index) ^ actualHash.charAt(index);
    }
    return difference == 0;
  }
}
