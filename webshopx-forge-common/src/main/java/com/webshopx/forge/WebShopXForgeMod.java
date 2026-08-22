package com.webshopx.forge;

import com.webshopx.loader.LoaderRuntime;
import net.minecraftforge.fml.common.Mod;

@Mod("webshopx")
public final class WebShopXForgeMod {
  public WebShopXForgeMod() {
    LoaderRuntime.start("forge", System.getProperty("webshopx.minecraft", "unknown"),
        System.getProperty("webshopx.loader", "unknown"));
  }
}
