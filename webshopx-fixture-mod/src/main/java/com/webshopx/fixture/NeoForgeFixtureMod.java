package com.webshopx.fixture;

import net.neoforged.fml.common.Mod;
import net.neoforged.bus.api.IEventBus;

@Mod("webshopx_fixture")
public final class NeoForgeFixtureMod {
  public NeoForgeFixtureMod(IEventBus modBus) {
    FixtureItemRegistration.registerDeferred("net.neoforged", modBus);
  }
}
