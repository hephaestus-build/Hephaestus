# ADR 0032: Generated clients are a Maven build boundary

**Status:** Accepted
**Date:** 2026-08-25
**Authors:** Server foundations

## Context

The application compiled its handwritten sources together with roughly 12,000 generated GitHub,
GitLab, and Outline transport classes. Application and test edits therefore shared a compiler
boundary with inputs that had not changed.

ADR 0001 flattened the application into `server/` because its former nesting represented no
architectural boundary. Generated clients have an independent lifecycle: schemas, generator
configuration, dependencies, invalidation, and compiled output change separately from application
code.

## Decision drivers

- Tests must run by default and must not depend on workspace build state.
- Every generator input and toolchain change must invalidate reused output.
- Local and hosted builds should reuse unchanged generated output.
- CI reuse must not serialize required jobs or allow pull requests to publish shared cache entries.
- Mutable application build directories are not reusable artifacts.

## Considered options

- **Keep the monolith and infer a quick build from `target/`: rejected.** Filesystem state silently
  changed test selection and could leave stale generated output.
- **Transfer compiled application output between jobs: rejected.** Its 2m53s producer increased
  workflow-to-gate latency to 13m40s and coupled reuse to mutable application classes.
- **Add a generated module without persisted build output: rejected.** A clean reactor still compiles
  every module.
- **Cache module `target/` directories: rejected.** Mutable lifecycle output has no safe ownership or
  invalidation boundary.
- **Use a generated module with Maven Build Cache: accepted.** Measurement showed a 24.75s clean
  package and 1.39–1.47s cache restoration without adding a producer to the workflow critical path.

## Decision

`server/pom.xml` aggregates two modules. `generated-clients` owns the GraphQL and OpenAPI inputs,
generators, generated sources, runtime GraphQL documents, and its JAR. `application` owns deployable
code, resources, migrations, and tests and depends on that JAR.

Generation runs in the standard Maven lifecycle. Tests are never skipped based on filesystem state.
Only generated-client output participates in Maven Build Cache; the application disables restoring
and saving build output. Pull requests may restore an exact generated entry, while only the default
branch may publish one.

Maven Build Cache computes checksums from configured inputs and the effective Maven model. CI wraps
its local cache in an exact source-and-toolchain Actions cache key. The scheduled Server Phase
Reference workflow monitors byte reproducibility by comparing two clean generated-client builds with
caching disabled.

## Consequences

- Unchanged client inputs do not require regeneration or recompilation when a matching build-cache
  entry exists.
- Schema, operation, template, generator, Java, or generated-client dependency changes invalidate
  reused output. Application and test-only changes do not.
- Commands start at `server/`; deployable-only invocations target `application` after installing its
  reactor dependency.
- Tooling addresses module-owned paths rather than the former monolithic source and output trees.
- ADR 0001's rationale against organizational nesting remains applicable; this reactor adds an
  enforceable build-lifecycle boundary.

## Revisit when

Reconsider the mechanism if cache validation or reproducibility fails, cache transfer cost erases the
measured gain, or Maven Build Cache support changes materially. Re-test removal of the Relay wrapper
cleanup when GraphQL Java Codegen is upgraded. Preserve the generated/application ownership boundary
unless their lifecycles cease to differ.

## References

- [Issue #1527](https://github.com/hephaestus-build/Hephaestus/issues/1527) and
  [PR #1529](https://github.com/hephaestus-build/Hephaestus/pull/1529) contain the measurements and hosted
  observation record.
- [Maven reactor builds](https://maven.apache.org/guides/mini/guide-multiple-modules.html)
- [Maven Build Cache concepts](https://maven.apache.org/extensions/maven-build-cache-extension/concepts.html)
- [Maven reproducible builds](https://maven.apache.org/guides/mini/guide-reproducible-builds.html)
