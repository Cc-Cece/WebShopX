# ADR-0003: Loader-isolated compatibility-family artifacts and shared Java contracts

- Status: accepted
- Date: 2026-08-23

## Decision

WebShopX uses separate artifacts for Fabric, Forge and NeoForge. Platform-neutral code lives in
`webshopx-platform-api`, `webshopx-core` and `webshopx-loader-runtime`. Each Loader artifact has
exactly one metadata format and one entrypoint and embeds the shared runtime. Loader API binary
contracts are compile-only and are never packaged.

The frozen artifact families are:

| Family | Declared Minecraft interval | Boundary evidence | Artifact suffix |
|---|---|---|---|
| Fabric legacy | `>=1.18.2 <1.20` | 1.18.2, 1.19.4 | `fabric-1.18.2` |
| Fabric bridge | `>=1.20 <=1.20.4` | 1.20, 1.20.4 | `fabric-1.20.1` |
| Fabric component | `>=1.20.5 <=26.2` | 1.20.5, 26.2 | `fabric-26.2` |
| Forge legacy | `[1.18.2,1.20)` | 1.18.2, 1.19.4 | `forge-1.18.2` |
| Forge bridge | `[1.20,1.20.2)` | 1.20, 1.20.1 | `forge-1.20.1` |
| NeoForge transition | `[1.20.1]` | 1.20.1 | `neoforge-1.20.1` |
| NeoForge modern | `[1.20.2,26.3)` | 1.20.2, 26.2 | `neoforge-26.2` |

Each interval is a single binary-compatibility family, not an inference from one anchor. Loader
entrypoints only use the stable Loader bootstrap annotation/interface; all Minecraft registry,
item, inventory and command access is runtime-reflective and fail-closed. All family artifacts are
compiled to Java 17 bytecode, while the server matrix selects the Java version required by the
Minecraft endpoint. WebShopX does not call Fabric API, so its former exact Fabric API dependency
was removed instead of falsely pinning an otherwise compatible server-only artifact.

Every interval is accepted only when both boundary servers pass lifecycle, metadata, HTTP, native
item corpus and clean-stop gates. The RC evidence packet contains one required check per boundary;
missing, skipped or stale boundaries keep the checked-in row `unverified` and make
`releaseReadiness` fail. Paper remains the root project so its established task names and artifact
variants remain compatible.

## Rationale

This layout prevents cross-Loader metadata and binary dependencies, keeps Minecraft types out of
the core, and permits legacy Java 17 and modern Java 25 compilation in the same Gradle invocation.

## Consequences

Adding a Minecraft version outside these closed intervals requires an explicit module or a revised
ADR-backed interval, metadata verification, entrypoint smoke, and boundary dedicated-server smoke
before it can be advertised.
