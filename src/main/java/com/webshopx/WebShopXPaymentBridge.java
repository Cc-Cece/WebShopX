package com.webshopx;

import com.webshopx.payment.api.PaymentCreateRequest;
import com.webshopx.payment.api.PaymentConfigDescriptor;
import com.webshopx.payment.api.PaymentConfigAccess;
import com.webshopx.payment.api.PaymentConfigField;
import com.webshopx.payment.api.PaymentConfigProblem;
import com.webshopx.payment.api.PaymentConfigSnapshot;
import com.webshopx.payment.api.PaymentConfigUpdateRequest;
import com.webshopx.payment.api.PaymentConfigUpdateResult;
import com.webshopx.payment.api.PaymentConfigurable;
import com.webshopx.payment.api.PaymentCreateResult;
import com.webshopx.payment.api.PaymentListener;
import com.webshopx.payment.api.PaymentMethod;
import com.webshopx.payment.api.PaymentNotify;
import com.webshopx.payment.api.PaymentNotifyResult;
import com.webshopx.payment.api.PaymentQueryRequest;
import com.webshopx.payment.api.PaymentQueryResult;
import com.webshopx.payment.api.PaymentStatus;
import com.webshopx.payment.api.RechargeRatePolicy;
import com.webshopx.payment.api.SettlementAmountMode;
import com.webshopx.payment.api.WebShopXPaymentApi;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private final Map<String, ActiveRegistration> activeRegistrations = new LinkedHashMap<>();

  WebShopXPaymentBridge(
      JavaPlugin plugin,
      YuPayBridge legacyYuPayBridge,
      java.util.function.Supplier<PluginSettings> settingsSupplier) {
    this.plugin = plugin;
    this.legacyYuPayBridge = legacyYuPayBridge;
    this.settingsSupplier = settingsSupplier;
  }

  boolean isAvailable() {
    return !providerHandles().isEmpty();
  }

  Optional<String> activeProviderId() {
    return activeRegistrations.keySet().stream().findFirst();
  }

  Optional<String> configuredProviderId() {
    return Optional.empty();
  }

  PaymentProviderInfo providerInfo() {
    List<PaymentProviderInfo> providers = providerInfos();
    return providers.isEmpty()
        ? new PaymentProviderInfo(false, null, null, Set.of(), Set.of(), true,
            SettlementAmountMode.WEBSHOPX_CALCULATED.name(), "")
        : providers.get(0);
  }

  List<PaymentProviderInfo> providerInfos() {
    List<PaymentProviderInfo> result = new ArrayList<>();
    for (ProviderHandle handle : providerHandles().values()) {
      result.add(providerInfo(handle));
    }
    return Collections.unmodifiableList(result);
  }

  PaymentProviderInfo providerInfo(String providerId) {
    return providerInfo(requireProvider(providerId));
  }

  private PaymentProviderInfo providerInfo(ProviderHandle handle) {
    if (handle == null) {
      return new PaymentProviderInfo(false, null, null, Set.of(), Set.of(), true,
          SettlementAmountMode.WEBSHOPX_CALCULATED.name(), "");
    }
    if (handle.kind() == ProviderKind.LEGACY_YUPAY) {
      return new PaymentProviderInfo(true, LEGACY_PROVIDER_ID, "YuPay",
          Set.of(PaymentMethod.ALIPAY, PaymentMethod.WECHAT), Set.of("CNY"), true,
          SettlementAmountMode.WEBSHOPX_CALCULATED.name(), "");
    }
    Set<PaymentMethod> methods = new LinkedHashSet<>();
    Set<String> currencies = new LinkedHashSet<>();
    RechargeRatePolicy ratePolicy = RechargeRatePolicy.webShopXManaged();
    try {
      if (handle.api().supportedMethods() != null) {
        methods.addAll(handle.api().supportedMethods());
      }
    } catch (RuntimeException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to read payment provider methods.", exception);
    }
    try {
      if (handle.api().supportedCurrencies() != null) {
        for (String currency : handle.api().supportedCurrencies()) {
          if (currency != null && !currency.isBlank()) {
            currencies.add(currency.trim().toUpperCase(Locale.ROOT));
          }
        }
      }
    } catch (RuntimeException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to read payment provider currencies.", exception);
    }
    try {
      RechargeRatePolicy providedPolicy = handle.api().rechargeRatePolicy();
      if (providedPolicy != null) {
        ratePolicy = providedPolicy;
      }
    } catch (RuntimeException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to read payment provider recharge rate policy.", exception);
    }
    return new PaymentProviderInfo(
        true,
        handle.providerId(),
        handle.api().displayName(),
        Collections.unmodifiableSet(methods),
        Collections.unmodifiableSet(currencies),
        ratePolicy.editable(),
        ratePolicy.settlementAmountMode().name(),
        ratePolicy.configurationNotice());
  }

  CreatePaymentResultData createPayment(String providerId, CreatePaymentRequestData request) {
    ProviderHandle handle = requireProvider(providerId);
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
    apiRequest.setMethodCode(request.methodCode());
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

  QueryPaymentResultData queryPayment(String providerId, String merchantOrderId, String providerOrderId) {
    ProviderHandle handle = requireProvider(providerId);
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

  Optional<PaymentProviderConfiguration> providerConfiguration(String providerId, String locale) {
    ProviderHandle handle = requireProvider(providerId);
    if (handle == null || handle.kind() != ProviderKind.API
        || !(handle.api() instanceof PaymentConfigurable configurable)) {
      return Optional.empty();
    }
    try {
      return Optional.of(new PaymentProviderConfiguration(
          handle.providerId(), handle.api().displayName(),
          configurable.describeConfiguration(locale), configurable.readConfiguration(),
          configurable.supportedConfigurationLocales()));
    } catch (RuntimeException exception) {
      throw new ServiceException("payment_config_read_failed", exceptionMessage(exception));
    }
  }

  PaymentConfigUpdateResult updateProviderConfiguration(String providerId, PaymentConfigUpdateRequest request) {
    ProviderHandle handle = requireProvider(providerId);
    if (handle.kind() != ProviderKind.API
        || !(handle.api() instanceof PaymentConfigurable configurable)) {
      throw new ServiceException("payment_config_unsupported", "Payment provider does not expose configuration");
    }
    try {
      Map<String, PaymentConfigField> fields = new java.util.LinkedHashMap<>();
      PaymentConfigDescriptor descriptor = configurable.describeConfiguration();
      if (descriptor != null) {
        descriptor.sections().forEach(section -> section.fields().forEach(field -> fields.put(field.key(), field)));
      }
      java.util.List<PaymentConfigProblem> problems = new java.util.ArrayList<>();
      request.changes().keySet().forEach(key -> {
        PaymentConfigField field = fields.get(key);
        if (field == null || field.access() == PaymentConfigAccess.READ_ONLY) {
          problems.add(new PaymentConfigProblem(key, "unknown_or_read_only", "Setting is not writable"));
        }
      });
      request.clearedSecrets().forEach(key -> {
        PaymentConfigField field = fields.get(key);
        if (field == null || field.access() != PaymentConfigAccess.WRITE_ONLY) {
          problems.add(new PaymentConfigProblem(key, "not_a_secret", "Setting is not a write-only secret"));
        }
      });
      if (!problems.isEmpty()) {
        return PaymentConfigUpdateResult.rejected("Configuration was not changed", problems);
      }
      PaymentConfigUpdateResult result = configurable.updateConfiguration(request);
      if (result == null) {
        throw new ServiceException("payment_config_update_failed", "Payment provider returned no result");
      }
      return result;
    } catch (ServiceException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new ServiceException("payment_config_update_failed", exceptionMessage(exception));
    }
  }

  void cancelPayment(String providerId, String merchantOrderId, String providerOrderId) {
    ProviderHandle handle = requireProvider(providerId);
    if (handle.kind() == ProviderKind.LEGACY_YUPAY) {
      return;
    }
    PaymentQueryRequest request = new PaymentQueryRequest();
    request.setMerchantOrderId(merchantOrderId);
    request.setProviderOrderId(providerOrderId);
    try {
      handle.api().cancelPayment(request);
    } catch (RuntimeException exception) {
      throw new ServiceException("payment_cancel_failed", exceptionMessage(exception));
    }
  }

  boolean registerPaymentListener(Function<PaymentNotifyData, NotifyResultData> handler) {
    unregisterPaymentListener();
    for (ProviderHandle handle : providerHandles().values()) {
      if (handle.kind() == ProviderKind.LEGACY_YUPAY) {
        boolean registered = legacyYuPayBridge.registerPaymentListener(notify -> {
          NotifyResultData result = handler.apply(toBridgeNotify(LEGACY_PROVIDER_ID, notify));
          return result.success() ? YuPayBridge.NotifyResultData.ok(result.message())
              : YuPayBridge.NotifyResultData.fail(result.code(), result.message());
        });
        if (registered) {
          activeRegistrations.put(handle.providerId(),
              new ActiveRegistration(handle.kind(), handle.providerId(), null));
        }
        continue;
      }
      PaymentListener listener = notify -> {
        NotifyResultData result = handler.apply(toBridgeNotify(handle.providerId(), notify));
        return result.success() ? PaymentNotifyResult.ok(result.message())
            : PaymentNotifyResult.fail(result.code(), result.message());
      };
      try {
        handle.api().registerListener(CONSUMER_ID, listener);
        activeRegistrations.put(handle.providerId(),
            new ActiveRegistration(handle.kind(), handle.providerId(), handle.api()));
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.WARNING,
            "Failed to register listener for payment provider " + handle.providerId(), exception);
      }
    }
    return !activeRegistrations.isEmpty();
  }

  void unregisterPaymentListener() {
    for (ActiveRegistration registration : new ArrayList<>(activeRegistrations.values())) {
      if (registration.kind() == ProviderKind.LEGACY_YUPAY) {
        legacyYuPayBridge.unregisterPaymentListener();
      } else {
        try {
          registration.api().unregisterListener(CONSUMER_ID);
        } catch (RuntimeException exception) {
          plugin.getLogger().log(Level.WARNING, "Failed to unregister payment listener.", exception);
        }
      }
    }
    activeRegistrations.clear();
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

  private ProviderHandle requireProvider(String providerId) {
    if (isBlank(providerId)) {
      throw new ServiceException("payment_provider_required", "A payment provider must be configured for this method and currency");
    }
    ProviderHandle handle = providerHandles().get(providerId.trim().toLowerCase(Locale.ROOT));
    if (handle == null) {
      throw new ServiceException("payment_provider_not_found", "Payment provider was not found: " + providerId);
    }
    return handle;
  }

  private Map<String, ProviderHandle> providerHandles() {
    Map<String, ProviderHandle> result = new LinkedHashMap<>();
    Collection<RegisteredServiceProvider<WebShopXPaymentApi>> registrations =
        plugin.getServer().getServicesManager().getRegistrations(WebShopXPaymentApi.class);
    for (RegisteredServiceProvider<WebShopXPaymentApi> registration : registrations) {
      WebShopXPaymentApi api = registration.getProvider();
      if (api == null || isBlank(api.providerId())) {
        continue;
      }
      String id = api.providerId().trim().toLowerCase(Locale.ROOT);
      if (result.containsKey(id)) {
        plugin.getLogger().warning("Duplicate payment provider id ignored: " + id);
        continue;
      }
      result.put(id, new ProviderHandle(ProviderKind.API, id, api));
    }
    if (legacyYuPayBridge.isAvailable()) {
      result.putIfAbsent(LEGACY_PROVIDER_ID,
          new ProviderHandle(ProviderKind.LEGACY_YUPAY, LEGACY_PROVIDER_ID, null));
    }
    return result;
  }

  private PaymentNotifyData toBridgeNotify(String providerId, PaymentNotify notify) {
    if (notify == null) {
      return null;
    }
    return new PaymentNotifyData(
        providerId,
        notify.getMerchantOrderId(),
        notify.getProviderOrderId(),
        statusName(notify.getStatus()),
        notify.getAmountMinor(),
        notify.getCurrency(),
        methodName(notify.getMethod(), notify.getMethodCode()),
        notify.getPaidAt(),
        notify.getCreditedCoinAmount(),
        notify.getExtra());
  }

  private PaymentNotifyData toBridgeNotify(String providerId, YuPayBridge.PaymentNotifyData notify) {
    if (notify == null) {
      return null;
    }
    return new PaymentNotifyData(
        providerId,
        notify.merchantOrderId(),
        notify.providerOrderId(),
        notify.status(),
        notify.amountMinor(),
        notify.currency(),
        notify.method(),
        notify.payTime(),
        null,
        notify.extra());
  }

  private String statusName(PaymentStatus status) {
    return status == null ? "UNKNOWN" : status.name();
  }

  private String methodName(PaymentMethod method, String methodCode) {
    if (!isBlank(methodCode)) {
      return methodCode.trim();
    }
    if (method == null) {
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
      String methodCode,
      String returnUrl,
      String notifyUrl,
      Instant expiresAt,
      Map<String, String> metadata) {
  }

  record PaymentProviderConfiguration(
      String providerId,
      String displayName,
      PaymentConfigDescriptor descriptor,
      PaymentConfigSnapshot snapshot,
      Set<String> supportedLocales) {
  }

  record PaymentProviderInfo(
      boolean available,
      String providerId,
      String displayName,
      Set<PaymentMethod> supportedMethods,
      Set<String> supportedCurrencies,
      boolean rechargeRateEditable,
      String settlementAmountMode,
      String rechargeRateNotice) {
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
      String providerId,
      String merchantOrderId,
      String providerOrderId,
      String status,
      long amountMinor,
      String currency,
      String method,
      Instant payTime,
      Long creditedCoinAmount,
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
