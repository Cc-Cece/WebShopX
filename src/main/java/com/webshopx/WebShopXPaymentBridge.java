package com.webshopx;

import com.webshopx.payment.api.PaymentCreateRequest;
import com.webshopx.payment.api.PaymentCreateResult;
import com.webshopx.payment.api.PaymentListener;
import com.webshopx.payment.api.PaymentMethod;
import com.webshopx.payment.api.PaymentNotify;
import com.webshopx.payment.api.PaymentNotifyResult;
import com.webshopx.payment.api.PaymentQueryRequest;
import com.webshopx.payment.api.PaymentQueryResult;
import com.webshopx.payment.api.PaymentStatus;
import com.webshopx.payment.api.WebShopXPaymentApi;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

final class WebShopXPaymentBridge {
  private static final String CONSUMER_ID = "webshopx";
  private static final String LEGACY_PROVIDER_ID = "yupay";

  private final JavaPlugin plugin;
  private final YuPayBridge legacyYuPayBridge;
  private final java.util.function.Supplier<PluginSettings> settingsSupplier;
  private ActiveRegistration activeRegistration;

  WebShopXPaymentBridge(
      JavaPlugin plugin,
      YuPayBridge legacyYuPayBridge,
      java.util.function.Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.legacyYuPayBridge = legacyYuPayBridge;
    this.settingsSupplier = settingsSupplier;
  }

  boolean isAvailable() {
    try {
      ProviderHandle handle = resolveProvider(false);
      return handle != null;
    } catch (ServiceException exception) {
      return false;
    }
  }

  Optional<String> activeProviderId() {
    if (activeRegistration != null) {
      return Optional.of(activeRegistration.providerId());
    }
    try {
      ProviderHandle handle = resolveProvider(false);
      return handle == null ? Optional.empty() : Optional.of(handle.providerId());
    } catch (ServiceException exception) {
      return Optional.empty();
    }
  }

  Optional<String> configuredProviderId() {
    String provider = settingsSupplier.get().paymentSettings().provider();
    return isBlank(provider) ? Optional.empty() : Optional.of(provider);
  }

  CreatePaymentResultData createPayment(CreatePaymentRequestData request) {
    ProviderHandle handle = requireProvider();
    if (handle.kind() == ProviderKind.LEGACY_YUPAY) {
      return createLegacyPayment(request);
    }

    PaymentCreateRequest apiRequest = new PaymentCreateRequest();
    apiRequest.setMerchantOrderId(request.merchantOrderId());
    apiRequest.setUserId(request.userId());
    apiRequest.setPlayerUuid(request.playerUuid());
    apiRequest.setAmountMinor(request.amountMinor());
    apiRequest.setCurrency(request.currency());
    apiRequest.setSubject(request.subject());
    apiRequest.setDescription(request.description());
    apiRequest.setPreferredMethod(request.preferredMethod());
    apiRequest.setReturnUrl(request.returnUrl());
    apiRequest.setNotifyUrl(request.notifyUrl());
    apiRequest.setExpiresAt(request.expiresAt());
    apiRequest.setMetadata(request.metadata());

    PaymentCreateResult result;
    try {
      result = handle.api().createPayment(apiRequest);
    } catch (RuntimeException exception) {
      throw new ServiceException("payment_api_error", exceptionMessage(exception));
    }
    if (result == null) {
      return CreatePaymentResultData.fail(
          handle.providerId(),
          request.merchantOrderId(),
          "payment_create_failed",
          "Payment provider returned no result");
    }
    return new CreatePaymentResultData(
        handle.providerId(),
        result.isSuccess(),
        result.getMerchantOrderId(),
        result.getProviderOrderId(),
        statusName(result.getStatus()),
        methodName(result.getMethod(), result.getMethodCode()),
        result.getPayUrl(),
        result.getQrCodeUrl(),
        result.getExpiresAt(),
        result.getErrorCode(),
        result.getMessage());
  }

  QueryPaymentResultData queryPayment(String merchantOrderId, String providerOrderId) {
    ProviderHandle handle = requireProvider();
    if (handle.kind() == ProviderKind.LEGACY_YUPAY) {
      if (isBlank(providerOrderId)) {
        throw new ServiceException("payment_query_failed", "providerOrderId is required for legacy YuPay");
      }
      YuPayBridge.QueryPaymentResultData result = legacyYuPayBridge.queryPayment(providerOrderId);
      return new QueryPaymentResultData(
          LEGACY_PROVIDER_ID,
          result.success(),
          result.merchantOrderId(),
          result.providerOrderId(),
          result.status(),
          result.paid(),
          result.amountMinor(),
          result.currency(),
          result.method(),
          result.payTime(),
          result.errorCode(),
          result.message());
    }

    PaymentQueryRequest request = new PaymentQueryRequest();
    request.setMerchantOrderId(merchantOrderId);
    request.setProviderOrderId(providerOrderId);
    PaymentQueryResult result;
    try {
      result = handle.api().queryPayment(request);
    } catch (RuntimeException exception) {
      throw new ServiceException("payment_api_error", exceptionMessage(exception));
    }
    if (result == null) {
      return QueryPaymentResultData.fail(
          handle.providerId(),
          merchantOrderId,
          providerOrderId,
          "payment_query_failed",
          "Payment provider returned no result");
    }
    return new QueryPaymentResultData(
        handle.providerId(),
        result.isSuccess(),
        result.getMerchantOrderId(),
        result.getProviderOrderId(),
        statusName(result.getStatus()),
        result.isPaid(),
        result.getAmountMinor(),
        result.getCurrency(),
        methodName(result.getMethod(), result.getMethodCode()),
        result.getPaidAt(),
        result.getErrorCode(),
        result.getMessage());
  }

  boolean registerPaymentListener(Function<PaymentNotifyData, NotifyResultData> handler) {
    ProviderHandle handle = resolveProvider(true);
    if (handle == null) {
      return false;
    }
    if (handle.kind() == ProviderKind.LEGACY_YUPAY) {
      boolean registered = legacyYuPayBridge.registerPaymentListener(notify -> {
        NotifyResultData result = handler.apply(toBridgeNotify(notify));
        return result.success()
            ? YuPayBridge.NotifyResultData.ok(result.message())
            : YuPayBridge.NotifyResultData.fail(result.code(), result.message());
      });
      if (registered) {
        activeRegistration = new ActiveRegistration(ProviderKind.LEGACY_YUPAY, LEGACY_PROVIDER_ID, null);
        plugin.getLogger().warning(
            "Legacy YuPay API detected as payment fallback; please migrate to WebShopXPaymentApi.");
      }
      return registered;
    }

    PaymentListener listener = notify -> {
      NotifyResultData result = handler.apply(toBridgeNotify(notify));
      return result.success()
          ? PaymentNotifyResult.ok(result.message())
          : PaymentNotifyResult.fail(result.code(), result.message());
    };
    try {
      handle.api().registerListener(CONSUMER_ID, listener);
      activeRegistration = new ActiveRegistration(ProviderKind.API, handle.providerId(), handle.api());
      return true;
    } catch (RuntimeException exception) {
      throw new ServiceException("payment_api_error", exceptionMessage(exception));
    }
  }

  void unregisterPaymentListener() {
    ActiveRegistration registration = activeRegistration;
    activeRegistration = null;
    if (registration == null) {
      legacyYuPayBridge.unregisterPaymentListener();
      return;
    }
    if (registration.kind() == ProviderKind.LEGACY_YUPAY) {
      legacyYuPayBridge.unregisterPaymentListener();
      return;
    }
    try {
      registration.api().unregisterListener(CONSUMER_ID);
    } catch (RuntimeException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to unregister payment listener.", exception);
    }
  }

  private CreatePaymentResultData createLegacyPayment(CreatePaymentRequestData request) {
    YuPayBridge.CreatePaymentResultData result = legacyYuPayBridge.createPayment(
        new YuPayBridge.CreatePaymentRequestData(
            request.merchantOrderId(),
            request.userId(),
            request.playerUuid(),
            request.amountMinor(),
            request.currency(),
            request.subject(),
            request.description(),
            request.returnUrl(),
            request.metadata()));
    return new CreatePaymentResultData(
        LEGACY_PROVIDER_ID,
        result.success(),
        result.merchantOrderId(),
        result.providerOrderId(),
        result.success() ? "PAYING" : "FAILED",
        null,
        result.payUrl(),
        result.qrCodeUrl(),
        result.expireTime(),
        result.errorCode(),
        result.message());
  }

  private ProviderHandle requireProvider() {
    ProviderHandle handle = resolveProvider(true);
    if (handle == null) {
      throw new ServiceException("payment_unavailable", "Payment service is not available");
    }
    return handle;
  }

  private ProviderHandle resolveProvider(boolean logAutoSelection) {
    Collection<RegisteredServiceProvider<WebShopXPaymentApi>> registrations =
        plugin.getServer().getServicesManager().getRegistrations(WebShopXPaymentApi.class);
    String configuredProvider = settingsSupplier.get().paymentSettings().provider();
    if (!registrations.isEmpty()) {
      ProviderHandle first = null;
      for (RegisteredServiceProvider<WebShopXPaymentApi> registration : registrations) {
        WebShopXPaymentApi api = registration.getProvider();
        if (api == null || isBlank(api.providerId())) {
          continue;
        }
        ProviderHandle handle = new ProviderHandle(
            ProviderKind.API,
            api.providerId().trim().toLowerCase(Locale.ROOT),
            api);
        if (first == null) {
          first = handle;
        }
        if (!isBlank(configuredProvider)
            && handle.providerId().equals(configuredProvider.trim().toLowerCase(Locale.ROOT))) {
          return handle;
        }
      }
      if (!isBlank(configuredProvider)) {
        throw new ServiceException(
            "payment_provider_not_found",
            "Configured payment provider was not found: " + configuredProvider);
      }
      if (first != null && logAutoSelection) {
        plugin.getLogger().info("Using payment provider: " + first.providerId());
      }
      return first;
    }
    if (legacyYuPayBridge.isAvailable()) {
      return new ProviderHandle(ProviderKind.LEGACY_YUPAY, LEGACY_PROVIDER_ID, null);
    }
    return null;
  }

  private PaymentNotifyData toBridgeNotify(PaymentNotify notify) {
    if (notify == null) {
      return null;
    }
    return new PaymentNotifyData(
        notify.getMerchantOrderId(),
        notify.getProviderOrderId(),
        statusName(notify.getStatus()),
        notify.getAmountMinor(),
        notify.getCurrency(),
        methodName(notify.getMethod(), notify.getMethodCode()),
        notify.getPaidAt(),
        notify.getExtra());
  }

  private PaymentNotifyData toBridgeNotify(YuPayBridge.PaymentNotifyData notify) {
    if (notify == null) {
      return null;
    }
    return new PaymentNotifyData(
        notify.merchantOrderId(),
        notify.providerOrderId(),
        notify.status(),
        notify.amountMinor(),
        notify.currency(),
        notify.method(),
        notify.payTime(),
        notify.extra());
  }

  private String statusName(PaymentStatus status) {
    return status == null ? "UNKNOWN" : status.name();
  }

  private String methodName(PaymentMethod method, String methodCode) {
    if (!isBlank(methodCode)) {
      return methodCode.trim();
    }
    if (method == null || method == PaymentMethod.AUTO) {
      return null;
    }
    return method.name();
  }

  private String exceptionMessage(RuntimeException exception) {
    return exception.getMessage() == null ? exception.toString() : exception.getMessage();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  record CreatePaymentRequestData(
      String merchantOrderId,
      String userId,
      UUID playerUuid,
      long amountMinor,
      String currency,
      String subject,
      String description,
      PaymentMethod preferredMethod,
      String returnUrl,
      String notifyUrl,
      Instant expiresAt,
      Map<String, String> metadata) {
  }

  record CreatePaymentResultData(
      String providerId,
      boolean success,
      String merchantOrderId,
      String providerOrderId,
      String status,
      String method,
      String payUrl,
      String qrCodeUrl,
      Instant expireTime,
      String errorCode,
      String message) {
    static CreatePaymentResultData fail(
        String providerId,
        String merchantOrderId,
        String errorCode,
        String message) {
      return new CreatePaymentResultData(
          providerId,
          false,
          merchantOrderId,
          null,
          "FAILED",
          null,
          null,
          null,
          null,
          errorCode,
          message);
    }
  }

  record QueryPaymentResultData(
      String providerId,
      boolean success,
      String merchantOrderId,
      String providerOrderId,
      String status,
      boolean paid,
      long amountMinor,
      String currency,
      String method,
      Instant payTime,
      String errorCode,
      String message) {
    static QueryPaymentResultData fail(
        String providerId,
        String merchantOrderId,
        String providerOrderId,
        String errorCode,
        String message) {
      return new QueryPaymentResultData(
          providerId,
          false,
          merchantOrderId,
          providerOrderId,
          "UNKNOWN",
          false,
          0L,
          null,
          null,
          null,
          errorCode,
          message);
    }
  }

  record PaymentNotifyData(
      String merchantOrderId,
      String providerOrderId,
      String status,
      long amountMinor,
      String currency,
      String method,
      Instant payTime,
      Map<String, String> extra) {
  }

  record NotifyResultData(boolean success, String code, String message) {
    static NotifyResultData ok(String message) {
      return new NotifyResultData(true, "OK", message);
    }

    static NotifyResultData fail(String code, String message) {
      return new NotifyResultData(false, code, message);
    }
  }

  private enum ProviderKind {
    API,
    LEGACY_YUPAY
  }

  private record ProviderHandle(
      ProviderKind kind,
      String providerId,
      WebShopXPaymentApi api) {
  }

  private record ActiveRegistration(
      ProviderKind kind,
      String providerId,
      WebShopXPaymentApi api) {
  }
}
