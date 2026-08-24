package com.webshopx.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.webshopx.SharedCommerceService;
import com.webshopx.payment.api.PaymentCreateRequest;
import com.webshopx.payment.api.PaymentCreateResult;
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

  private static final class FixtureProvider implements WebShopXPaymentApi {
    private PaymentListener listener;
    private String latestOrderId;
    private String latestProviderOrderId;
    private long latestAmount;
    private String latestCurrency;

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
  }
}
