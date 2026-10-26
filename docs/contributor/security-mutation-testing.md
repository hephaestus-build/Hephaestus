---
sidebar_position: 9
title: Security mutation testing
description: How PIT mutation testing is scoped to security-boundary classes, and what the advisory result does and does not prove.
---

# Security mutation testing

PIT mutates a curated set of security-boundary classes and runs their focused unit tests. The
advisory result measures whether those tests reject injected changes.
It is not a security guarantee or a mutation-score gate.

## Run the suite

The **Security / Mutation testing** workflow runs when a pull request changes an affected security
area or the suite's build inputs. It also supports manual dispatch. Run the same analysis locally
with JDK 21:

```bash
vp run test:server:mutation
```

The command invokes the native PIT task, which builds its own prerequisites. Analysis always reruns.
Unchanged compilation can use Gradle's cache. It fails if any
Gradle task fails, the report is missing or invalid, or PIT leaves a mutation in a technical or
incomplete state. The job summary reports timing and outcomes. PIT's HTML/XML reports and a Markdown
summary are written below the application module's `build/reports/pitest` directory and uploaded by
the workflow even on failure.

The command evaluates the full target set without incremental analysis.
In CI, a separate step first compiles the application and its tests, with a limit of ten minutes.
The command then has its own limit of eight minutes, and the job has a limit of twenty minutes.
Local runs have no wrapper timeout.

CI compiles the tests without Error Prone and NullAway (`testAnalysis=false`), because the analysis does not change their bytecode.
The [Quality → App Server](ci-cd.mdx#build-once) job runs that analysis.

## Triage

The pull-request author reviews reported survivors and uncovered mutations in affected code. The
affected server CODEOWNERS review any accepted classification.

- Add a public-behavior test when a mutant exposes an unverified contract.
- Explain an accepted equivalent or unproductive mutant in the pull-request description instead of
  asserting private call order or implementation details.
- Remove unwired code instead of building mutation tests around it.
- Treat technical and incomplete statuses as an invalid run, never as killed mutations.

[Issue #1498](https://github.com/hephaestus-build/Hephaestus/issues/1498) records the suite's evaluation and
the decision to keep it non-required and advisory.

## Further reading

- [PIT Gradle plugin](https://github.com/szpak/gradle-pitest-plugin)
- [Mutation testing at Google](https://testing.googleblog.com/2021/04/mutation-testing.html)
