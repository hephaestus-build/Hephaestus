#### 🔴 Repository capture and agent image upgrade

Deploy matching server, worker, `git-preparation` and agent images: the agent image now carries
runtime contract 3, and native Git runs in the new `git-preparation` image rather than in the
application image. The shipped Compose files hand every server and worker container that image's
digest from the verified release lock as `HEPHAESTUS_IMAGE_GIT_PREPARATION`; a deployment manifest of
your own must forward it too, digest-pinned wherever the agent image must be. Drain running reviews
before upgrading. Installed practices receive a new policy revision on source contract `1.1.0`;
historical practice revisions and observations remain unchanged.

Remove `GIT_TREE_MAX_FILES`, `GIT_TREE_MAX_TOTAL_SIZE` and `GIT_TREE_MAX_FILE_SIZE`. A review now
captures the whole repository at the reviewed commit together with the Git history reachable from it,
so the retired 32 MiB tree bound is no longer a capacity estimate: provision worker storage for
repository mirrors, per-attempt snapshots and sandbox input archives. A repository whose checkout plus
mirrored history exceeds `GIT_MAX_SNAPSHOT_BYTES` (8 GiB by default) is refused whole rather than
captured in part; raise it for larger monorepos. `GIT_MAX_CONCURRENT_INGESTIONS` (default 2) caps how
many captured commits a worker writes to PostgreSQL at once.

Review the expanded repository-history scope with your deployment's privacy owner. Files deleted from
the current checkout can remain accessible in history.

Remove `SANDBOX_DOCKER_CLI`: sandbox inputs and results travel through the authenticated worker
gateway, not through the Docker CLI or a host-mounted context directory.

Remove `PRACTICE_REVIEW_EXECUTION_CAPTURE_ENABLED`; private execution capture is no longer
supported. A review retains its admitted observations and their citation verdicts, not its inputs,
model requests or session transcripts, and archived transcripts from earlier releases are neither
admission verdicts nor replay evidence.

Remove `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS`; the content-addressed store and its retention sweep are
gone. Repository mirrors now live in worker-owned Docker volumes named `hephaestus-git-*`, and the
worker's `HEPHAESTUS_FABRIC_ROOT` holds only attempt folders and Git spool files. After the upgrade,
once no attempt from the previous release is still running, delete the retired `sources/` and `cas/`
directories and the previous layout's per-job `jobs/<job-id>/` directories under that root. Do not
remove active attempt folders (`jobs/<workspace-id>/<job-id>/`) or the new mirror volumes.
