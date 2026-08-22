# ADR-0004: Keep the WebShopX client enhancement out of the release matrix

- Status: accepted
- Date: 2026-08-23

## Decision

The M0-M9 candidate is server-side only. The web UI, commands and standard server containers are
the complete interaction path. No custom client protocol or client JAR is introduced.

## Rationale

A client module would multiply Loader/version/network security work while no server-authoritative
business operation requires it. Price, balance, permission, stock and delivery decisions must stay
on the server. Deferring it also guarantees that players without WebShopX installed can use every
core function.

## Consequences

`CLIENT_ENHANCEMENT` is explicitly unsupported, not silently missing. A later optional client
requires a new ADR, protocol negotiation, downgrade tests and independent artifacts.
