#### 🔴 Stop every role before the Kubernetes-only sandbox cutover

Stop the server, worker, and webhook roles. Take a verified database backup. Apply the migration, then start all roles from the same release through the Helm chart. The old runtime must not run on the new schema.

Remove `HEPHAESTUS_SANDBOX_RUNTIME`, all `SANDBOX_DOCKER_*` keys, `DOCKER_GROUP_ID`, `SANDBOX_MEMORY_BYTES`, and `SANDBOX_PIDS_LIMIT`. The Docker sandbox backend and socket access are removed. The chart's Pod-kind resource table sets sandbox memory and storage; the node enforces the process limit.

The migration promotes version 8 review configurations and removes their unused `allowInternet: false` field. It preserves every other frozen field and rejects a version 8 row with a different internet-access value. Older snapshot formats remain unchanged. For rollback, stop every role and restore the database backup taken before the upgrade.

Local development runs the server and worker in the host JVM through `vp run dev`. Each worktree has its own restricted sandbox namespace and quota in the shared k3d cluster. Use `vp run dev:kubernetes` for the one on-demand chart qualification release. See the contributor local-development guide.
