---
"hephaestus": patch
---

The release history on the instance overview no longer shows a false rollback when an older server restarts during a rolling update.
On Kubernetes, each rollout of the server now appears once, with the revision that Kubernetes gave it. A rollback through `helm rollback` or `kubectl rollout undo` is a new revision, so it appears as the newest entry, even when it runs an earlier release.
The server reads its revision with `get` on Pods and ReplicaSets in the release namespace. The chart grants this read-only access when it gives the server the Kubernetes API: with sandboxes, with backups, or with `networkPolicy.kubernetesApi` alone.
Without the Kubernetes API, each server start appears as one entry without a revision.
The server reads its revision when it becomes ready and then every minute. An entry can appear up to a minute late, and a revision that no server read is missing, which leaves a gap in the revisions.
The history starts with this release: entries that earlier releases recorded stay in the database but are no longer shown.
