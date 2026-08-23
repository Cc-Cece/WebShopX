package com.webshopx.loader;

import java.lang.reflect.Method;
import java.util.Optional;

/** Reads Loader-owned version APIs without linking the shared runtime to any Loader. */
final class LoaderEnvironment {
  private LoaderEnvironment() { }

  static Version detect(String family) {
    try {
      return switch (family) {
        case "fabric" -> fabric();
        case "forge" -> legacyFml("forge");
        case "neoforge" -> detectNeoForge();
        default -> fallback();
      };
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      return fallback();
    }
  }

  private static Version fabric() throws ReflectiveOperationException {
    Class<?> loaderType = Class.forName("net.fabricmc.loader.api.FabricLoader");
    Object loader = loaderType.getMethod("getInstance").invoke(null);
    String minecraft = (String) loaderType.getMethod("getRawGameVersion").invoke(loader);
    Optional<?> container = (Optional<?>) loaderType.getMethod("getModContainer", String.class)
        .invoke(loader, "fabricloader");
    Object metadata = Class.forName("net.fabricmc.loader.api.ModContainer")
        .getMethod("getMetadata").invoke(container.orElseThrow());
    Object version = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata")
        .getMethod("getVersion").invoke(metadata);
    String loaderVersion = (String) Class.forName("net.fabricmc.loader.api.Version")
        .getMethod("getFriendlyString").invoke(version);
    return new Version(minecraft, loaderVersion);
  }

  private static Version detectNeoForge() throws ReflectiveOperationException {
    try {
      return modernNeoForge();
    } catch (ClassNotFoundException ignored) {
      return legacyFml("neoforge");
    }
  }

  private static Version legacyFml(String family) throws ReflectiveOperationException {
    Class<?> loader = Class.forName("net.minecraftforge.fml.loading.FMLLoader");
    Object info = loader.getMethod("versionInfo").invoke(null);
    String minecraft = invokeString(info, "mcVersion");
    String loaderVersion = invokeString(info, "forgeVersion");
    return new Version(minecraft, loaderVersion);
  }

  private static Version modernNeoForge() throws ReflectiveOperationException {
    Class<?> loader = Class.forName("net.neoforged.fml.loading.FMLLoader");
    Object current = loader.getMethod("getCurrent").invoke(null);
    Object info = loader.getMethod("getVersionInfo").invoke(current);
    return new Version(invokeString(info, "mcVersion"), invokeString(info, "neoForgeVersion"));
  }

  private static String invokeString(Object target, String method) throws ReflectiveOperationException {
    Method accessor = target.getClass().getMethod(method);
    return (String) accessor.invoke(target);
  }

  private static Version fallback() {
    return new Version(System.getProperty("webshopx.minecraft", "unknown"),
        System.getProperty("webshopx.loader", "unknown"));
  }

  record Version(String minecraft, String loader) { }
}
