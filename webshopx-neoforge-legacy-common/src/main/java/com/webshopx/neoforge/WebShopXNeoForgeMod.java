package com.webshopx.neoforge;

import com.webshopx.loader.LoaderRuntime;
import com.webshopx.loader.ReflectiveHealthCommand;
import net.minecraftforge.fml.common.Mod;

/** NeoForge 1.20.1 retained the legacy Forge-package FML annotation. */
@Mod("webshopx")
public final class WebShopXNeoForgeMod {
  public WebShopXNeoForgeMod() {
    LoaderRuntime.prepareEventBus("neoforge", "net.minecraftforge.common.MinecraftForge");
    LoaderRuntime.startDetected("neoforge");
    ReflectiveHealthCommand.installEventBus("net.minecraftforge.common.MinecraftForge");
  }
}
