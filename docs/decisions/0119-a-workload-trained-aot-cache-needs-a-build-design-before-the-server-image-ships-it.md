# ADR 0119: A workload-trained AOT cache needs a build design before the server image ships it

**Status:** Accepted
**Date:** 2026-11-17
**Authors:** Felix T.J. Dietrich
**Relates to:** [0067](0067-the-server-image-builds-with-buildpacks-and-cds.md), [0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md)

## Context

The server image ships an AOT cache, `/workspace/application.aot` ([JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514)).
The buildpack writes it in a training run that stops at context refresh (`-Dspring.context.exit=onRefresh`).
The cache holds only the classes and the method profiles ([JEP 515](https://openjdk.org/jeps/515)) of context refresh.
The code that a request or a scheduler runs after refresh is not in it.
The [Spring Framework](https://docs.spring.io/spring-framework/reference/integration/aot-cache.html) recommends a cache from "a portion of a production-like workflow" instead of `onRefresh`.

ADR 0067, update of 2026-11-17, rule 5, kept the training run at `onRefresh` and asked for a separate decision on a cache trained on a workload.
This ADR is that decision.

### What a workload-trained cache measured

The maintainers measured three caches on 2026-11-17. These were the conditions:

- The image: the CI image of `main` at `ffc3eb52df`, with Spring Boot `4.1.2`, Liberica JRE `25.0.5` and the Paketo spring-boot buildpack `5.40.5`.
- The container: 4 CPUs and 3 GiB, the limits of the small profile, on one shared x86_64 host.
- The environment: the rendered small-profile server environment, with the agent runtime on and the chart's `JAVA_TOOL_OPTIONS`. Two items were different from the chart: `HEPHAESTUS_DATABASE_ALLOW_INSECURE_REMOTE=true`, and no Kubernetes API.
- The database: migrated and empty. For this reason, sync and reviews did no work.
- The runs: five for each cache, unless the table gives another number. The values are medians.

The three caches:

- **C1** was trained with `--add-modules=jdk.jfr` only. The cache that the image has shipped since #4636 uses these training options.
- **B1** is an `onRefresh` cache that was trained with the chart's flight-recording flags.
- **B3** was trained by hand with the `prod` profile, the agent runtime on, and three rounds of warm-up, then stopped gracefully. The cache is 253 MB.

| Measure | C1 (3 runs) | B1 | B3 |
| --- | --- | --- | --- |
| Ready (`docker run` to the first readiness `200`) | 15.7 s | 16.2 s | 15.5 s |
| Metaspace used after 60 s | 61 MiB | 65 MiB | 38 MiB |
| First `GET /api/identity-providers` | not measured | 23 ms | 15 ms |
| First `POST /webhooks/github` | not measured | 8 ms | 5.6 ms |
| Anonymous memory outside the heap, after a full GC | not measured | 513 MiB | 415 MiB |
| Mapped cache (shared class space) | not measured | 196 MB | 232 MB |

Against C1, the ready time of B3 gains about 0.2 s. Against B1, the first requests of B3 take about one third less time (−35 % and −30 %).
The host had other tenants, so the ready times have a wide spread. The Metaspace and class numbers vary by less than 1 %.
Memory under review load was not measured.

### The real constraint

The workload needs PostgreSQL with a migrated schema.
`pack` gives the build no database. For this reason, the `cds-training` profile exists: it lets context refresh succeed without one.

[Build once](../contributor/ci-cd.mdx#build-once) covers the server JAR. ADR 0092 covers the image:

- CI builds each image once (rule 1).
- Each image has signed provenance and an SBOM (rule 5).
- One lock names each application image (rule 6).

The cache is valid only for the JRE, class path and module set of its training run ([JEP 483](https://openjdk.org/jeps/483)).
It does not have to come from the build step. A training run on the built image gives a valid cache.
A CI step that adds the cache as a layer makes a second image. ADR 0092 then requires that second image to be the image that CI scans, signs and locks.

## Decision drivers

- The tested artifact is the shipped artifact. Each artifact that runs is signed, with provenance and an SBOM (ADR 0092).
- A self-hoster installs with the chart only. A missing or rejected cache makes the server slower, not dead (ADR 0067, update rule 4).
- The training uses synthetic data only. The cache contains no secret and no workspace data.
- The training is repeatable. A change of the workload is a reviewed change in the repository.
- The CI time that the training adds, against the measured gain.

## Considered options

### Option 1: keep `onRefresh` (chosen)

No change. The image keeps the C1 numbers.

### Option 2: train inside the image build (declined for now)

A step of the build starts a throwaway PostgreSQL and migrates it. Then it runs the workload, before the image is complete.
[`pack build`](https://buildpacks.io/docs/for-platform-operators/how-to/integrate-ci/pack/cli/pack_build/) can connect the build container to a network with `--network`.

- The image stays one artifact, built once.
- The build gets a database service, a workload driver and more time.
- The Paketo spring-boot buildpack starts its own training run. This ADR did not check if it can run a workload in place of `onRefresh`. If it cannot, a custom buildpack owns the training run, and the image depends on that buildpack.

### Option 3: train on the built image, and ship the cache as a separate signed artifact (declined for now)

A CI job starts the built image with a database, runs the workload, and publishes the cache.
The job signs the cache and attests it to the image digest.
The job **Server image startup** starts the image with a migrated database already, so the training can run in it.

- The image does not change.
- The cache needs its own entry in `security/release-images.json`. Its evidence must pass the release evidence gate (ADR 0092 rule 4). The lock must name its digest (rule 6).
- A test must start the image and the cache together with `-XX:AOTMode=on`. Then a cache that the JVM rejects fails CI.
- The chart must fetch the cache, verify it, and mount it over `/workspace/application.aot`. A self-hoster must mirror and verify one more artifact.
- In production, the JVM rejects a cache that does not match the image. The server then starts without a cache: slower, not dead.

### Option 4: train on the built image, and add the cache as a layer (declined for now)

This gives a second image that `pack` did not build.

- CI must scan, sign, attest and lock that second image (ADR 0092 rules 1, 5 and 6).
- The image tests must run on the second image, not on the image from `pack`.

### What decides between options 2 and 3

Options 2 and 3 put the complexity in different places: a database in the build, or a second artifact that the chart mounts.
If the revisit trigger fires, two measures decide between them:

- The CI time that the training adds to each merge group.
- The number of artifacts that a release signs and a self-hoster verifies.

## Decision

1. The training run stays at context refresh until a measured target misses. § Revisit trigger names the targets and the measurement.
2. When a target misses, an update of this ADR chooses option 2, 3 or 4 with the measures above.

## Consequences

- Good: the server keeps one artifact and no database in the build.
- Good: the release signs and locks no cache artifact apart from the image.
- Bad: the image does not get the first-request and memory gains of B3.
- Size, for each option that ships B3: the cache file grows from about 200 MB to 253 MB. The mapped cache grows from 196 MB to 232 MB.

## Confirmation

`scripts/server-image-startup.ts` starts the packaged image with `-XX:AOTMode=on`. It fails when the JVM rejects the cache that the training run of the image wrote. It also fails when the JVM is not ready within its startup budget.
No check fails when the training run leaves context refresh. Review of `server/application/project.toml` catches that change.

## Revisit trigger

The study of 2026-11-17 proposes these targets at 4 CPUs:

- **T1:** the JVM is ready within 16 s, and the pod is ready within 5 s after the JVM.
- **T3:** the first request on each measured public path takes at most 50 ms. It also takes at most twice the warm latency.

T3 is already short.
B1 took 23 ms for the first `GET /api/identity-providers`. The warm latency was 8.7 ms, measured only on a run without a cache. That is about 2.6 times.
B3 took 15 ms, about 1.7 times.

Revisit when T1 or T3 is not met after these changes:

- The cache loads in every run mode (#4636).
- Compact object headers ([JEP 519](https://openjdk.org/jeps/519)).
- Shorter probe periods in the chart.

The job **Server image startup** (`scripts/server-image-startup.ts`) measures the JVM ready time against a budget.
No job measures the pod ready time or the latency of the first request. The numbers above for T3 come from the study, not from CI.
A measurement of T3 and of the pod part of T1 needs a new step before a target can count as missed.

## Sources

- [Spring Framework — AOT cache](https://docs.spring.io/spring-framework/reference/integration/aot-cache.html): recommends training on a production-like workflow, which is the reason for this ADR.
- [JEP 515 — Ahead-of-Time Method Profiling](https://openjdk.org/jeps/515): the cache holds the method profiles of the training run. A workload records the profiles of requests and schedulers. `onRefresh` records only those of context refresh.
- [JEP 483 — Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483): the cache must match the JRE, class path and modules of its training run. A training run on the built image meets this rule.
