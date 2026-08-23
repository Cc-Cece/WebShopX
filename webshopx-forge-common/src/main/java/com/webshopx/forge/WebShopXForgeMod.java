package com.webshopx.forge;

import com.webshopx.loader.LoaderRuntime;
import com.webshopx.loader.ReflectiveHealthCommand;
import net.minecraftforge.fml.common.Mod;

@Mod("webshopx")
public final class WebShopXForgeMod {
  public WebShopXForgeMod() {
    LoaderRuntime.prepareEventBus("forge", "net.minecraftforge.common.MinecraftForge");
    LoaderRuntime.startDetected("forge");
    ReflectiveHealthCommand.installEventBus("net.minecraftforge.common.MinecraftForge");
  }
}
