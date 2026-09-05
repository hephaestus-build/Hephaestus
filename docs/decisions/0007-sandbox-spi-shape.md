# ADR 0007: Sandbox SPI shape — sealed VolumeMount + typed NetworkPolicy

**Status:** Accepted
**Date:** 2026-05-20
**Authors:** Server foundations epic (#1097)

## Context

The sandbox SPI (`SandboxSpec`, `NetworkPolicy`, `InteractiveSandboxSpec`) needs to
support multiple adapters over time: today's Docker tar-injection adapter, a future
Kubernetes adapter, possibly Firecracker / microVM for hostile code.

The existing `Map<String, String> volumeMounts` cannot express read-only flags, size
limits, or secret-vs-config semantics. `NetworkPolicy.llmProxyUrl` was an unvalidated
`String`.

## Decision drivers

- Reserve the right abstraction *only* when a consumer exists; YAGNI on the rest.
- Keep the SPI evolvable: a sealed interface lets a future adapter add variants with
  compiler-enforced exhaustive handling.
- Validate URLs at construction time so misconfiguration fails fast (including YAML bind).

## Considered options

1. **Sealed `VolumeMount` with `HostPathMount` as the only variant today** — type system
   reserves the seal; future variants land in the same PR as the first consumer.
2. **Sealed `VolumeMount` with all 4 K8s-aligned variants pre-shipped** — pre-shipped
   variants have zero consumers; the supposed compiler-enforced exhaustive switch never
   fires (`SandboxSpec.volumeMounts` is still `Map<String,String>`). Speculative
   generality.
3. **Keep `Map<String, String>`; defer the seal** — every future adapter epic pays the
   migration cost.

## Decision

Option 1. `sealed interface VolumeMount permits HostPathMount`. Future variants
(`EmptyDirMount`, `ConfigMapMount`, `SecretMount`) land alongside their first consumer
(typically the K8s adapter). `SandboxSpec.volumeMounts` stays `Map<String, String>` until
a non-HostPath consumer materialises.

`NetworkPolicy.llmProxyUrl` keeps its `String` type but gains
absolute-`http(s)` validation in the compact constructor — including under
`@ConfigurationProperties` binding. `gitProxyUrl` is **not** added; it had no consumers
in this epic.

`WorkerAuthProvider` SPI explicitly **not** shipped — the BYO-runner auth model needs
real requirements (two-stage identity bootstrap, runner registration tokens, scoped
credentials per industry research) that don't exist yet.

## Consequences

- `DockerSandboxAdapter` continues to dispatch on `Map<String, String>` — no behavior
  change.
- The K8s adapter epic (or whoever lands the first non-HostPath consumer) adds variants
  in the same PR, migrates `SandboxSpec.volumeMounts` to `List<VolumeMount>`, and
  exhaustive-switches on the seal.
- `NetworkPolicy` construction fails-fast on malformed `llmProxyUrl` instead of
  surfacing the error during the first sandbox request.

## Revisit trigger

The first non-`HostPathMount` volume mount needs a real consumer in production code; or
the BYO-runner epic surfaces a concrete auth model that needs an SPI; or a new
`NetworkPolicy` field starts seeing wide use and validation gaps surface.

## Update — 2026-09-03 (issue #1719)

[ADR 0041](0041-compose-1x-kubernetes-2.md) supersedes the Decision's `VolumeMount` portability
rationale and its withheld `WorkerAuthProvider` SPI, and the Revisit trigger's first two clauses:
ADR 0041 withdraws the BYO remote-worker purpose, so no BYO-runner auth model will surface.
A driver only launches, observes, kills, removes and lists; the workspace moves as one tar and
sandboxes take no host mounts, so the first non-`HostPathMount` consumer this ADR was reserving the
seal for will not arrive. No `VolumeMount` type exists; `SandboxSpec.volumeMounts` is the
`Map<String, String>` of option 3.
