# ADR 0109: The server compiles, tests and runs on Java 25 LTS

**Status:** Accepted
**Date:** 2026-11-13
**Authors:** Hephaestus maintainers

**Builds on:** [ADR 0043](0043-gradle-java-build.md) (Gradle owns the Java build), [ADR 0067](0067-the-server-image-builds-with-buildpacks-and-cds.md) (buildpacks and CDS), [ADR 0089](0089-pinned-node-and-pnpm-install-the-repository-and-vite-plus-runs-every-command.md) rule 10 (`.java-version` pins the JDK line)

## Context

The server ran on Java 21. One number in `.java-version` selects the JDK for Gradle, CI and Renovate.
`server/application/project.toml` restates it as `BP_JVM_VERSION` for the image.

`application.yml` turns on virtual threads for requests and several executors.
On Java 21, a virtual thread that blocks inside `synchronized` keeps its carrier ("pinning").
The code guards against this hazard with `CacheLoads`, with locks instead of monitors where it blocks, with a pinned-park gate in the integration tier, and with a chart setting that raises the virtual-thread scheduler's carrier limit.
Third-party code that blocks under a monitor, such as Tomcat's socket waits, stays outside these guards.
JDK 24 removed this hazard class ([JEP 491](https://openjdk.org/jeps/491)). JDK 25 is the next LTS release, and it contains JEP 491.

Spring recommends JDK 25 or later for production with Spring Framework 7.
The Paketo Spring Boot buildpack deprecates the CDS switch of ADR 0067 in favor of the AOT cache, which needs Java 25.

## Decision drivers

- Remove a hazard class at the platform level, not with guards that miss third-party code.
- Keep the build-once rule: the JDK line that tests the JAR is the JDK line that the image runs.
- Keep one home for the JDK line.
- Keep the change reversible with one revert.

## Considered options

1. **Stay on Java 21.** Updates continue until at least December 2029. The pinning guards stay load-bearing, and the AOT cache stays out of reach.
2. **Test on 21 and run the image on a 25 JRE.** The tests then run on another JVM than the image. This breaks the build-once rule.
3. **Compile with `--release 21` on JDK 25.** The bytecode keeps Java 21 compatibility, which nothing needs: a rollback restores the previous image with its own JRE. It adds a second number to keep equal.
4. **Move the JDK line to 25 everywhere (chosen).**
5. **Track the newest non-LTS release.** Each release gets about six months of updates, so the move repeats twice a year.

## Decision

**Option 4.** A reviewer can check each rule against the diff.

1. `.java-version` contains `25`. Gradle, `actions/setup-java` and Renovate read it. No file pins a JDK inline.
2. The image's JVM buildpack reads the JDK line from the JAR manifest (`Build-Jdk-Spec`, which Gradle writes from `.java-version`), so `server/application/project.toml` does not restate it. The JRE pin in `.github/ci-tools/buildpack-liberica-jre.json` belongs to that line, and its checksums come from the `buildpack.toml` of the Liberica buildpack in the pinned builder. `gate:toolchain` fails on drift.
3. `-Werror` and `-Xlint:all,-processing` stay. A new finding is fixed, not suppressed. Missing annotation metadata goes on the compile-only classpath.
4. The compiler arguments drop `-XDaddTypeAnnotationsToSymbol=true`. NullAway's JSpecify mode needs it only before JDK 22.
5. The pinned-park gate and `PinnedParks` stay. On 25 they detect the pinning that remains: a native frame, a class initializer that blocks, or a wait for class initialization.
6. `CacheLoads` and the locks stay. A comment that gives Java 21 pinning as the reason states the constraint that still holds, or goes.
7. The server uses no preview feature and no incubator module.
8. CDS stays as ADR 0067 decides. A separate change moves the image to the AOT cache and turns on compact object headers, with its own measurement, and updates ADR 0067. The training run and the runtime then need the same JVM options ([JEP 483](https://openjdk.org/jeps/483)).
9. A separate change removes `jdk.virtualThreadScheduler.maxPoolSize` from the chart. On 25 a Tomcat socket wait unmounts, so the gateway no longer needs it. Until then it still caps the carriers that the scheduler adds for pinned file I/O.

## Consequences

- Good: the server no longer depends on each library avoiding `synchronized` around I/O.
- Good: one Java line for the build, the tests and the image, the line that Spring names for production.
- Good: the AOT cache ([JEP 483](https://openjdk.org/jeps/483), [JEP 514](https://openjdk.org/jeps/514), [JEP 515](https://openjdk.org/jeps/515)) and compact object headers ([JEP 519](https://openjdk.org/jeps/519)) become available. Each needs its own measured decision.
- Good: Temurin 25 has updates until at least September 2031.
- Bad: each contributor installs a JDK 25, because Gradle does not download toolchains here.
- Bad: the first CI run after the change starts with a cold Gradle cache.
- Neutral: TLS defaults do not change. The SHA-1 ban in TLS 1.2 handshake signatures ([JDK-8340321](https://bugs.openjdk.org/browse/JDK-8340321)) already reached Java 21 in 21.0.10.
- Bad: on JDK 24 and later, Netty's native library load prints a JNI warning ([JEP 472](https://openjdk.org/jeps/472)). The test JVM and `bootRun` enable native access. The image starts the application from a class path, not with `java -jar`, so a JAR manifest entry does not enable it there. The AOT cache change decides how the image does.
- Bad: Lombok's annotation processor prints a `sun.misc.Unsafe` warning in javac ([JEP 498](https://openjdk.org/jeps/498)). JDK 26 makes that memory access throw by default.
- Bad: Spring 7 reads class metadata with the ClassFile API on Java 24 and later, and that reader retains more heap per application context than ASM ([spring-framework#37112](https://github.com/spring-projects/spring-framework/pull/37112) removed most of it; [#36978](https://github.com/spring-projects/spring-framework/pull/36978) is not released). With Spring's default of 32 cached test contexts, the unsharded integration tier ran out of its 4 GB heap on Java 25 (live set 3.8 GB; Java 21 levels off near 3.3 to 3.6 GB). The test `spring.properties` caps the cache at 24 contexts: the tier then passes on Java 25 with a live set near 3.3 GB and no slower.
- Good: the move found a test defect. Since JDK 23 the JDK `HttpServer` sends the headers of a chunked response only with its body ([JDK-6968351](https://bugs.openjdk.org/browse/JDK-6968351), release note [JDK-8331847](https://bugs.openjdk.org/browse/JDK-8331847)). The live-test stand-in for the model proxy forwarded with `transferTo`, which never flushes. It held streamed frames even on 21, and on 25 its client waited for the headers. It now flushes each read.

## Rollback

Revert the change. The JDK line, the image JVM and the JRE pin return together.
Hosts roll back with the [upgrade rollback procedure](https://docs.hephaestus.build/admin/upgrade#rollback). Each release image carries its own JRE, and the change has no migration.

## Confirmation

- `gate:toolchain` fails when Gradle, Renovate, a workflow, `BP_JVM_VERSION` or the JRE pin leaves the `.java-version` line.
- `-Werror` fails compilation on any lint finding.
- The integration tier fails on a pinned park of 20 ms or more.
- `ProxyStandInLifecycleTest` fails when the stand-in stops streaming a frame while the upstream stalls.

## Revisit trigger

- The next LTS release reaches general availability, and Spring Boot and the Paketo Liberica buildpack support it.
- A JDK 25 update breaks a test tier or the image build, and no fix is available.

## Sources

- [JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491): monitors unmount from JDK 24. The remaining pinning cases decide which guards stay.
- [Spring Framework Versions](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-Versions): Spring Framework 7 recommends JDK 25 or later for production.
- [paketo-buildpacks/spring-boot](https://github.com/paketo-buildpacks/spring-boot): `BP_JVM_CDS_ENABLED` is deprecated for `BP_JVM_AOTCACHE_ENABLED`, which needs Java 25.
- [NullAway JSpecify support](https://github.com/uber/NullAway/wiki/JSpecify-Support): JSpecify mode needs javac 22 or later, or the flag on 21. This is the basis of rule 4.
- [JDK-6968351](https://bugs.openjdk.org/browse/JDK-6968351) and release note [JDK-8331847](https://bugs.openjdk.org/browse/JDK-8331847): the `HttpServer` header change in JDK 23.
- [Gradle compatibility matrix](https://docs.gradle.org/current/userguide/compatibility.html): Gradle 9.1 and later run on and compile for Java 25.
- [JDK-8367675](https://bugs.openjdk.org/browse/JDK-8367675): the TLS SHA-1 change in Java 21.0.10, so it is not part of this move.
