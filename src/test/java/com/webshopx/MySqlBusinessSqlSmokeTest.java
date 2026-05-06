package com.webshopx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MySqlBusinessSqlSmokeTest {
  @Container
  private static final MariaDBContainer<?> MARIADB =
      new MariaDBContainer<>("mariadb:11.4")
          .withDatabaseName("webshop")
          .withUsername("webshop")
          .withPassword("webshop");

  @Test
  void mysqlProviderCriticalSqlShouldExecute() throws Exception {
    SqlProvider sql = SqlProvider.forType(DbType.MYSQL);
    try (Connection connection =
        DriverManager.getConnection(MARIADB.getJdbcUrl(), MARIADB.getUsername(), MARIADB.getPassword())) {
      createSchema(connection);

      try (PreparedStatement st = connection.prepareStatement(sql.insertWalletIfMissingSql())) {
        st.setLong(1, 1L);
        st.executeUpdate();
      }
      try (PreparedStatement st = connection.prepareStatement(sql.insertWalletIfMissingSql())) {
        st.setLong(1, 1L);
        st.executeUpdate();
      }

      try (PreparedStatement st = connection.prepareStatement(sql.upsertRuntimeConfigSql())) {
        st.setString(1, "market_runtime");
        st.setString(2, "{\"max\":1}");
        st.executeUpdate();
      }
      try (PreparedStatement st = connection.prepareStatement(sql.upsertRuntimeConfigSql())) {
        st.setString(1, "market_runtime");
        st.setString(2, "{\"max\":2}");
        st.executeUpdate();
      }

      try (PreparedStatement st = connection.prepareStatement(sql.upsertMarketTagSql())) {
        st.setString(1, "default");
        st.setString(2, "Default");
        st.setBoolean(3, true);
        st.setInt(4, 100);
        st.executeUpdate();
      }
      try (PreparedStatement st = connection.prepareStatement(sql.upsertMarketTagSql())) {
        st.setString(1, "default");
        st.setString(2, "Default-2");
        st.setBoolean(3, false);
        st.setInt(4, 90);
        st.executeUpdate();
      }

      try (PreparedStatement st = connection.prepareStatement(sql.upsertRedeemUsageSql())) {
        st.setString(1, "R1");
        st.setLong(2, 1L);
        st.executeUpdate();
      }
      try (PreparedStatement st = connection.prepareStatement(sql.upsertRedeemUsageSql())) {
        st.setString(1, "R1");
        st.setLong(2, 1L);
        st.executeUpdate();
      }

      String ttlSql =
          "SELECT server_id FROM player_presence WHERE mc_uuid = ? AND updated_at >= "
              + sql.currentTimestampMinusSecondsExpr()
              + " LIMIT 1";
      try (PreparedStatement st = connection.prepareStatement(sql.upsertPlayerPresenceOnlineSql())) {
        st.setString(1, "00000000-0000-0000-0000-000000000001");
        st.setString(2, "PlayerA");
        st.setString(3, "s1");
        st.executeUpdate();
      }
      try (PreparedStatement st = connection.prepareStatement(ttlSql)) {
        st.setString(1, "00000000-0000-0000-0000-000000000001");
        st.setInt(2, 120);
        try (ResultSet rs = st.executeQuery()) {
          assertTrue(rs.next());
          assertEquals("s1", rs.getString("server_id"));
        }
      }

      try (PreparedStatement st =
          connection.prepareStatement("SELECT version FROM runtime_config WHERE config_key='market_runtime'");
          ResultSet rs = st.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(2L, rs.getLong("version"));
      }
    }
  }

  private static void createSchema(Connection c) throws Exception {
    execute(
        c,
        "CREATE TABLE web_users (id BIGINT PRIMARY KEY, username VARCHAR(32), bound_uuid CHAR(36))");
    execute(
        c,
        "CREATE TABLE wallets (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT UNIQUE, shop_coin BIGINT DEFAULT 0, game_coin BIGINT DEFAULT 0)");
    execute(
        c,
        "CREATE TABLE wallet_ledger (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, wallet_id BIGINT, currency VARCHAR(24), delta BIGINT, biz_type VARCHAR(64), biz_id VARCHAR(128), UNIQUE KEY uniq_wallet_biz (wallet_id,biz_type,biz_id))");
    execute(
        c,
        "CREATE TABLE runtime_config (config_key VARCHAR(64) PRIMARY KEY, config_value TEXT, version BIGINT NOT NULL DEFAULT 1, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
    execute(
        c,
        "CREATE TABLE webshop_meta (meta_key VARCHAR(64) PRIMARY KEY, meta_value TEXT)");
    execute(
        c,
        "CREATE TABLE market_tags (code VARCHAR(64) PRIMARY KEY, display_name VARCHAR(64), enabled BOOLEAN, priority INT)");
    execute(
        c,
        "CREATE TABLE redeem_usage (code VARCHAR(32), user_id BIGINT, use_count INT, used_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(code,user_id))");
    execute(
        c,
        "CREATE TABLE player_presence (mc_uuid CHAR(36) PRIMARY KEY, username VARCHAR(32), server_id VARCHAR(64), online BOOLEAN, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
  }

  private static void execute(Connection c, String sql) throws Exception {
    try (PreparedStatement st = c.prepareStatement(sql)) {
      st.execute();
    }
  }
}
