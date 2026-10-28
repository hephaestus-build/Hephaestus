---
"hephaestus": minor
---

You can install Hephaestus on Kubernetes with its Helm chart.
Workers run practice reviews and conversation turns in sandboxes on the cluster.
The single-node profile enables them. The install guide shows how to name the sandbox namespace and Kubernetes API endpoint.
That profile stops the worker before its replacement starts, so reviews and conversations pause during the drain and startup.
Each worker checks isolation at startup and every 15 minutes.

Each chart revision runs the database migration once, in a Job.
The 0.95.0 schema upgrade requires every old runtime role to stop first, as the migration guide describes.
A failed schema migration needs the documented recovery procedure. An image rollback cannot undo it.

The chart can back up bundled PostgreSQL to an S3-compatible bucket with pgBackRest.
Kubernetes 1.35 or later, a network plugin that enforces NetworkPolicy and a Gateway API controller are required.
Each release publishes the signed chart beside its images.

To move an existing Compose installation, use [Move from Compose to the Helm chart](https://docs.hephaestus.build/admin/compose-to-kubernetes).
Upgrade Compose to 0.95.0 first, then keep that version during the move.
