package com.webshopx.fixture;

import net.fabricmc.api.ModInitializer;

public final class FabricFixtureMod implements ModInitializer {
  @Override public void onInitialize() {
    FixtureItemRegistration.registerDirect();
  }
}
