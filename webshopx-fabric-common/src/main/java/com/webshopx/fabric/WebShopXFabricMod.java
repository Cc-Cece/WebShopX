package com.webshopx.fabric;

import com.webshopx.loader.LoaderRuntime;
import net.fabricmc.api.ModInitializer;

public final class WebShopXFabricMod implements ModInitializer {
  @Override public void onInitialize() {
    LoaderRuntime.startDetected("fabric");
  }
}
