# ADR 0003: Spring Modulith 2.0 adoption with two empirical shared kernels

**Status:** Accepted
**Date:** 2026-05-20
**Authors:** Server foundations epic (#1097)

## Context

The server has 18 top-level packages and a growing population of cross-package
dependencies. ArchUnit alone (17 tests) catches surface-level rule violations but
doesn't have a first-class "module" concept; cross-internal-package coupling
sneaks through. Spring Modulith 2.0 (GA November 2025) ships `@ApplicationModule`,
named interfaces, `Type.OPEN`, and a built-in cycle detector that runs as JUnit
under the hood.

The first real `ApplicationModules.of(...).verify()` run on the existing codebase
surfaced two genuinely-shared kernels (`config` cross-cutting Spring
`@Configuration`, `gitprovider` 47 entities + 14 SPI interfaces consumed by every
feature module) plus several packages with concentrated public surfaces that map
cleanly to a handful of sub-packages each — `workspace::context`, `practices::spi`,
`activity::events`, etc.

## Decision drivers

- Catch architectural drift at PR time (cycles, internal-package leaks)
- Generate diagrams as CI artifacts (PlantUML / C4 / Module Canvas)
- Be honest about which packages are genuinely shared kernels vs. which have a
  narrow public API that just happens to be spread across a few sub-packages
- Stay close to Modulith idiom — future framework features assume the same shape

## Considered options

1. **Two `Type.OPEN` kernels for the genuinely-shared packages; default CLOSED
   elsewhere with `@NamedInterface` exposing only the public sub-packages.** Honest
   about today's coupling without giving up boundary enforcement on packages that
   *do* have narrow APIs.
2. **All shared-looking packages OPEN.** Faster to land; gives up boundary
   enforcement on packages where the public API actually IS narrow.
3. **Named interfaces narrowing every cross-module API including the genuine
   kernels.** Architecturally pure; the `config`/`gitprovider` reorganisation would
   touch ~1000 imports for no field-evidence value.
4. **No Modulith adoption, keep ArchUnit only.** Saves a starter dependency but
   misses the cycle gate, the diagrams, and the future framework features.

## Decision

Option 1. Annotate every top-level package with `@ApplicationModule`. Mark the
two empirically-shared kernels as `Type.OPEN`:

- `config` — cross-cutting Spring `@Configuration` grab-bag, imported by every
  module's bean wiring
- `gitprovider` — 47 entities, 14 SPI interfaces; the data substrate every feature
  module sits on top of

For the other five packages with public surfaces concentrated in sub-packages,
keep the default (CLOSED) and expose the narrow sub-package APIs via
`@NamedInterface`:

- `core` — `core::exception`, `core::security`, `core::proxy`, `core::runtime`,
  `core::event` as named interfaces (tenancy primitives + logging utilities are
  available via `core` itself, which is the displayName)
- `workspace` — `workspace::context` (tenancy primitives), `workspace::authorization`,
  `workspace::spi`, `workspace::settings`
- `practices` — `practices::model`, `practices::spi`, `practices::review`,
  `practices::finding`
- `activity` — `activity::scoring`

The Gradle `architectureTest` tier runs the module-boundary verification. The
[testing guide](../contributor/testing.mdx) owns the current commands;
[ADR 0043](0043-gradle-java-build.md) supersedes the original Maven test wiring.

## Consequences

- Every PR runs `ApplicationModules.of(HephaestusApplication.class).verify()` (~80s
  warm).
- Adding a new module is one `package-info.java` with `@ApplicationModule`.
- Cycles introduced in future PRs fail the build with specific source/target package
  references — no detective work needed.
- The two `Type.OPEN` kernels are honest about coupling that's not worth fighting.
  Future epics can narrow them once there's field evidence that narrowing buys
  specific value (don't speculatively decompose).
- The five `@NamedInterface`-narrowed modules now reject internal-package leaks at
  CI time. Adding a new public API to one of them is a deliberate act
  (`@NamedInterface` declaration), not a side effect of where a class happens to
  live.
- Root-package cycles (Application.java + WebConfig.java + SecurityConfig.java +
  SecurityUtils.java moved into `core/security/`) are accepted today. Further root
  cleanup tracked separately.

## Revisit trigger

A new cycle that lands in CI surfaces a coupling pattern not covered by the current
OPEN-or-named-interface choice; or a `@NamedInterface`-narrowed module accumulates
enough cross-module imports to warrant flipping it to `Type.OPEN`; or Spring
Modulith 2.x introduces a deployment-unit concept that supersedes the role-flag
pattern in ADR 0005.
