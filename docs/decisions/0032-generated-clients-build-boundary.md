# ADR 0032: Generated clients have an independent build boundary

**Status:** Ownership boundary accepted; Maven mechanism superseded by [ADR 0043](0043-gradle-java-build.md)
**Date:** 2026-08-25
**Authors:** Server foundations

## Context

The application compiled handwritten sources together with generated GitHub, GitLab, and Outline
transport classes. Application and test edits therefore shared a compiler
boundary with inputs that had not changed.

ADR 0001 flattened the application into `server/` because its former nesting represented no
architectural boundary. Generated clients have an independent lifecycle: schemas, generator
configuration, dependencies, invalidation, and compiled output change separately from application
code.

## Considered options

- **Keep the monolith and infer a quick build from `target/`: rejected.** Filesystem state silently
  changed test selection and could leave stale generated output.
- **Transfer compiled application output between jobs as a cache: rejected.** A producer adds a
  serial dependency and couples reuse to mutable application classes. This does not prohibit
  downstream gates from consuming the packaged release artifact.
- **Add a generated module without persisted build output: rejected.** A clean reactor still compiles
  every module.
- **Cache module `target/` directories: rejected.** Mutable lifecycle output has no safe ownership or
  invalidation boundary.
- **Use a generated module with Maven Build Cache: accepted at the time.** It reused generated
  output without adding a producer to the workflow critical path.

## Decision

`server/generated-clients` owns the GraphQL and OpenAPI inputs, generators, generated sources,
runtime GraphQL documents, and its JAR. `server/application` owns deployable code, resources,
migrations, and tests and depends on that JAR.

Generation is a declared prerequisite of compilation. Tests are never skipped based on filesystem
state. [ADR 0043](0043-gradle-java-build.md) owns the current build and cache mechanism; the Maven
mechanism originally selected here is superseded.

## Consequences

- Unchanged client inputs do not require regeneration or recompilation when a matching build-cache
  entry exists.
- Schema, operation, template, generator, Java, or generated-client dependency changes invalidate
  reused output. Application and test-only changes do not.
- Tooling addresses module-owned paths rather than the former monolithic source and output trees.
- ADR 0001's rationale against organizational nesting remains applicable; this module split adds an
  enforceable build-lifecycle boundary.

## References

- [Issue #1527](https://github.com/hephaestus-build/Hephaestus/issues/1527) and
  [PR #1529](https://github.com/hephaestus-build/Hephaestus/pull/1529) contain the measurements and hosted
  observation record.
