package com.webshopx;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  @Test
  void hashCanBeVerified() {
    PasswordHasher hasher = new PasswordHasher();
    String salt = hasher.newSalt();
    String hash = hasher.hash("s3curePassword", salt);

    Assertions.assertTrue(hasher.verify("s3curePassword", salt, hash));
    Assertions.assertFalse(hasher.verify("wrongPassword", salt, hash));
  }
}
