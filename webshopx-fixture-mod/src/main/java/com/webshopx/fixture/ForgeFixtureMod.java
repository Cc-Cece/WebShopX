package com.webshopx.fixture;

import net.minecraftforge.fml.common.Mod;

@Mod("webshopx_fixture")
public final class ForgeFixtureMod {
  public ForgeFixtureMod() {
    FixtureItemRegistration.registerDeferred("net.minecraftforge");
  }
}
