# ADR 0067: The server image builds with Paketo Buildpacks and Application CDS

**Status:** Accepted
**Date:** 2026-05-19
**Authors:** Felix T.J. Dietrich
**Amended by:** [ADR 0119](0119-a-workload-trained-aot-cache-needs-a-build-design-before-the-server-image-ships-it.md)

This decision was first recorded as a contributor page. It moved here unchanged, so that every decision lives in `docs/decisions/`.

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

The chart's runtime `SPRING_PROFILES_ACTIVE` overrides the default that the buildpack sets.
Paketo writes `env.launch/<KEY>.default`, which yields to the runtime environment.
The [build-only profile boundary](https://docs.hephaestus.build/contributor/local-development#build-only-profiles) keeps runtime bootstrap out of training.
It rejects a production/build-profile combination.
Authentication types remain available for class loading without configured deployment secrets.

The build uses the run image without changes.
The server needs no `git` binary.
`GitRepositoryManager` uses JGit.

## Dependency scan coverage

The dependency security gate checks the server runtime and the Gradle plugin classpath separately.
The plugin classpath includes the Spring Boot buildpack platform and the GraphQL and OpenAPI generators.
Its native Gradle lockfile is included in the checkout scan and the submitted GitHub dependency graph.
A container scan does not check these build dependencies.
See [dependency security controls](https://docs.hephaestus.build/contributor/ci-cd#security-and-supply-chain-controls) for the scan inputs and policy.

CI builds the image with `pack`, not Gradle's `bootBuildImage` task.
The plugin classpath still takes part in the security gate even when that task is not used.

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

The Helm chart probes the actuator over HTTP (`hephaestus.probes` in `deploy/chart/hephaestus/templates/_helpers.tpl`).
Only a container runtime outside Kubernetes needs a container `HEALTHCHECK`.
`run-tiny` has no shell or wget.
`builder-noble-java-tiny` includes no probe.
Thus, `server/application/project.toml` names an explicit buildpack group.

A named group **replaces** the builder's default order.
The `java` composite (`paketo-buildpacks/java`) must come before `docker://paketobuildpacks/health-checker`.
`BP_HEALTH_CHECKER_ENABLED=true` enables the health-checker.
It supplies the static, shell-free `thc` binary at `/workspace/health-check`.

Such a runtime invokes this binary as an exec-form `HEALTHCHECK`. No chart template or workflow invokes it.
`THC_PORT` and `THC_PATH` select actuator liveness/readiness.
The buildpack adds no `health-check` process type.
Thus, each probe starts no JVM.

## Rollback

The Dockerfile path builds from the checkout, not from the packaged JAR.
The build-once guarantee does not apply until that path also downloads `application-artifact`.

1. Add a `Dockerfile` for `server/application` again.
2. Add an `application-server` target to `docker/docker-bake.hcl`, and remove the Buildpacks steps from `.github/workflows/reusable-docker-build.yml`.
3. Use the [upgrade rollback procedure](https://docs.hephaestus.build/admin/upgrade#rollback).

Detection uses an error spike in the logs of the new `service.version`.
No shipped alert watches `application_ready_time_seconds`.

## Operational checklist

`application.yml` sets `timeout-per-shutdown-phase` from `SHUTDOWN_TIMEOUT` (default 20s).
The Paketo launcher `exec`s the JVM.
It forwards signals natively, without `tini`.
Paketo's memory calculator handles JVM memory parameters.

1. Set the deployment system's stop grace period above the shutdown timeout so SIGTERM can drain in-flight requests.
2. Do not set `MaxRAMPercentage`, `-Xmx`, or `-Xss` in the runtime environment of the chart.
3. If necessary, override only `BPL_JVM_HEAD_ROOM`.

## Confirmation

`scripts/ci-contract-server.test.ts` fails when the image build does not call `pack build` with the descriptor and the pinned run image.
It also fails when a workflow uses `bootBuildImage`.
CDS on and AOT off are not enforced. Review of `server/application/project.toml` catches a change.

## Sources

- [Spring Boot 4 — AOT cache and CDS how-to](https://docs.spring.io/spring-boot/how-to/aot-cache.html)
- [Paketo — build an image from a compiled artifact](https://paketo.io/docs/howto/java/)
- [pack build reference](https://buildpacks.io/docs/for-platform-operators/how-to/integrate-ci/pack/cli/pack_build/)
- [Project descriptor reference](https://buildpacks.io/docs/reference/config/project-descriptor/)
- [Spring Boot 4 — Ahead-of-Time Processing](https://docs.spring.io/spring-boot/reference/packaging/aot.html)
- [OpenJDK JEP 483 — Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483)
- [paketo-buildpacks/spring-boot](https://github.com/paketo-buildpacks/spring-boot)
- [paketo-buildpacks/spring-boot#571 — BP_JVM_CDS_ENABLED deprecation track](https://github.com/paketo-buildpacks/spring-boot/issues/571)

## Update — 2026-11-17: the image ships an AOT cache that every run mode loads

On Java 25, the spring-boot buildpack 5.40 treats `BP_JVM_CDS_ENABLED` as `BP_JVM_AOTCACHE_ENABLED`.
Its training run writes an AOT cache, `/workspace/application.aot` ([JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514)), not `application.jsa`.
The launcher adds `-XX:AOTCache=application.aot`, not `-XX:SharedArchiveFile`.
§ What the build does describes the CDS archive of Java 21.

The cache loads only if the training run and the production run resolve the same modules.
Since #4580, the chart starts the server with `-XX:StartFlightRecording`, which adds the module `jdk.jfr` ([JDK-8375469](https://bugs.openjdk.org/browse/JDK-8375469)).
The training run did not add it. Thus, the JVM rejected the cache at each start and loaded every class into Metaspace.
On staging, Metaspace use then exceeded the memory calculator's cap, and the server crash-looped.

The small profile at 4 CPUs measured the effect of a cache that loads. Metaspace use was 65 MiB instead of 205 MiB. The server was ready in 16.2 s instead of 20.4 s.

The decision now has these rules:

1. The image owns the JVM options that the cache depends on. `server/application/project.toml` gives the training run and every launch process the same module set, `--add-modules=jdk.jfr`. The `migrate` run mode is one of these processes.
2. The chart may add system properties, heap-independent tuning and recording settings. It adds no module, agent or object-layout option.
3. A CI job starts the packaged image with the chart's rendered options and `-XX:AOTMode=on`. A rejected cache fails the merge group.
4. Production keeps `-XX:AOTMode=auto`. A rejected cache makes the server slower, not dead. `BPL_JVM_LOADED_CLASS_COUNT` sizes Metaspace for a run without the cache, and `HephaestusMetaspaceNearLimit` fires at 80 % of the cap.
5. The training run stays at context refresh: [ADR 0119](0119-a-workload-trained-aot-cache-needs-a-build-design-before-the-server-image-ships-it.md).

Consequences:

- Good: the server starts about 21 % sooner and uses about 68 % less Metaspace, as measured. A rejected cache fails CI and is no longer a silent regression.
- Bad: a run of the image without `jdk.jfr` cannot use the cache. A local experiment must keep the module set.
- Bad: the buildpack starts the training run with `TRAINING_RUN_JAVA_TOOL_OPTIONS` as its whole environment. Thus, that variable also names the `cds-training` profile.

The job **Server image startup** (`scripts/server-image-startup.ts`) confirms rules 3 and 4.
`scripts/ci-contract-images.test.ts` fails when the training run and the launch append name different modules.
Revisit this update when JDK-8375469 ships in the JDK line of `.java-version`, or when [JEP 516](https://openjdk.org/jeps/516) matters for a move to ZGC.

## Update — 2026-11-17: compact object headers

The image also uses compact object headers ([JEP 519](https://openjdk.org/jeps/519)).
The cache records the object-header layout of its training run, so rule 1 of the update above now covers that layout too.
`server/application/project.toml` sets `-XX:+UseCompactObjectHeaders` for the training run and for every launch, beside `--add-modules=jdk.jfr`.
`scripts/ci-contract-images.test.ts` fails when the two settings differ.
