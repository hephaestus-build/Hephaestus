---
id: buildpacks-cds-decision
title: Server image build via Paketo Buildpacks + Application CDS
description: How and why the application-server image is built with Paketo Cloud Native Buildpacks and Application CDS.
---

The `application-server` image is built with `pack` (Paketo Cloud Native Buildpacks) from the
executable JAR that CI packaged and tested, with Application Class Data Sharing (CDS) enabled. The
recipe is `server/application/project.toml`; there is no `Dockerfile`. Spring Boot AOT processing is
**off** (see below).

## Why

Startup is dominated by class loading, and a rolling restart pays it once per replica. CDS maps a
prebuilt archive instead; Liquibase's share of startup is unaffected.

## What the build does

`builder-noble-java-tiny` applies the Spring Boot buildpack. With `BP_JVM_CDS_ENABLED=true` the launcher runs once at build time (the "CDS training run") to load the bean-graph classes, archives them to `/workspace/application.jsa`, and bakes `-XX:SharedArchiveFile=/workspace/application.jsa` into the launcher. At runtime the JVM mmaps the archive instead of class-loading from JARs.

The training run boots under the `cds-training` profile (`application-cds-training.yml`), which disables Liquibase + JDBC-metadata probing and identifies PostgreSQL without opening a connection so context refresh succeeds without a reachable Postgres. Coolify's runtime `SPRING_PROFILES_ACTIVE=prod` overrides the buildpack-baked default (Paketo writes `env.launch/<KEY>.default`, which yields to the runtime env).

The [build-only profile boundary](./runtime-roles.mdx#build-only-profiles) keeps runtime bootstrap out of training and rejects a production/build-profile combination. Authentication types remain available for class loading without configured deployment secrets.

The run image is used unmodified; native Git runs in the isolated preparation image, not the application image.

## Why not Spring AOT processing (`spring.aot.enabled=true`)

AOT evaluates conditional bean registration at build time. Hephaestus selects integrations and runtime roles from
deployment configuration, so an image built in CI cannot safely fix those choices without omitting beans needed in
production. CDS preserves runtime configuration while improving startup.

## Why not GraalVM Native Image

Much longer CI builds, loss of JIT peak throughput on Hibernate workloads, no JFR/JVMTI/debugger, a closed-world model that breaks `@ConditionalOn*` runtime overrides, and reflective dependencies (Slack Bolt, the GraphQL client runtime, Liquibase) that publish no reachability metadata.

## Builder pinning

`server/application/project.toml` pins `builder-noble-java-tiny` and the `health-checker` buildpack
by sha256 digest; `.github/workflows/cicd.yml` pins `ubuntu-noble-run-tiny` the same way, because
the project descriptor has no run-image key. Renovate tracks each image's `latest` tag and opens the
digest bump.

## Container healthcheck on the distroless run image

On Docker Compose the container `HEALTHCHECK` is the only container-level health signal —
`service_healthy` gating and the `docker compose ps` column both depend on it. `run-tiny` has no
shell/wget and `builder-noble-java-tiny` bundles no probe, so `server/application/project.toml` names an explicit buildpack
group. A named group **replaces** the builder's default order, so the `java` composite must be listed
(`paketo-buildpacks/java`) before `docker://paketobuildpacks/health-checker`; `BP_HEALTH_CHECKER_ENABLED=true` opts it in. It contributes
the static, shell-free `thc` binary at `/workspace/health-check`, which the compose services invoke as an
exec-form `HEALTHCHECK` (`THC_PORT`/`THC_PATH` → actuator liveness/readiness). No `health-check` process
type is added, so no JVM is spawned per probe.

## Rollback

Re-add a `Dockerfile` for `server/application` and switch the `application-server-image` job in `.github/workflows/cicd.yml` from `use-buildpacks: true` to `docker-file`. The Dockerfile path builds from the checkout, not from the packaged JAR, so the build-once guarantee lapses until that path also downloads `application-artifact`. Coolify re-deploys the prior image SHA. Detection: Sentry release-tagged error spike, or Prometheus alert on `application_ready_time_seconds > 15` for three consecutive deploys.

## Operational checklist

- **Graceful shutdown** — `application.yml` sets `timeout-per-shutdown-phase` from `SHUTDOWN_TIMEOUT` (default 20s). Set the deploy substrate's stop grace period above it so SIGTERM can drain in-flight requests. The Paketo launcher `exec`s the JVM; signal forwarding is native, no `tini`.
- **JVM memory** — do NOT set `MaxRAMPercentage`, `-Xmx`, or `-Xss` in Coolify env. Paketo's memory calculator handles them. Override only `BPL_JVM_HEAD_ROOM` if needed.

## Sources

- [Spring Boot 4 — AOT cache and CDS how-to](https://docs.spring.io/spring-boot/how-to/aot-cache.html)
- [Paketo — build an image from a compiled artifact](https://paketo.io/docs/howto/java/)
- [pack build reference](https://buildpacks.io/docs/for-platform-operators/how-to/integrate-ci/pack/cli/pack_build/)
- [Project descriptor reference](https://buildpacks.io/docs/reference/config/project-descriptor/)
- [Spring Boot 4 — Ahead-of-Time Processing](https://docs.spring.io/spring-boot/reference/packaging/aot.html)
- [OpenJDK JEP 483 — Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483)
- [paketo-buildpacks/spring-boot](https://github.com/paketo-buildpacks/spring-boot)
- [paketo-buildpacks/spring-boot#571 — BP_JVM_CDS_ENABLED deprecation track](https://github.com/paketo-buildpacks/spring-boot/issues/571)
