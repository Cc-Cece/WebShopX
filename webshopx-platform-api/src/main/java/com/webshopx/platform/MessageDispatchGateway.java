package com.webshopx.platform;

import java.util.Map;

public interface MessageDispatchGateway {
  String resolve(String key, Map<String, String> placeholders);

  void info(String key, Map<String, String> placeholders);

  void warn(String key, Map<String, String> placeholders);
}
