package com.webshopx.forge;

import com.webshopx.loader.LoaderRuntime;
import net.minecraftforge.fml.common.Mod;

@Mod("webshopx")
public final class WebShopXForgeMod {
  public WebShopXForgeMod() {
    LoaderRuntime.startDetected("forge");
  }
}
