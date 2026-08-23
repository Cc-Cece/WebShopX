package com.webshopx.fabric;

import com.webshopx.loader.LoaderRuntime;
import com.webshopx.loader.ReflectiveHealthCommand;
import net.fabricmc.api.ModInitializer;

public final class WebShopXFabricMod implements ModInitializer {
  @Override public void onInitialize() {
    LoaderRuntime.prepareFabric();
    LoaderRuntime.startDetected("fabric");
    ReflectiveHealthCommand.installFabric();
  }
}
