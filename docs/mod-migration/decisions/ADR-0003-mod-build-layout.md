# ADR-0003: Loader-isolated anchor artifacts and shared Java contracts

- Status: accepted
- Date: 2026-08-23

## Decision

WebShopX uses separate artifacts for Fabric, Forge and NeoForge. Platform-neutral code lives in
`webshopx-platform-api`, `webshopx-core` and `webshopx-loader-runtime`. Each Loader artifact has
exactly one metadata format and one entrypoint and embeds the shared runtime. Loader API binary
contracts are compile-only and are never packaged.

The frozen anchor set is:

| Family | Minecraft | Loader | Java | Artifact suffix |
|---|---|---|---:|---|
| Fabric | 1.18.2 | 0.19.3 | 17 | `fabric-1.18.2` |
| Fabric | 1.20.1 | 0.19.3 | 17 | `fabric-1.20.1` |
| Fabric | 26.2 | 0.19.3 | 25 | `fabric-26.2` |
| Forge | 1.18.2 | 40.3.12 | 17 | `forge-1.18.2` |
| Forge | 1.20.1 | 47.4.23 | 17 | `forge-1.20.1` |
| NeoForge | 1.20.1 | 47.1.106 | 17 | `neoforge-1.20.1` |
| NeoForge | 26.2 | 26.2.0.65 | 25 | `neoforge-26.2` |

An anchor proves only its exact row. It does not imply that untested intermediate Minecraft
versions are supported. Paper remains the root project so its established task names and artifact
variants remain compatible.

## Rationale

This layout prevents cross-Loader metadata and binary dependencies, keeps Minecraft types out of
the core, and permits legacy Java 17 and modern Java 25 compilation in the same Gradle invocation.

## Consequences

Adding a Minecraft version requires an explicit module or an ADR-backed compatibility interval,
metadata verification, entrypoint smoke, and a dedicated-server smoke before it can be advertised.
