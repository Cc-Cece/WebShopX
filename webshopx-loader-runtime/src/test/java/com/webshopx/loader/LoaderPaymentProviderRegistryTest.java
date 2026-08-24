package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.SharedCommerceService;
import com.webshopx.payment.api.PaymentCreateRequest;
import com.webshopx.payment.api.PaymentCreateResult;
import com.webshopx.payment.api.PaymentConfigAccess;
import com.webshopx.payment.api.PaymentConfigApplyMode;
import com.webshopx.payment.api.PaymentConfigDescriptor;
import com.webshopx.payment.api.PaymentConfigField;
import com.webshopx.payment.api.PaymentConfigFieldType;
import com.webshopx.payment.api.PaymentConfigSection;
import com.webshopx.payment.api.PaymentConfigSnapshot;
import com.webshopx.payment.api.PaymentConfigUpdateRequest;
import com.webshopx.payment.api.PaymentConfigUpdateResult;
import com.webshopx.payment.api.PaymentConfigurable;
import com.webshopx.payment.api.PaymentListener;
import com.webshopx.payment.api.PaymentMethod;
import com.webshopx.payment.api.PaymentNotify;
import com.webshopx.payment.api.PaymentQueryRequest;
import com.webshopx.payment.api.PaymentQueryResult;
import com.webshopx.payment.api.PaymentStatus;
import com.webshopx.payment.api.WebShopXPaymentApi;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoaderPaymentProviderRegistryTest {
  @TempDir Path temporaryDirectory;

  @Test
  void hostNeutralProviderCreatesAndCreditsExactlyOnceFromTrustedNotification() {
    try (SharedDatabaseRuntime runtime = SharedDatabaseRuntime.start(temporaryDirectory)) {
      FixtureProvider provider = new FixtureProvider();
      try (LoaderPaymentProviderRegistry registry =
          LoaderPaymentProviderRegistry.attach(
              runtime.commerce(), "payment-node", List.of(provider), Logger.getAnonymousLogger())) {
        assertEquals(1, registry.size());
        var configuration = runtime.commerce()
            .paymentProviderConfiguration("fixture-loader", "zh-CN").orElseThrow();
        assertEquals("Fixture Loader Pay", configuration.displayName());
        assertEquals("https://pay.example", configuration.snapshot().values().get("endpoint"));
        assertEquals(Set.of("secret"), configuration.snapshot().configuredSecrets());
        assertTrue(!configuration.snapshot().values().containsKey("secret"));
        assertEquals(
            "REJECTED",
            runtime.commerce().updatePaymentProviderConfiguration(
                "fixture-loader",
                new PaymentConfigUpdateRequest(Map.of("readOnly", "changed"), Set.of()))
                .status().name());
        assertEquals(
            "APPLIED",
            runtime.commerce().updatePaymentProviderConfiguration(
                "fixture-loader",
                new PaymentConfigUpdateRequest(
                    Map.of("endpoint", "https://new.example", "secret", "new-secret"), Set.of()))
                .status().name());
        assertEquals("https://new.example", provider.endpoint);
        assertEquals("new-secret", provider.secret);
        UUID player = UUID.randomUUID();
        long user = runtime.authentication().setPasswordFromGame(player, "PayUser", "pay-secret")
            .userId();
        SharedCommerceService.Recharge recharge =
            runtime.commerce().createRecharge(
                new SharedCommerceService.RechargeRequest(
                    user, player, 500, "CNY", 50, "fixture-loader", "pay-create-1", "test"));
        assertEquals("PAYING", recharge.status());
        assertTrue(recharge.payUrl().startsWith("https://pay.example/"));

        PaymentNotify notify = new PaymentNotify();
        notify.setMerchantOrderId(recharge.orderId());
        notify.setProviderOrderId(recharge.providerOrderId());
        notify.setStatus(PaymentStatus.SUCCESS);
        notify.setAmountMinor(500);
        notify.setCurrency("CNY");
        notify.setPaidAt(Instant.now());
        assertTrue(provider.listener.onPaymentNotify(notify).isSuccess());
        assertTrue(provider.listener.onPaymentNotify(notify).isSuccess());
        assertEquals(50, runtime.wallet().getBalance(user).shopCoin());
        assertEquals(0, runtime.wallet().getBalance(user).gameCoin());

        SharedCommerceService.Recharge recovered =
            runtime.commerce().createRecharge(
                new SharedCommerceService.RechargeRequest(
                    user, player, 250, "CNY", 25, "fixture-loader", "pay-create-2", "recover"));
        assertEquals("CREDITED", runtime.commerce().reconcileRecharge(user, recovered.orderId()).status());
        assertEquals("CREDITED", runtime.commerce().reconcileRecharge(user, recovered.orderId()).status());
        assertEquals(75, runtime.wallet().getBalance(user).shopCoin());
      }
      assertTrue(provider.listener == null);
    }
  }

  private static final class FixtureProvider implements WebShopXPaymentApi, PaymentConfigurable {
    private PaymentListener listener;
    private String latestOrderId;
    private String latestProviderOrderId;
    private long latestAmount;
    private String latestCurrency;
    private String endpoint = "https://pay.example";
    private String secret = "stored-secret";

    @Override
    public String providerId() {
      return "fixture-loader";
    }

    @Override
    public String displayName() {
      return "Fixture Loader Pay";
    }

    @Override
    public Set<PaymentMethod> supportedMethods() {
      return Set.of(PaymentMethod.ALIPAY);
    }

    @Override
    public PaymentCreateResult createPayment(PaymentCreateRequest request) {
      PaymentCreateResult result = new PaymentCreateResult();
      result.setSuccess(true);
      result.setMerchantOrderId(request.getMerchantOrderId());
      result.setProviderOrderId("provider-" + request.getMerchantOrderId());
      result.setStatus(PaymentStatus.PAYING);
      result.setPayUrl("https://pay.example/" + request.getMerchantOrderId());
      result.setExpiresAt(Instant.now().plusSeconds(600));
      latestOrderId = request.getMerchantOrderId();
      latestProviderOrderId = result.getProviderOrderId();
      latestAmount = request.getAmountMinor();
      latestCurrency = request.getCurrency();
      return result;
    }

    @Override
    public PaymentQueryResult queryPayment(PaymentQueryRequest request) {
      PaymentQueryResult result = new PaymentQueryResult();
      result.setSuccess(latestOrderId.equals(request.getMerchantOrderId()));
      result.setMerchantOrderId(latestOrderId);
      result.setProviderOrderId(latestProviderOrderId);
      result.setStatus(PaymentStatus.SUCCESS);
      result.setPaid(true);
      result.setAmountMinor(latestAmount);
      result.setCurrency(latestCurrency);
      result.setPaidAt(Instant.now());
      return result;
    }

    @Override
    public void registerListener(String consumerId, PaymentListener listener) {
      this.listener = listener;
    }

    @Override
    public void unregisterListener(String consumerId) {
      listener = null;
    }

    @Override
    public PaymentConfigDescriptor describeConfiguration() {
      return new PaymentConfigDescriptor(
          1,
          List.of(new PaymentConfigSection(
              "payment",
              "Payment",
              "Fixture settings",
              List.of(
                  field("endpoint", PaymentConfigFieldType.URL, PaymentConfigAccess.READ_WRITE),
                  field("secret", PaymentConfigFieldType.SECRET, PaymentConfigAccess.WRITE_ONLY),
                  field("readOnly", PaymentConfigFieldType.TEXT, PaymentConfigAccess.READ_ONLY)))));
    }

    @Override
    public Set<String> supportedConfigurationLocales() {
      return Set.of("en-US", "zh-CN");
    }

    @Override
    public PaymentConfigSnapshot readConfiguration() {
      return new PaymentConfigSnapshot(
          Map.of("endpoint", endpoint, "readOnly", "provider-owned", "secret", secret),
          secret.isBlank() ? Set.of() : Set.of("secret"));
    }

    @Override
    public PaymentConfigUpdateResult updateConfiguration(PaymentConfigUpdateRequest request) {
      if (request.changes().containsKey("endpoint")) {
        endpoint = String.valueOf(request.changes().get("endpoint"));
      }
      if (request.changes().containsKey("secret")) {
        secret = String.valueOf(request.changes().get("secret"));
      }
      if (request.clearedSecrets().contains("secret")) secret = "";
      return PaymentConfigUpdateResult.applied("updated");
    }

    private static PaymentConfigField field(
        String key, PaymentConfigFieldType type, PaymentConfigAccess access) {
      return new PaymentConfigField(
          key,
          type,
          key,
          "fixture",
          access,
          PaymentConfigApplyMode.IMMEDIATE,
          false,
          null,
          null,
          null,
          List.of());
    }
  }
}
