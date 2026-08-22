package com.webshopx.neoforge;

import com.webshopx.loader.LoaderRuntime;
import net.neoforged.fml.common.Mod;

@Mod("webshopx")
public final class WebShopXNeoForgeMod {
  public WebShopXNeoForgeMod() {
    LoaderRuntime.start("neoforge", System.getProperty("webshopx.minecraft", "unknown"),
        System.getProperty("webshopx.loader", "unknown"));
  }
}
