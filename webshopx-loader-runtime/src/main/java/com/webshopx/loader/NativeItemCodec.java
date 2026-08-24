package com.webshopx.loader;

import com.webshopx.core.ItemEnvelopeService;
import com.webshopx.platform.CompatibilityDomain;
import com.webshopx.platform.ItemEnvelope;
import com.webshopx.platform.PlatformIdentity;
import com.webshopx.platform.PlatformPorts;
import com.webshopx.platform.PlatformResult;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lossless native ItemStack codec for legacy NBT and registry-aware data components. */
final class NativeItemCodec implements PlatformPorts.ItemCodec<Object> {
  private static final Pattern ITEM_ID = Pattern.compile(
      "(?:^|[,{\\s])(?:\\\"?id\\\"?)\\s*:\\s*\\\"?([a-z0-9_.-]+:[a-z0-9_./-]+)\\\"?");
  private final String id;
  private final int version;
  private final PlatformIdentity identity;
  private final Supplier<Object> server;
  private final ItemEnvelopeService envelopes;
  private volatile Class<?> nativeItemClass;
  private volatile String lastFailure = "none";

  NativeItemCodec(PlatformIdentity identity, Supplier<Object> server, Clock clock) {
    this.identity = Objects.requireNonNull(identity, "identity");
    this.server = Objects.requireNonNull(server, "server");
    this.id = identity.loader() + "-native-item";
    this.version = modern(identity.minecraftVersion()) ? 2 : 1;
    this.envelopes = new ItemEnvelopeService(clock, Set.of(id));
  }

  @Override public String id() { return id; }
  @Override public int version() { return version; }

  @Override
  public PlatformResult<ItemEnvelope> encode(Object item, PlatformIdentity requestedIdentity) {
    if (item == null) return PlatformResult.rejected("ITEM_NULL", "error.item.null");
    if (!identity.equals(requestedIdentity)) {
      return PlatformResult.rejected("ITEM_PLATFORM_MISMATCH", "error.item.platform_mismatch");
    }
    try {
      nativeItemClass = item.getClass();
      if (isEmpty(item)) return PlatformResult.rejected("ITEM_EMPTY", "error.item.empty");
      int count = count(item);
      String snbt = version == 1 ? encodeLegacy(item) : encodeModern(item);
      Matcher matcher = ITEM_ID.matcher(snbt);
      if (!matcher.find()) return PlatformResult.rejected("ITEM_ID_MISSING", "error.item.id_missing");
      byte[] payload = snbt.getBytes(StandardCharsets.UTF_8);
      CompatibilityDomain domain = domain();
      return PlatformResult.success(envelopes.create(
          id, version, domain, matcher.group(1), count, payload,
          Map.of("format", version == 1 ? "legacy-snbt" : "components-snbt")));
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation
          && invocation.getCause() != null ? invocation.getCause() : failure;
      lastFailure = cause.getClass().getSimpleName() + ":" + Objects.toString(cause.getMessage(), "");
      return PlatformResult.rejected("ITEM_ENCODE_FAILED", "error.item.encode_failed");
    }
  }

  @Override
  public PlatformResult<Object> decode(ItemEnvelope envelope, CompatibilityDomain targetDomain) {
    PlatformResult<ItemEnvelope> validation = envelopes.validate(envelope, targetDomain);
    if (validation instanceof PlatformResult.Rejected<ItemEnvelope> rejected) {
      return new PlatformResult.Rejected<>(rejected.errorCode(), rejected.messageKey(), rejected.retryable());
    }
    if (!(validation instanceof PlatformResult.Success<ItemEnvelope> success)) {
      return PlatformResult.rejected("ITEM_INVALID", "error.item.invalid");
    }
    try {
      String snbt = new String(success.value().payload(), StandardCharsets.UTF_8);
      Object tag = parseTag(snbt);
      Object item = version == 1 ? decodeLegacy(tag) : decodeModern(tag);
      if (item == null || isEmpty(item)) {
        return PlatformResult.rejected("ITEM_DECODE_EMPTY", "error.item.decode_empty");
      }
      return PlatformResult.success(item);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation
          && invocation.getCause() != null ? invocation.getCause() : failure;
      lastFailure = cause.getClass().getSimpleName() + ":" + Objects.toString(cause.getMessage(), "");
      return PlatformResult.rejected("ITEM_DECODE_FAILED", "error.item.decode_failed");
    }
  }

  CompatibilityDomain domain() {
    return new CompatibilityDomain(identity.platform(), identity.loader(), identity.minecraftVersion(),
        version, identity.modpackFingerprint());
  }

  PlatformResult<ItemEnvelope> createEnvelope(String registryId, int count) {
    if (registryId == null || !registryId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
        || count < 1 || count > 99_999) {
      return PlatformResult.rejected("ITEM_CREATE_INVALID", "error.item.create_invalid");
    }
    try {
      ClassLoader loader = server.get().getClass().getClassLoader();
      Class<?> locationType = loadFirst(loader,
          "net.minecraft.resources.ResourceLocation", "net.minecraft.resources.Identifier",
          "net.minecraft.class_2960");
      Object location = resourceLocation(locationType, registryId);
      Object itemRegistry = itemRegistry(loader);
      Object nativeItem = registryValue(itemRegistry, location);
      Object stack = constructStack(loader, nativeItem, count);
      if (stack == null || isEmpty(stack)) {
        return PlatformResult.rejected("ITEM_REGISTRY_MISSING", "error.item.registry_missing");
      }
      return encode(stack, identity);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      lastFailure = failure.getClass().getSimpleName() + ":" + Objects.toString(failure.getMessage(), "");
      return PlatformResult.rejected("ITEM_CREATE_FAILED", "error.item.create_failed");
    }
  }

  Object decodeNativeTag(Object tag) throws ReflectiveOperationException {
    return version == 1 ? decodeLegacy(tag) : decodeModern(tag);
  }

  Object envelopeTag(ItemEnvelope envelope) throws ReflectiveOperationException {
    PlatformResult<ItemEnvelope> validation = envelopes.validate(envelope, domain());
    if (!(validation instanceof PlatformResult.Success<ItemEnvelope> success)) {
      throw new IllegalArgumentException("invalid native item envelope");
    }
    return parseTag(new String(success.value().payload(), StandardCharsets.UTF_8));
  }

  String probeRoundTrip() {
    try {
      ClassLoader loader = server.get().getClass().getClassLoader();
      Class<?> itemsType = loadFirst(loader, "net.minecraft.world.item.Items", "net.minecraft.class_1802");
      String last = "no_public_item";
      int candidates = 0;
      for (Field field : itemsType.getFields()) {
        if (!Modifier.isStatic(field.getModifiers())) continue;
        Object nativeItem = field.get(null);
        if (nativeItem == null) continue;
        Object stack = constructStack(loader, nativeItem);
        if (stack == null || isEmpty(stack)) continue;
        candidates++;
        PlatformResult<ItemEnvelope> encoded = encode(stack, identity);
        if (!(encoded instanceof PlatformResult.Success<ItemEnvelope> success)) {
          last = encoded instanceof PlatformResult.Rejected<ItemEnvelope> rejected
              ? rejected.errorCode() : encoded.getClass().getSimpleName();
          continue;
        }
        PlatformResult<Object> decoded = decode(success.value(), domain());
        if (!(decoded instanceof PlatformResult.Success<Object> restored)) {
          last = decoded instanceof PlatformResult.Rejected<Object> rejected
              ? rejected.errorCode() : decoded.getClass().getSimpleName();
          continue;
        }
        PlatformResult<ItemEnvelope> reencoded = encode(restored.value(), identity);
        if (reencoded instanceof PlatformResult.Success<ItemEnvelope> second
            && success.value().payloadHash().equals(second.value().payloadHash())) {
          PlatformResult<ItemEnvelope> created = createEnvelope("minecraft:diamond", 3);
          if (!(created instanceof PlatformResult.Success<ItemEnvelope> generated)
              || generated.value().count() != 3
              || !(decode(generated.value(), domain()) instanceof PlatformResult.Success<?>)) {
            return "WebShopX item-roundtrip=FAIL reason=registry_create detail=" + lastFailure;
          }
          String corpus = probeCorpus(generated.value());
          if (!corpus.startsWith("PASS")) {
            return "WebShopX item-roundtrip=FAIL reason=fixture_corpus detail=" + corpus;
          }
          return "WebShopX item-roundtrip=PASS codec=" + id + " version=" + version
              + " registry=" + success.value().registryId()
              + " hash=" + success.value().payloadHash()
              + " registry-create=PASS createdHash=" + generated.value().payloadHash()
              + " fixtures=" + corpus;
        }
      }
      return "WebShopX item-roundtrip=FAIL reason=" + last + " candidates=" + candidates
          + " detail=" + lastFailure;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
      return "WebShopX item-roundtrip=FAIL reason=" + failure.getClass().getSimpleName();
    }
  }

  private String probeCorpus(ItemEnvelope reference) {
    List<String> registryIds = List.of("minecraft:stone", "minecraft:diamond_sword",
        "minecraft:enchanted_book", "minecraft:potion", "minecraft:written_book",
        "minecraft:filled_map", "minecraft:shulker_box", "minecraft:bundle");
    int passed = 0;
    for (String registryId : registryIds) {
      PlatformResult<ItemEnvelope> created = createEnvelope(registryId, 1);
      if (!(created instanceof PlatformResult.Success<ItemEnvelope> envelope)
          || !(decode(envelope.value(), domain()) instanceof PlatformResult.Success<?>)) {
        return "registry:" + registryId + ":" + resultCode(created);
      }
      passed++;
    }
    CompatibilityDomain foreign = new CompatibilityDomain(identity.platform(), identity.loader(),
        identity.minecraftVersion(), version, "sha256:foreign-modpack");
    ItemEnvelope incompatible = copy(reference, reference.payload(), reference.payloadHash(), foreign, reference.codec());
    PlatformResult<Object> rejectedDomain = decode(incompatible, domain());
    if (!(rejectedDomain instanceof PlatformResult.Rejected<Object> rejected)
        || !"ITEM_DOMAIN_INCOMPATIBLE".equals(rejected.errorCode())) {
      return "domain_rejection_failed";
    }
    byte[] corrupt = reference.payload();
    corrupt[0] ^= 1;
    ItemEnvelope damaged = copy(reference, corrupt, reference.payloadHash(), domain(), reference.codec());
    PlatformResult<Object> rejectedHash = decode(damaged, domain());
    if (!(rejectedHash instanceof PlatformResult.Rejected<Object> hashFailure)
        || !"ITEM_HASH_MISMATCH".equals(hashFailure.errorCode())) {
      return "hash_rejection_failed";
    }
    ItemEnvelope unknown = copy(reference, reference.payload(), reference.payloadHash(), domain(), "missing-codec");
    PlatformResult<Object> rejectedCodec = decode(unknown, domain());
    if (!(rejectedCodec instanceof PlatformResult.Rejected<Object> codecFailure)
        || !"ITEM_CODEC_UNKNOWN".equals(codecFailure.errorCode())) {
      return "codec_rejection_failed";
    }
    return "PASS(" + passed + ",domain,hash,codec)";
  }

  private static ItemEnvelope copy(ItemEnvelope source, byte[] payload, String hash,
      CompatibilityDomain domain, String codec) {
    return new ItemEnvelope(source.schemaVersion(), codec, source.codecVersion(), domain,
        source.registryId(), source.count(), source.payloadEncoding(), payload, hash,
        source.summary(), source.createdAt());
  }

  private static String resultCode(PlatformResult<?> result) {
    return result instanceof PlatformResult.Rejected<?> rejected
        ? rejected.errorCode() : result.getClass().getSimpleName();
  }

  private static Object constructStack(ClassLoader loader, Object nativeItem)
      throws ReflectiveOperationException {
    return constructStack(loader, nativeItem, 1);
  }

  private static Object constructStack(ClassLoader loader, Object nativeItem, int count)
      throws ReflectiveOperationException {
    Class<?> stackType = loadFirst(loader,
        "net.minecraft.world.item.ItemStack", "net.minecraft.class_1799");
    for (Constructor<?> constructor : stackType.getConstructors()) {
      if (constructor.getParameterCount() == 2
          && constructor.getParameterTypes()[0].isInstance(nativeItem)
          && constructor.getParameterTypes()[1] == int.class) {
        return constructor.newInstance(nativeItem, count);
      }
    }
    if (count == 1) {
      for (Constructor<?> constructor : stackType.getConstructors()) {
        if (constructor.getParameterCount() == 1
            && constructor.getParameterTypes()[0].isInstance(nativeItem)) {
          return constructor.newInstance(nativeItem);
        }
      }
    }
    return null;
  }

  private static Object resourceLocation(Class<?> type, String id) throws ReflectiveOperationException {
    for (String name : List.of("parse", "tryParse", "method_60654", "method_12836")) {
      try {
        Method factory = type.getMethod(name, String.class);
        Object value = factory.invoke(null, id);
        if (value != null) return value;
      } catch (NoSuchMethodException ignored) {
        // Try the next API generation.
      }
    }
    try {
      return type.getConstructor(String.class).newInstance(id);
    } catch (NoSuchMethodException missingSingleArgument) {
      String[] parts = id.split(":", 2);
      return type.getConstructor(String.class, String.class).newInstance(parts[0], parts[1]);
    }
  }

  private static Object itemRegistry(ClassLoader loader) throws ReflectiveOperationException {
    ReflectiveOperationException last = null;
    for (String className : List.of("net.minecraft.core.registries.BuiltInRegistries",
        "net.minecraft.core.Registry", "net.minecraft.class_7923", "net.minecraft.class_2378")) {
      try {
        Class<?> type = Class.forName(className, false, loader);
        return staticField(type, "ITEM", "f_257033_", "f_122827_", "field_41178", "field_11142");
      } catch (ReflectiveOperationException failure) {
        last = failure;
      }
    }
    throw last == null ? new ClassNotFoundException("item registry") : last;
  }

  private static Object registryValue(Object registry, Object location)
      throws ReflectiveOperationException {
    List<Class<?>> apiTypes = new java.util.ArrayList<>();
    for (String className : List.of("net.minecraft.core.DefaultedRegistry",
        "net.minecraft.core.Registry", "net.minecraft.class_7922", "net.minecraft.class_2378")) {
      try {
        Class<?> type = Class.forName(className, false, registry.getClass().getClassLoader());
        if (type.isInstance(registry)) apiTypes.add(type);
      } catch (ClassNotFoundException ignored) {
        // Try the next runtime API generation.
      }
    }
    apiTypes.add(registry.getClass());
    for (Class<?> apiType : apiTypes) {
      for (String name : List.of("getValue", "method_10223", "m_7745_", "m_6246_", "get")) {
        Method get = Arrays.stream(apiType.getMethods())
            .filter(value -> value.getName().equals(name) && value.getParameterCount() == 1)
            .filter(value -> value.getParameterTypes()[0].isInstance(location))
            .findFirst().orElse(null);
        if (get == null) continue;
        Object value = get.invoke(registry, location);
        if (value instanceof java.util.Optional<?> optional) {
          if (optional.isEmpty()) return null;
          value = optional.get();
          try {
            value = method(value.getClass(), new String[]{"value", "method_40237"}, 0).invoke(value);
          } catch (NoSuchMethodException notAHolder) {
            // The optional already contains the native item on this API generation.
          }
        }
        return value;
      }
    }
    throw new NoSuchMethodException("item registry lookup");
  }

  private static Class<?> loadFirst(ClassLoader loader, String... names)
      throws ClassNotFoundException {
    for (String name : names) {
      try {
        return Class.forName(name, false, loader);
      } catch (ClassNotFoundException ignored) {
        // Try the next runtime namespace.
      }
    }
    throw new ClassNotFoundException(String.join(",", names));
  }

  private String encodeLegacy(Object item) throws ReflectiveOperationException {
    Method save = method(item.getClass(), new String[]{"save", "method_7953", "m_41739_"}, 1);
    Class<?> tagType = save.getParameterTypes()[0];
    Object tag = tagType.getConstructor().newInstance();
    return Objects.toString(save.invoke(item, tag));
  }

  private Object decodeLegacy(Object tag) throws ReflectiveOperationException {
    Class<?> itemType = requireItemClass();
    Method factory = Arrays.stream(itemType.getMethods())
        .filter(value -> Modifier.isStatic(value.getModifiers()) && value.getParameterCount() == 1)
        .filter(value -> value.getParameterTypes()[0].isInstance(tag))
        .filter(value -> itemType.isAssignableFrom(value.getReturnType()))
        .filter(value -> named(value, "of", "method_7915", "m_41712_"))
        .findFirst().orElseThrow(() -> new NoSuchMethodException("ItemStack NBT factory"));
    return factory.invoke(null, tag);
  }

  private String encodeModern(Object item) throws ReflectiveOperationException {
    Object codec = staticField(item.getClass(), "CODEC", "field_24671", "f_41574_");
    Object ops = registryOps(item.getClass().getClassLoader());
    Method encodeStart = method(codec.getClass(), new String[]{"encodeStart"}, 2);
    return Objects.toString(dataResultValue(encodeStart.invoke(codec, ops, item)));
  }

  private Object decodeModern(Object tag) throws ReflectiveOperationException {
    Class<?> itemType = requireItemClass();
    Object codec = staticField(itemType, "CODEC", "field_24671", "f_41574_");
    Object ops = registryOps(itemType.getClassLoader());
    Method parse = method(codec.getClass(), new String[]{"parse"}, 2);
    return dataResultValue(parse.invoke(codec, ops, tag));
  }

  private Object registryOps(ClassLoader loader) throws ReflectiveOperationException {
    Object nativeServer = server.get();
    if (nativeServer == null) throw new IllegalStateException("native server is not bound");
    Method accessMethod = method(nativeServer.getClass(),
        new String[]{"registryAccess", "method_30611", "m_206579_"}, 0);
    Object access = accessMethod.invoke(nativeServer);
    Class<?> nbtOps = loadFirst(loader, "net.minecraft.nbt.NbtOps", "net.minecraft.class_2509");
    Object baseOps = staticField(nbtOps, "INSTANCE", "field_11560");
    Class<?> registryOps = loadFirst(loader,
        "net.minecraft.resources.RegistryOps", "net.minecraft.class_6903");
    Method create = Arrays.stream(registryOps.getMethods())
        .filter(value -> Modifier.isStatic(value.getModifiers())
            && named(value, "create", "method_46632"))
        .filter(value -> value.getParameterCount() == 2
            && value.getParameterTypes()[0].isInstance(baseOps)
            && value.getParameterTypes()[1].isInstance(access))
        .findFirst().orElseThrow(() -> new NoSuchMethodException("RegistryOps.create"));
    return create.invoke(null, baseOps, access);
  }

  private Object parseTag(String snbt) throws ReflectiveOperationException {
    ClassLoader loader = requireItemClass().getClassLoader();
    Class<?> parser;
    try {
      parser = Class.forName("net.minecraft.nbt.TagParser", false, loader);
    } catch (ClassNotFoundException ignored) {
      parser = Class.forName("net.minecraft.class_2522", false, loader);
    }
    Method parse = Arrays.stream(parser.getMethods())
        .filter(value -> Modifier.isStatic(value.getModifiers()) && value.getParameterCount() == 1)
        .filter(value -> value.getParameterTypes()[0] == String.class)
        .filter(value -> named(value, "parseCompoundFully", "parseTag", "method_10718", "m_129359_"))
        .findFirst().orElseThrow(() -> new NoSuchMethodException("SNBT parser"));
    return parse.invoke(null, snbt);
  }

  private static Object dataResultValue(Object result) throws ReflectiveOperationException {
    Method get = Arrays.stream(result.getClass().getMethods())
        .filter(value -> value.getParameterCount() == 0)
        .filter(value -> value.getName().equals("getOrThrow"))
        .findFirst().orElseThrow(() -> new NoSuchMethodException("DataResult.getOrThrow"));
    return get.invoke(result);
  }

  private static boolean isEmpty(Object item) throws ReflectiveOperationException {
    return (Boolean) method(item.getClass(),
        new String[]{"isEmpty", "method_7960", "m_41619_"}, 0).invoke(item);
  }

  static int count(Object item) throws ReflectiveOperationException {
    return (Integer) method(item.getClass(),
        new String[]{"getCount", "method_7947", "m_41613_"}, 0).invoke(item);
  }

  private Class<?> requireItemClass() {
    Class<?> result = nativeItemClass;
    if (result == null) {
      Object nativeServer = server.get();
      if (nativeServer == null) throw new IllegalStateException("native server is not bound");
      try {
        result = loadFirst(nativeServer.getClass().getClassLoader(),
            "net.minecraft.world.item.ItemStack", "net.minecraft.class_1799");
        nativeItemClass = result;
      } catch (ClassNotFoundException failure) {
        throw new IllegalStateException("native ItemStack class is unavailable", failure);
      }
    }
    return result;
  }

  private static Object staticField(Class<?> type, String... names) throws ReflectiveOperationException {
    for (String name : names) {
      try {
        Field field = type.getField(name);
        return field.get(null);
      } catch (NoSuchFieldException ignored) {
        // Try the next runtime mapping name.
      }
    }
    throw new NoSuchFieldException(String.join(",", names));
  }

  static Method method(Class<?> type, String[] names, int parameters) throws NoSuchMethodException {
    for (Method value : type.getMethods()) {
      if (value.getParameterCount() == parameters && named(value, names)) return value;
    }
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      for (Method value : current.getDeclaredMethods()) {
        if (value.getParameterCount() == parameters && named(value, names)) {
          if (!value.trySetAccessible()) continue;
          return value;
        }
      }
    }
    throw new NoSuchMethodException(type.getName() + "." + String.join("/", names));
  }

  private static boolean named(Method method, String... names) {
    for (String name : names) if (method.getName().equals(name)) return true;
    return false;
  }

  private static boolean modern(String minecraft) {
    String[] parts = minecraft.split("\\.");
    try {
      int major = Integer.parseInt(parts[0]);
      if (major > 1) return true;
      int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
      int patch = parts.length > 2
          ? Integer.parseInt(parts[2].replaceFirst("[^0-9].*$", "")) : 0;
      return minor > 20 || (minor == 20 && patch >= 5);
    } catch (NumberFormatException failure) {
      return false;
    }
  }
}
