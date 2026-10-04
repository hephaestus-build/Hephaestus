---
id: buildpacks-cds-decision
title: Server image build through Paketo Buildpacks + Application CDS
description: How and why the application-server image is built with Paketo Cloud Native Buildpacks and Application CDS.
---

CI uses `pack` (Paketo Cloud Native Buildpacks) to build the `application-server` image from the executable JAR that it packaged and tested.
Application Class Data Sharing (CDS) is enabled.
The recipe is `server/application/project.toml`.
There is no `Dockerfile`.
Spring Boot AOT processing is **off** (see below).

## Why

Class loading accounts for most startup time.
A rolling restart incurs this cost once per replica.
CDS maps a prebuilt archive instead.
It does not change Liquibase's share of startup time.

## What the build does

`builder-noble-java-tiny` applies the Spring Boot buildpack.
With `BP_JVM_CDS_ENABLED=true`, the launcher runs once at build time: the "CDS training run".
It loads the bean-graph classes and archives them to `/workspace/application.jsa`.
It adds `-XX:SharedArchiveFile=/workspace/application.jsa` to the launcher.
At runtime, the JVM mmaps the archive instead of class-loading from JARs.

The training run starts under the `cds-training` profile (`application-cds-training.yml`).
This profile disables Liquibase and JDBC-metadata probing.
It identifies PostgreSQL without a connection.
Thus, context refresh succeeds without a reachable Postgres.

Compose's runtime `SPRING_PROFILES_ACTIVE=prod` overrides the default that the buildpack sets.
Paketo writes `env.launch/<KEY>.default`, which yields to the runtime environment.
The [build-only profile boundary](./runtime-roles.mdx#build-only-profiles) keeps runtime bootstrap out of training.
It rejects a production/build-profile combination.
Authentication types remain available for class loading without configured deployment secrets.

The build uses the run image without changes.
The server needs no `git` binary.
`GitRepositoryManager` and `GitDiffOperations` use JGit.

## Why not Spring AOT processing (`spring.aot.enabled=true`)

AOT evaluates conditional bean registration at build time.
Hephaestus selects integrations and runtime roles from deployment configuration.
An image built in CI cannot safely fix those choices.
It could omit beans that production needs.
CDS preserves runtime configuration and improves startup.

## Why not GraalVM Native Image

GraalVM Native Image has these disadvantages:

- Much longer CI builds.
- Loss of JIT peak throughput on Hibernate workloads.
- No JFR, JVMTI, or debugger.
- A closed-world model that breaks `@ConditionalOn*` runtime overrides.
- Reflective dependencies with no published reachability metadata: Slack Bolt, the GraphQL client runtime, and Liquibase.

## Builder pinning

`server/application/project.toml` pins `builder-noble-java-tiny` and the `health-checker` buildpack by sha256 digest.
`.github/workflows/cicd.yml` pins `ubuntu-noble-run-tiny` the same way.
The project descriptor has no run-image key.
Renovate tracks each image's `latest` tag and opens the digest update.

## Container healthcheck on the distroless run image

On Docker Compose, the container `HEALTHCHECK` is the only container-level health signal.
Both `service_healthy` gating and the `docker compose ps` column depend on it.
`run-tiny` has no shell or wget.
`builder-noble-java-tiny` includes no probe.
Thus, `server/application/project.toml` names an explicit buildpack group.

A named group **replaces** the builder's default order.
The `java` composite (`paketo-buildpacks/java`) must come before `docker://paketobuildpacks/health-checker`.
`BP_HEALTH_CHECKER_ENABLED=true` enables the health-checker.
It supplies the static, shell-free `thc` binary at `/workspace/health-check`.

The Compose services invoke this binary as an exec-form `HEALTHCHECK`.
`THC_PORT` and `THC_PATH` select actuator liveness/readiness.
The buildpack adds no `health-check` process type.
Thus, each probe starts no JVM.

## Rollback

The Dockerfile path builds from the checkout, not from the packaged JAR.
The build-once guarantee does not apply until that path also downloads `application-artifact`.

1. Add a `Dockerfile` for `server/application` again.
2. In `.github/workflows/cicd.yml`, change the `application-server-image` job from `use-buildpacks: true` to `docker-file`.
3. Use the [production operations runbook](production-operations-runbook.mdx) for rollback.

Detection uses a Sentry release-tagged error spike or a Prometheus alert.
The alert condition is `application_ready_time_seconds > 15` for three consecutive deploys.

## Operational checklist

`application.yml` sets `timeout-per-shutdown-phase` from `SHUTDOWN_TIMEOUT` (default 20s).
The Paketo launcher `exec`s the JVM.
It forwards signals natively, without `tini`.
Paketo's memory calculator handles JVM memory parameters.

1. Set the deployment system's stop grace period above the shutdown timeout so SIGTERM can drain in-flight requests.
2. Do not set `MaxRAMPercentage`, `-Xmx`, or `-Xss` in the Compose environment.
3. If necessary, override only `BPL_JVM_HEAD_ROOM`.

## Sources

- [Spring Boot 4 — AOT cache and CDS how-to](https://docs.spring.io/spring-boot/how-to/aot-cache.html)
- [Paketo — build an image from a compiled artifact](https://paketo.io/docs/howto/java/)
- [pack build reference](https://buildpacks.io/docs/for-platform-operators/how-to/integrate-ci/pack/cli/pack_build/)
- [Project descriptor reference](https://buildpacks.io/docs/reference/config/project-descriptor/)
- [Spring Boot 4 — Ahead-of-Time Processing](https://docs.spring.io/spring-boot/reference/packaging/aot.html)
- [OpenJDK JEP 483 — Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483)
- [paketo-buildpacks/spring-boot](https://github.com/paketo-buildpacks/spring-boot)
- [paketo-buildpacks/spring-boot#571 — BP_JVM_CDS_ENABLED deprecation track](https://github.com/paketo-buildpacks/spring-boot/issues/571)
