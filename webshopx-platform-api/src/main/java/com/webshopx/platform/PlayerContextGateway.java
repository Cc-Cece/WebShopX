package com.webshopx.platform;

import java.util.UUID;
import java.util.function.Function;

public interface PlayerContextGateway {
  <T> T supplyPlayer(UUID playerId, Function<PlayerHandle, T> task);

  interface PlayerHandle {
    UUID uniqueId();

    boolean online();
  }
}
