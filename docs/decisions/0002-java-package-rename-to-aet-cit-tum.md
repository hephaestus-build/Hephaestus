# ADR 0002: Rename Java base package to `de.tum.cit.aet.hephaestus`

**Status:** Accepted (amended 2026-09-17 — deploy domain moved, package kept)
**Date:** 2026-05-20
**Authors:** Server foundations epic (#1097)

## Context

The Java code under `server/src/main/java/` used `de.tum.in.www1.hephaestus` — a legacy
TUM naming convention referencing the "Informatics, chair www1" structure. That structure
has since been reorganized into CIT (Computation, Information and Technology) with
multiple chairs at `*.cit.tum.de`. The legacy package name no longer reflects organisation
or ownership.

The current `hephaestus` host URL (`application.yml#host-url`) is
`hephaestus.ase.cit.tum.de` (Applied Software Engineering chair). The research-group
website the maintainers identify with is `aet.cit.tum.de` (Applied Education Technologies).
The two TUM chair acronyms aren't interchangeable; aligning the package with one of them
is an explicit ownership statement.

## Decision drivers

- The legacy `de.tum.in.www1` name is unambiguously stale (org structure no longer exists).
- One-shot rename minimises blast radius.
- Modulith adoption is the right moment (we already touch every `package-info`).

## Considered options

1. **Rename now to `de.tum.cit.aet.hephaestus`** — align with the current chair.
2. **Defer indefinitely** — accept the legacy name; nothing breaks technically.
3. **Rename to a generic name** like `com.hephaestus` — eliminates the dependency on TUM chair structure entirely.

## Decision

Adopt option 1. Rename `de.tum.in.www1.hephaestus` → `de.tum.cit.aet.hephaestus` across
all 1,121 Java source files in one mechanical commit.

## Consequences

- One large but isolated commit touches every Java file (every `package` and `import` line).
- Open feature branches will conflict; we coordinate the merge of the rename PR ahead of feature merges.
- Forks / external consumers (none today) would need to rebase — acceptable cost.
- Dynamic FQN references (Spring config `logging.level` keys, OpenAPI codegen targets, achievements-schema JSON `$ref`s) were swept in the same commit.
- `git log --follow` still tracks individual files across the rename.
- The host URL in `application.yml` still points at `hephaestus.ase.cit.tum.de`. Aligning
  it with the chosen package (e.g. `hephaestus.aet.cit.tum.de`) is a deploy-side decision
  with DNS, certificate, and link-rot implications — tracked separately from this rename.

## Revisit trigger

A future chair reorganisation, or a decision to move the deploy domain (which would close
the package-vs-host-URL gap noted above).

## Update — 2026-09-17

Supersedes the last bullet of § Consequences and answers the revisit trigger: the deploy domain
moved, and the package stays `de.tum.cit.aet.hephaestus`.

- The hosted deployment is `https://hephaestus.build` and the GitHub organisation is
  `hephaestus-build` (`README.md`); `hephaestus.host-url` in
  `server/application/src/main/resources/application.yml` defaults to
  `${APPLICATION_HOST_URL:http://localhost:4200}`, so no application host URL names a TUM chair.
- `aet.cit.tum.de` survives as the base package, as the `$id` of the artifact-source and
  practice-catalog JSON schemas (`server/application/src/main/resources/contracts/`,
  `practices/default-catalog.schema.json`, read by `scripts/validate-artifact-source-contracts.ts`),
  as the commented `HEPHAESTUS_AUTH_ISSUER` example in `server/.env.example`, and in
  `docker/.env.example` as the `APP_HOSTNAME` example (`hephaestus.cit.tum.de`) and as the
  deployment the `tumaet` legal profile describes (`hephaestus.aet.cit.tum.de`).
