package com.webshopx.payment.api;

import java.util.Collections;
import java.util.Set;

/**
 * Optional capability for exposing provider-owned settings to a host such as WebShopX.
 *
 * <p>The provider remains the sole owner of its configuration. The host may render the
 * descriptor and forward edits, but must not edit provider files or reload the provider plugin.
 * Implementations must persist and apply accepted changes themselves. If an implementation needs
 * to rebuild or reload an internal component, it should do so at the appropriate point inside
 * {@link #updateConfiguration(PaymentConfigUpdateRequest)}.
 */
public interface PaymentConfigurable {
  PaymentConfigDescriptor describeConfiguration();

  /** Locales available for the configuration UI. Empty means the descriptor is single-language. */
  default Set<String> supportedConfigurationLocales() {
    return Collections.emptySet();
  }

  /**
   * Returns a localized descriptor. Providers without localization need not override this method.
   * How translations are stored (files, constants, or another system) remains provider-owned.
   */
  default PaymentConfigDescriptor describeConfiguration(String locale) {
    return describeConfiguration();
  }

  PaymentConfigSnapshot readConfiguration();

  PaymentConfigUpdateResult updateConfiguration(PaymentConfigUpdateRequest request);
}
