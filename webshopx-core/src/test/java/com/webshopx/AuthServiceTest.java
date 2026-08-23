package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuthServiceTest {
  @TempDir Path temporaryDirectory;
  private DatabaseManager database;
  private AuthService service;
  private Clock clock;

  @BeforeEach void startDatabase() {
    database = new DatabaseManager(null, new DatabaseSettings(
        DbType.SQLITE, "", 0, "", "", "", false, false, "", 1,
        temporaryDirectory.resolve("auth.db").toString(), "WAL", "NORMAL",
        5_000, 3, List.of(10, 25, 50)));
    database.start();
    SchemaProvider.forType(DbType.SQLITE).ensureSchema(database, ZoneOffset.UTC);
    clock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
    service = new AuthService(database, () -> new AuthService.SessionSettings(40, 2), clock);
  }

  @AfterEach void stopDatabase() {
    if (database != null) database.close();
  }

  @Test void createsPlayerAccountLogsInAndRevokesSession() {
    UUID player = UUID.randomUUID();
    AuthService.InGamePasswordResult created =
        service.setPasswordFromGame(player, "Player_One", "correct-horse");
    assertTrue(created.created());

    AuthService.AuthResult login = service.login(player.toString(), "correct-horse");
    assertEquals(created.userId(), login.user().id());
    assertEquals(40, login.sessionToken().length());
    assertEquals("2026-08-23T02:00", login.expiresAt().toString());
    assertTrue(service.findUserBySession(login.sessionToken()).isPresent());

    service.logout(login.sessionToken());
    assertFalse(service.findUserBySession(login.sessionToken()).isPresent());
  }

  @Test void passwordReplacementRevokesExistingSessionsAndRejectsOldSecret() {
    UUID player = UUID.randomUUID();
    service.setPasswordFromGame(player, "Player_Two", "first-secret");
    AuthService.AuthResult first = service.login("Player_Two", "first-secret");

    AuthService.InGamePasswordResult updated =
        service.setPasswordFromGame(player, "Player_Two", "second-secret");
    assertFalse(updated.created());
    assertTrue(service.findUserBySession(first.sessionToken()).isEmpty());
    ServiceException oldPassword = assertThrows(
        ServiceException.class, () -> service.login("Player_Two", "first-secret"));
    assertEquals("invalid_credentials", oldPassword.code());
    assertNotEquals(first.sessionToken(), service.login("Player_Two", "second-secret").sessionToken());
  }

  @Test void expiredAndMissingSessionsFailClosed() {
    service.setPasswordFromGame(UUID.randomUUID(), "Player_Three", "valid-secret");
    AuthService.AuthResult result = service.login("Player_Three", "valid-secret");
    AuthService future = new AuthService(database, () -> new AuthService.SessionSettings(40, 2),
        Clock.offset(clock, Duration.ofHours(3)));
    assertTrue(future.findUserBySession(result.sessionToken()).isEmpty());
    assertTrue(future.findUserBySession("missing").isEmpty());
    assertTrue(future.findUserBySession(" ").isEmpty());
  }

  @Test void recordsAndClearsCrossNodePlayerPresence() {
    PlayerPresenceService presence = new PlayerPresenceService(database,
        () -> new PlayerPresenceService.PresenceSettings("fabric-a", 120));
    UUID player = UUID.randomUUID();
    presence.markOnline(player, "Online_Player");
    assertEquals("fabric-a", presence.resolveOnlineServer(player));
    presence.markOffline(player);
    assertEquals(null, presence.resolveOnlineServer(player));

    UUID another = UUID.randomUUID();
    presence.markOnline(another, "Another_Player");
    presence.markServerOffline("fabric-a");
    assertEquals(null, presence.resolveOnlineServer(another));
  }

  @Test void walletAppliesIdempotentLedgerAndAtomicExchange() {
    long userId = service.setPasswordFromGame(
        UUID.randomUUID(), "Wallet_User", "wallet-secret").userId();
    WalletService wallet = new WalletService(database, () -> new WalletService.ExchangePolicy(
        new WalletService.ExchangeDirection(true, 2.0D),
        new WalletService.ExchangeDirection(false, 0.0D)), null, null);

    assertEquals(100L, wallet.adjustBalance(
        userId, CurrencyType.SHOP_COIN, 100L, "TEST", "credit-1").shopCoin());
    assertEquals(100L, wallet.adjustBalance(
        userId, CurrencyType.SHOP_COIN, 100L, "TEST", "credit-1").shopCoin());
    WalletService.WalletBalance exchanged = wallet.exchange(
        userId, CurrencyType.SHOP_COIN, CurrencyType.GAME_COIN, 25L, "exchange-1");
    assertEquals(75L, exchanged.shopCoin());
    assertEquals(50L, exchanged.gameCoin());
    WalletService.WalletBalance duplicate = wallet.exchange(
        userId, CurrencyType.SHOP_COIN, CurrencyType.GAME_COIN, 25L, "exchange-1");
    assertEquals(exchanged, duplicate);

    ServiceException insufficient = assertThrows(ServiceException.class, () -> wallet.adjustBalance(
        userId, CurrencyType.SHOP_COIN, -76L, "TEST", "debit-too-large"));
    assertEquals("insufficient_funds", insufficient.code());
  }

  @Test void redeemCodeEnforcesPerUserLimitAndCreditsWalletOnce() {
    long userId = service.setPasswordFromGame(
        UUID.randomUUID(), "Redeem_User", "redeem-secret").userId();
    WalletService wallet = new WalletService(
        database, WalletService.ExchangePolicy::disabled, null, null);
    RedeemCodeService redeem = new RedeemCodeService(database, wallet);
    assertEquals("WELCOME_2026", redeem.createCode(30, 5, 10, 1, null, "welcome_2026"));

    RedeemCodeService.RedeemResult first = redeem.redeem(userId, "WELCOME_2026");
    assertEquals(RedeemCodeService.RedeemStatus.SUCCESS, first.status());
    assertEquals(new WalletService.WalletBalance(30, 5), first.balance());
    RedeemCodeService.RedeemResult duplicate = redeem.redeem(userId, "WELCOME_2026");
    assertEquals(RedeemCodeService.RedeemStatus.ALREADY_USED, duplicate.status());
    assertEquals(first.balance(), duplicate.balance());
  }
}
