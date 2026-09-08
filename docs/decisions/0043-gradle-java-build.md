# ADR 0043: Gradle owns the Java build

**Status:** Accepted
**Date:** 2026-09-08

## Context

The maintainers chose a complete Gradle cutover before 1.0. The existing Maven build separated
generated clients from application code, but application-only commands required installing sibling
artifacts locally and differed from fresh-checkout builds. The replacement must preserve the
[same-artifact release contract](../contributor/ci-cd.mdx#build-once) and existing verification gates.

## Decision

Gradle Kotlin DSL replaces the POMs, Maven wrapper and build-cache/profiler extensions. Vite+ remains
the repository command surface. The version catalog owns explicit Java dependency and plugin pins;
Spring's BOM manages its dependency families, including coordinated security overrides through
Spring's dependency-management plugin. Java toolchains read the JDK line from `.java-version`.

Retain the two projects from [ADR 0032](0032-generated-clients-build-boundary.md): `generated-clients`
and `application`. Domain packages remain Spring Modulith modules; server, worker and webhook remain
roles of one executable JAR. Generation tasks own separate outputs and feed Java compilation through
Gradle's task dependencies, without local artifact installation.

Use the upstream GraphQL and OpenAPI generators, Error Prone/NullAway, Spotless, PMD, JaCoCo and PIT.
Liquibase runs through its supported CLI on the application classpath. The opt-in Maven AOT profile
is removed because runtime-dependent bean selection requires [AOT to remain off](../admin/buildpacks-cds-decision.md).

JUnit tags select the test tiers; Gradle dry-run discovery checks tier and shard coverage without
reimplementing runner selection semantics. Test execution is neither cached nor considered up to
date because external services and environment are not fully declared task inputs. Compilation and
packaging retain native incrementality and build caching.

CI uses the official Gradle setup action and restores the package job's outputs for artifact
consumers. [CI documentation](../contributor/ci-cd.mdx#caches) owns cache trust and provider choices;
[server profiles](../contributor/ci-cd.mdx#server-profiles) describe Gradle profiling and JFR.

## Consequences

- Source builders use `gradlew` or `gradlew.bat`; generated outputs live under `build/`.
- Dependency and plugin updates require lockfile and verification-metadata review; generating
  checksums records downloaded bytes but does not establish publisher trust.
- Runtime behavior, HTTP contracts and released Liquibase migrations do not change.

## Alternatives

Keeping Maven was viable and avoided migration risk, but retained the local-installation workflow
the maintainers chose to replace. Supporting both build systems would duplicate task and security
policy. Splitting domain packages into separate projects is not required for this build boundary.

## References

- [Gradle's Maven migration guide](https://docs.gradle.org/current/userguide/migrating_from_maven.html)
- [Spring Boot dependency management](https://docs.spring.io/spring-boot/gradle-plugin/managing-dependencies.html)
- [Gradle dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html)
