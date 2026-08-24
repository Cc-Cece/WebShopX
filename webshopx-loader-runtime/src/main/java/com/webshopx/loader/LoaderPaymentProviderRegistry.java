package com.webshopx.loader;

import com.webshopx.ServiceException;
import com.webshopx.SharedCommerceService;
import com.webshopx.payment.api.PaymentCreateRequest;
import com.webshopx.payment.api.PaymentConfigAccess;
import com.webshopx.payment.api.PaymentConfigField;
import com.webshopx.payment.api.PaymentConfigProblem;
import com.webshopx.payment.api.PaymentConfigSnapshot;
import com.webshopx.payment.api.PaymentConfigUpdateRequest;
import com.webshopx.payment.api.PaymentConfigUpdateResult;
import com.webshopx.payment.api.PaymentConfigurable;
import com.webshopx.payment.api.PaymentNotify;
import com.webshopx.payment.api.PaymentNotifyResult;
import com.webshopx.payment.api.PaymentQueryRequest;
import com.webshopx.payment.api.PaymentStatus;
import com.webshopx.payment.api.WebShopXPaymentApi;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Discovers host-neutral payment providers without requiring Bukkit on Loader servers. */
final class LoaderPaymentProviderRegistry implements AutoCloseable {
  private final String consumerId;
  private final List<WebShopXPaymentApi> providers = new ArrayList<>();
  private final Logger logger;

  private LoaderPaymentProviderRegistry(String consumerId, Logger logger) {
    this.consumerId = consumerId;
    this.logger = logger;
  }

  static LoaderPaymentProviderRegistry discover(
      SharedCommerceService commerce, String serverId, ClassLoader classLoader, Logger logger) {
    ServiceLoader<WebShopXPaymentApi> discovered =
        ServiceLoader.load(WebShopXPaymentApi.class, classLoader);
    try {
      return attach(commerce, serverId, discovered, logger);
    } catch (ServiceConfigurationError failure) {
      Logger resolved = logger == null
          ? Logger.getLogger(LoaderPaymentProviderRegistry.class.getName()) : logger;
      resolved.log(Level.WARNING, "Payment provider discovery failed; payments remain unavailable", failure);
      return new LoaderPaymentProviderRegistry("webshopx-loader-" + serverId, resolved);
    }
  }

  static LoaderPaymentProviderRegistry attach(
      SharedCommerceService commerce,
      String serverId,
      Iterable<WebShopXPaymentApi> candidates,
      Logger logger) {
    Objects.requireNonNull(commerce, "commerce");
    String consumerId = "webshopx-loader-" + serverId;
    LoaderPaymentProviderRegistry registry =
        new LoaderPaymentProviderRegistry(consumerId, logger == null ? Logger.getLogger(
            LoaderPaymentProviderRegistry.class.getName()) : logger);
    for (WebShopXPaymentApi provider : candidates) {
      if (provider == null) continue;
      try {
        String providerId = normalizedProviderId(provider);
        commerce.registerPaymentProvider(new ProviderAdapter(providerId, provider));
        provider.registerListener(
            consumerId, notify -> registry.onNotify(commerce, providerId, notify));
        registry.providers.add(provider);
      } catch (RuntimeException failure) {
        registry.logger.log(Level.WARNING, "Payment provider registration failed", failure);
      }
    }
    return registry;
  }

  int size() {
    return providers.size();
  }

  private PaymentNotifyResult onNotify(
      SharedCommerceService commerce, String providerId, PaymentNotify notify) {
    if (notify == null || notify.getMerchantOrderId() == null
        || notify.getMerchantOrderId().isBlank()) {
      return PaymentNotifyResult.fail("INVALID_NOTIFICATION", "Merchant order ID is required");
    }
    if (notify.getStatus() != PaymentStatus.SUCCESS) {
      return PaymentNotifyResult.fail("PAYMENT_NOT_SUCCESS", "Payment is not successful");
    }
    try {
      commerce.applyPayment(
          new SharedCommerceService.PaymentNotification(
              providerId,
              notify.getProviderOrderId(),
              notify.getMerchantOrderId(),
              notify.getAmountMinor(),
              notify.getCurrency(),
              true,
              notify.getPaidAt() == null ? Instant.now() : notify.getPaidAt()));
      return PaymentNotifyResult.ok("credited");
    } catch (ServiceException failure) {
      return PaymentNotifyResult.fail(failure.code(), failure.getMessage());
    } catch (RuntimeException failure) {
      logger.log(Level.SEVERE, "Payment notification processing failed", failure);
      return PaymentNotifyResult.fail("INTERNAL_ERROR", "Payment processing failed");
    }
  }

  @Override
  public void close() {
    for (WebShopXPaymentApi provider : providers) {
      try {
        provider.unregisterListener(consumerId);
      } catch (RuntimeException failure) {
        logger.log(Level.WARNING, "Payment provider listener unregister failed", failure);
      }
    }
    providers.clear();
  }

  private static String normalizedProviderId(WebShopXPaymentApi provider) {
    String providerId = provider.providerId();
    if (providerId == null || providerId.isBlank()) {
      throw new IllegalArgumentException("payment provider ID is blank");
    }
    return providerId.trim().toLowerCase(Locale.ROOT);
  }

  private record ProviderAdapter(String id, WebShopXPaymentApi provider)
      implements SharedCommerceService.PaymentProvider {
    @Override
    public SharedCommerceService.PaymentSession create(
        String orderId, long amountMinor, String currency, String description) {
      PaymentCreateRequest request = new PaymentCreateRequest();
      request.setMerchantOrderId(orderId);
      request.setAmountMinor(amountMinor);
      request.setCurrency(currency);
      request.setSubject("WebShopX recharge");
      request.setDescription(description);
      var result = provider.createPayment(request);
      if (result == null || !result.isSuccess()) {
        String code = result == null ? "payment_create_failed" : result.getErrorCode();
        String message = result == null ? "Payment provider returned no result" : result.getMessage();
        throw new ServiceException(
            code == null || code.isBlank() ? "payment_create_failed" : code,
            message == null || message.isBlank() ? "Payment creation failed" : message);
      }
      return new SharedCommerceService.PaymentSession(
          result.getProviderOrderId(), result.getPayUrl(), result.getQrCodeUrl(), result.getExpiresAt());
    }

    @Override
    public SharedCommerceService.PaymentNotification query(
        SharedCommerceService.Recharge recharge) {
      PaymentQueryRequest request = new PaymentQueryRequest();
      request.setMerchantOrderId(recharge.orderId());
      request.setProviderOrderId(recharge.providerOrderId());
      var result = provider.queryPayment(request);
      if (result == null || !result.isSuccess()) return null;
      boolean paid = result.isPaid() || result.getStatus() == PaymentStatus.SUCCESS;
      return new SharedCommerceService.PaymentNotification(
          id,
          result.getProviderOrderId() == null
              ? recharge.providerOrderId()
              : result.getProviderOrderId(),
          recharge.orderId(),
          result.getAmountMinor(),
          result.getCurrency(),
          paid,
          result.getPaidAt() == null ? Instant.now() : result.getPaidAt());
    }

    @Override
    public SharedCommerceService.PaymentProviderConfiguration configuration(String locale) {
      if (!(provider instanceof PaymentConfigurable configurable)) return null;
      try {
        var descriptor = configurable.describeConfiguration(locale);
        PaymentConfigSnapshot rawSnapshot = configurable.readConfiguration();
        Map<String, Object> safeValues = new LinkedHashMap<>();
        if (rawSnapshot != null) safeValues.putAll(rawSnapshot.values());
        if (descriptor != null) {
          descriptor.sections().forEach(section -> section.fields().forEach(field -> {
            if (field.access() == PaymentConfigAccess.WRITE_ONLY) safeValues.remove(field.key());
          }));
        }
        PaymentConfigSnapshot safeSnapshot = new PaymentConfigSnapshot(
            safeValues, rawSnapshot == null ? java.util.Set.of() : rawSnapshot.configuredSecrets());
        return new SharedCommerceService.PaymentProviderConfiguration(
            id,
            provider.displayName(),
            descriptor,
            safeSnapshot,
            configurable.supportedConfigurationLocales());
      } catch (RuntimeException failure) {
        throw new ServiceException("payment_config_read_failed", message(failure));
      }
    }

    @Override
    public PaymentConfigUpdateResult updateConfiguration(PaymentConfigUpdateRequest request) {
      if (!(provider instanceof PaymentConfigurable configurable)) {
        throw new ServiceException(
            "payment_config_unsupported", "Payment provider does not expose configuration");
      }
      try {
        var descriptor = configurable.describeConfiguration();
        var fields = new LinkedHashMap<String, PaymentConfigField>();
        if (descriptor != null) {
          descriptor.sections().forEach(
              section -> section.fields().forEach(field -> fields.put(field.key(), field)));
        }
        var problems = new ArrayList<PaymentConfigProblem>();
        request.changes().keySet().forEach(key -> {
          PaymentConfigField field = fields.get(key);
          if (field == null || field.access() == PaymentConfigAccess.READ_ONLY) {
            problems.add(new PaymentConfigProblem(
                key, "unknown_or_read_only", "Setting is not writable"));
          }
        });
        request.clearedSecrets().forEach(key -> {
          PaymentConfigField field = fields.get(key);
          if (field == null || field.access() != PaymentConfigAccess.WRITE_ONLY) {
            problems.add(new PaymentConfigProblem(
                key, "not_a_secret", "Setting is not a write-only secret"));
          }
        });
        if (!problems.isEmpty()) {
          return PaymentConfigUpdateResult.rejected("Configuration was not changed", problems);
        }
        PaymentConfigUpdateResult result = configurable.updateConfiguration(request);
        if (result == null) {
          throw new ServiceException(
              "payment_config_update_failed", "Payment provider returned no result");
        }
        return result;
      } catch (ServiceException failure) {
        throw failure;
      } catch (RuntimeException failure) {
        throw new ServiceException("payment_config_update_failed", message(failure));
      }
    }

    private static String message(RuntimeException failure) {
      return failure.getMessage() == null || failure.getMessage().isBlank()
          ? failure.getClass().getSimpleName()
          : failure.getMessage();
    }
  }
}
