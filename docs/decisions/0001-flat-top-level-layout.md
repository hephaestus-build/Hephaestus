# ADR 0001: Flat top-level layout

**Status:** Accepted (amended 2026-09-17 — `server/` is a two-module Gradle root per [0032](0032-generated-clients-build-boundary.md))
**Date:** 2026-05-20
**Authors:** Server foundations epic (#1097)

## Context

The repo's server-side code lived under `server/application-server/` while a separate
top-level deployable sat beside it. The nested `server/application-server/` was awkward in every
developer interaction: search results, CI workflow filters (`server/application-server/**`),
Dockerfile contexts, IDE workspace definitions, `cd server/application-server` in scripts.

With epic #1097 formalising the two-runtime-role split (`server` + `worker` from one JAR), the
redundant nesting became indefensible. We also wanted to stop fighting the weird path before the
rename merge bills compounded on top of every concurrent feature branch.

## Decision drivers

- DX: every command + every search result includes the path.
- Operational clarity: top-level deployables sit visibly as siblings, not buried under a parent.
- Future-proof: additional top-level services can drop in as siblings without rearranging.

## Considered options

1. **Flat top-level** — `server/`, `webapp/`, optional sibling deployables at repo root.
   - ✓ Concise paths, deployables visibly equal.
   - ✗ "server" is a generic name; conversations need disambiguation.
2. **`server/server/`** — keep nested with the server module re-named to match the parent.
   - ✗ Stutter in every path reference; `cd server/server` is a typo trap; `COPY
     server/server/target/*.jar` reads like a generated test fixture.
3. **`services/server/`** — Nx/TurboRepo-style parent grouping.
   - ✓ Scales cleanly when 5+ deployables exist.
   - ✗ Over-organisation at 2 deployables; adds a layer without buying anything today.

## Decision

Adopt option 1 (flat top-level). `server/application-server/` → `server/`.

## Consequences

- All CI workflow filters, Dockerfile contexts, scripts, and IDE configs needed one-shot updates
  (~80 files; mechanical, no logic change).
- Docker image names, Compose service names, Traefik labels, and the
  `generate:api:application-server` package script are intentionally NOT renamed in this epic —
  they're deploy-side concerns with a rollback story and live in a follow-up issue.
- The current top-level layout is: `server/`, `webapp/`, `docs/`. The webhook receiver runs from
  the `server/` artifact under the `webhook` Spring profile and ships as a separate
  `webhook-server` container from the same image — see **ADR 0008**.

## Revisit trigger

The repo grows to 5+ top-level deployables, or someone proposes a non-trivial `services/`
reorganisation with concrete scaling justification.

## Update — 2026-09-17

Corrects § Decision and § Consequences on where the code lives and what is shipped from it.

- `server/` is a Gradle multi-project root with two modules, `server/application/` and
  `server/generated-clients/` (`server/settings.gradle.kts`); the nesting is the build boundary
  [ADR 0032](0032-generated-clients-build-boundary.md) decided and
  [ADR 0043](0043-gradle-java-build.md) builds, not the organisational nesting this ADR removed.
- The repository root also carries `scripts/`, `docker/` and `load-tests/`.
- Four images are published (`.github/workflows/cicd.yml`, `ci-docker-build.yml`):
  `application-server` from `server/`, `webapp` from `webapp/Dockerfile`, `postgres` from
  `docker/postgres/Dockerfile` and `agent-pi` from `docker/agents/pi/Dockerfile`; the two under
  `docker/` are build contexts, not top-level siblings.
- The `generate:api:application-server` script is `generate:api:specs` and `generate:api:client`
  (`vite.config.ts`).
- The image name is corrected in [ADR 0008](0008-webhook-runtime-role.md) § Update 2026-09-17; the
  Compose service names `application-server` and `webhook-server` stand (`docker/compose.app.yaml`,
  `docker/compose.core.yaml`).
