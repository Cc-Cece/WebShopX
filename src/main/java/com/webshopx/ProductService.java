package com.webshopx;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

class ProductService {
  private final DatabaseManager databaseManager;

  ProductService(DatabaseManager databaseManager) {
    this.databaseManager = databaseManager;
  }

  void upsertSeeds(List<PluginSettings.ProductSeed> seeds) {
    if (seeds.isEmpty()) {
      return;
    }
    databaseManager.inTransaction(connection -> {
      for (PluginSettings.ProductSeed seed : seeds) {
        upsertSeed(connection, seed);
      }
      return null;
    });
  }

  List<ProductView> listActiveProducts() {
    return databaseManager.withConnection(connection -> {
      String sql = """
          SELECT id, sku, title, currency, price, command_template
          FROM products
          WHERE active = TRUE
          ORDER BY id ASC
          """;
      List<ProductView> products = new ArrayList<>();
      try (PreparedStatement statement = connection.prepareStatement(sql);
           ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          products.add(readProduct(resultSet));
        }
      }
      return products;
    });
  }

  @SuppressFBWarnings(
      value = "SQL_INJECTION_JDBC",
      justification = "Lock clause is selected from a fixed boolean branch")
  ProductView readActiveProduct(Connection connection, long productId, boolean forUpdate)
      throws SQLException {
    String lockClause = forUpdate ? " FOR UPDATE" : "";
    String sql = """
        SELECT id, sku, title, currency, price, command_template
        FROM products
        WHERE id = ? AND active = TRUE
        """ + lockClause;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, productId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new ServiceException("product_missing", "Product is not available");
        }
        return readProduct(resultSet);
      }
    }
  }

  private void upsertSeed(Connection connection, PluginSettings.ProductSeed seed) throws SQLException {
    String sql = """
        INSERT INTO products (sku, title, currency, price, command_template, active)
        VALUES (?, ?, ?, ?, ?, TRUE)
        ON DUPLICATE KEY UPDATE
          title = VALUES(title),
          currency = VALUES(currency),
          price = VALUES(price),
          command_template = VALUES(command_template)
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, seed.sku());
      statement.setString(2, seed.title());
      statement.setString(3, seed.currency().name());
      statement.setLong(4, seed.price());
      statement.setString(5, seed.commandTemplate());
      statement.executeUpdate();
    }
  }

  private ProductView readProduct(ResultSet resultSet) throws SQLException {
    return new ProductView(
        resultSet.getLong("id"),
        resultSet.getString("sku"),
        resultSet.getString("title"),
        CurrencyType.valueOf(resultSet.getString("currency")),
        resultSet.getLong("price"),
        resultSet.getString("command_template"));
  }

  record ProductView(
      long id,
      String sku,
      String title,
      CurrencyType currency,
      long price,
      String commandTemplate) {
  }
}
