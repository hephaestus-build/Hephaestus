---
"hephaestus": minor
---

You can now install Hephaestus on a Kubernetes cluster with its Helm chart. Workers run practice reviews and Heph in sandboxes on the cluster. The single-node profile turns them on: you name a sandbox namespace and the cluster's API endpoint, and the install guide shows how to find both. An upgrade starts the new workers before the old ones drain. Each worker checks at its start, and then every 15 minutes, that the cluster isolates sandboxes. Each upgrade migrates the database once, in a Job, while the previous release keeps serving, and rolls back when the migration fails. The chart can back up its bundled PostgreSQL with pgBackRest to an S3-compatible bucket. Kubernetes 1.34 or later, a network plugin that enforces NetworkPolicy, and a Gateway API controller are required. Each release publishes the signed chart beside its images.
