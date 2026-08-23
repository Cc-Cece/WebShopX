package com.webshopx.neoforge;

import com.webshopx.loader.LoaderRuntime;
import com.webshopx.loader.ReflectiveHealthCommand;
import net.neoforged.fml.common.Mod;

@Mod("webshopx")
public final class WebShopXNeoForgeMod {
  public WebShopXNeoForgeMod() {
    LoaderRuntime.startDetected("neoforge");
    ReflectiveHealthCommand.installEventBus("net.neoforged.neoforge.common.NeoForge");
  }
}
