package com.webshopx.neoforge;

import com.webshopx.loader.LoaderRuntime;
import net.minecraftforge.fml.common.Mod;

/** NeoForge 1.20.1 retained the legacy Forge-package FML annotation. */
@Mod("webshopx")
public final class WebShopXNeoForgeMod {
  public WebShopXNeoForgeMod() {
    LoaderRuntime.startDetected("neoforge");
  }
}
