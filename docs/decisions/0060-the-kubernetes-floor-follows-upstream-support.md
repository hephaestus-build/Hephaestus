# ADR 0060: The Kubernetes floor follows upstream support

**Status:** Accepted (amended 2026-10-28 — sandbox nodes need user namespaces, [ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md))
**Date:** 2026-10-26
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0041](0041-compose-1x-kubernetes-2.md) § Deployment. This decision replaces its floor of 1.34.

## Context

The Kubernetes project maintains three minor versions at a time and ends each one on a published date ([release policy](https://kubernetes.io/releases/)).
After that date, a minor gets no security fix.
The chart refused a cluster below 1.34 (`kubeVersion: ">=1.34.0-0"`).
Kubernetes 1.34 reaches its end of life on 2026-10-27.
Hephaestus supports no version that its upstream no longer maintains.

## Decision

The chart supports the minor versions that the Kubernetes project maintains.
Today these are 1.35, 1.36 and 1.37.
The chart's `kubeVersion` is `>=1.35.0-0`.

When upstream ends a minor, the next Hephaestus release raises the floor to the oldest maintained minor.
CI installs and upgrades the chart on the floor and on the latest minor.

A cluster at an older minor must be upgraded before Hephaestus runs on it.

## Consequences

- An operator plans a Kubernetes upgrade at least once a year to stay on a supported Hephaestus release.
- The chart can use what the maintained minors have in common, without workarounds for older ones.
- The chair's clusters at 1.30 and 1.33 are upgraded before Hephaestus runs there. `parma` runs k3s 1.35 and is supported.

## Update — 2026-10-28: sandbox nodes need user namespaces ([ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md))

Every sandbox Pod sets `hostUsers: false`. A sandbox node therefore needs Linux 6.3 or later and a container runtime with support for user namespaces, such as containerd 2.0 or later.
A node without support fails closed with a named reason, and the k3s installer's preflight checks the node before the install.
The Kubernetes floor of this decision stays.
