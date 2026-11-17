# ADR 0119: A workload-trained AOT cache needs a build design before the server image ships it

**Status:** Proposed
**Date:** 2026-11-17
**Authors:** Felix T.J. Dietrich
**Relates to:** [0067](0067-the-server-image-builds-with-buildpacks-and-cds.md)

## Context

The server image ships an AOT cache, `/workspace/application.aot` ([JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514)).
The buildpack writes it in a training run that stops at context refresh (`-Dspring.context.exit=onRefresh`).
That run loads the classes of the bean graph only. It loads no class that a request or a scheduler loads, and it records no method profiles.
[JEP 515](https://openjdk.org/jeps/515) adds method profiles to the cache.
The [Spring Framework](https://docs.spring.io/spring-framework/reference/integration/aot-cache.html) recommends a cache from "a portion of a production-like workflow" instead of `onRefresh`.

ADR 0067, update of 2026-11-17, rule 5, keeps the training run at `onRefresh`. It requires a separate decision for a cache trained on a workload.
This ADR records the options and what decides between them. It decides nothing.

### What a workload-trained cache measured

The maintainers measured two caches on 2026-11-17. These were the conditions:

- The image: the CI image of `main` at `ffc3eb52df`, with Spring Boot `4.1.2`, Liberica JRE `25.0.5` and the Paketo spring-boot buildpack `5.40.5`.
- The container: 4 CPUs and 3 GiB, the limits of the small profile, on one shared x86_64 host.
- The environment: the rendered small-profile server environment, with the agent runtime on and the chart's `JAVA_TOOL_OPTIONS`.
- The database: migrated and empty. Thus, sync and reviews did no work.
- The runs: five for each cache. The values are medians.

The two caches:

- **B1** is an `onRefresh` cache like the one that the image ships since #4636. It was trained with the chart's flight-recording flags.
- **B3** was trained by hand with the `prod` profile, the agent runtime on, and three rounds of warm-up, then stopped gracefully. The cache is 253 MB.

| Measure | B1 | B3 |
| --- | --- | --- |
| Ready (`docker run` to the first readiness `200`) | 16.2 s | 15.5 s |
| Metaspace used after 60 s | 65 MiB | 38 MiB |
| First `GET /api/identity-providers` | 23 ms | 15 ms |
| First `POST /webhooks/github` | 8 ms | 5.6 ms |
| Anonymous memory outside the heap, after a full GC | 513 MiB | 415 MiB |

The ready time gains 0.7 s. The first requests gain about one third. The memory gain is in Metaspace and anonymous memory, not in the heap.
The host had other tenants, so the ready times have a wide spread. The Metaspace and class numbers vary by less than 1 %.
Memory under review load was not measured.

### The real constraint

The workload needs PostgreSQL with a migrated schema.
`pack` gives the build no database. For this reason, the `cds-training` profile exists: it lets context refresh succeed without one.

A CI step that trains after `pack` and adds a layer changes the image after the build.
That breaks [Build once](../contributor/ci-cd.mdx#build-once): CI packages the server once, and the tested image is the shipped image.
The cache is valid only for the JVM, class path and module set of its training run ([JEP 483](https://openjdk.org/jeps/483)).
Thus, the cache cannot be trained apart from the image that runs it.

## Decision drivers

- The tested artifact is the shipped artifact. The release stays signed, with provenance and an SBOM for each artifact that runs.
- A self-hoster installs with the chart only. A missing or rejected cache makes the server slower, not dead (ADR 0067, update rule 4).
- The training uses synthetic data only. The cache contains no secret and no workspace data.
- The training is repeatable. A change of the workload is a reviewed change in the repository.
- The CI time that the training adds, against the measured gain.

## Considered options

1. **Keep `onRefresh`.** No change. The image keeps the B1 numbers.
2. **Train inside the image build.** A step of the build starts a throwaway PostgreSQL and migrates it. Then it runs the workload, before the image is complete. [`pack build`](https://buildpacks.io/docs/for-platform-operators/how-to/integrate-ci/pack/cli/pack_build/) can connect the build container to a network with `--network`.
   The image stays one artifact, built once.
   The build gets a database service, a workload driver and more time.
   The Paketo spring-boot buildpack starts its own training run. This ADR did not check if it can run a workload in place of `onRefresh`. If it cannot, a custom buildpack owns the training run, and the image depends on that buildpack.
3. **Train after the build, and ship the cache as a separate signed artifact.** A CI job starts the built image with a database, runs the workload, and publishes the cache. The job signs the cache and attests it to the image digest.
   The image does not change.
   The chart must fetch the cache, verify it, and mount it over `/workspace/application.aot`. A self-hoster must mirror and verify one more artifact.
   The JVM rejects a cache that does not match the image. The server then starts without a cache: slower, not dead.
4. **Train after the build, and add the cache as a layer.** This gives a second image that CI did not build with `pack`.
   It breaks Build once as written. The image tests and the provenance would have to move to the second image.

Options 2 and 3 differ in where the complexity goes: a database in the build, or a second artifact that the chart mounts.
The smaller of the two systems decides between them.
Option 1 stays correct if the measured gain does not close a target that the other changes leave open.

## Decision

None. The status stays `Proposed` until a measurement meets the revisit trigger, and an update records the choice.

## Consequences

- Good: the server keeps one artifact and no database in the build.
- Bad: the image does not get the first-request and memory gains of B3.

## Confirmation

Not enforced. This ADR decides nothing. ADR 0067 rule 5 and the review of `server/application/project.toml` keep the training run at `onRefresh`.

## Revisit trigger

The study proposes two targets at 4 CPUs.
The JVM is ready within 16 s, and the pod is ready within 5 s after the JVM.
The first request on each measured public path takes at most twice the warm latency and at most 50 ms.
Revisit when one target is not met after these changes:

- The cache loads in every run mode (#4636).
- Compact object headers ([JEP 519](https://openjdk.org/jeps/519)).
- Shorter probe periods in the chart.

Measure with the job **Server image startup** (`scripts/server-image-startup.ts`), and then choose an option.

## Sources

- [Spring Framework — AOT cache](https://docs.spring.io/spring-framework/reference/integration/aot-cache.html): recommends training on a production-like workflow, which is the reason for this ADR.
- [JEP 515 — Ahead-of-Time Method Profiling](https://openjdk.org/jeps/515): adds method profiles to the cache, which a workload records and `onRefresh` does not.
- [JEP 483 — Ahead-of-Time Class Loading & Linking](https://openjdk.org/jeps/483): the cache must match the JVM, class path and modules of its training run. Thus, the cache is trained with the image that runs it.
