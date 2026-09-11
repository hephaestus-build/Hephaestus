#### 🔴 Repository capture and agent image upgrade

Remove the retired `SANDBOX_DOCKER_CLI` setting; runtime transfers use the authenticated gateway.

Remove `GIT_TREE_MAX_FILES`, `GIT_TREE_MAX_TOTAL_SIZE`, and `GIT_TREE_MAX_FILE_SIZE` from deployment
configuration. Provision storage for complete captures, including reachable history, staging, and
sandbox input archives; the former 32 MiB default is no longer a capacity estimate.

Deploy matching server, worker, preparation and agent images with runtime contract 3. Sandboxes reach
the authenticated worker gateway, not the application server or a host-mounted context directory.
Drain running reviews before upgrading. Installed practices receive a new policy revision; historical
practice revisions and observations remain unchanged.

Review the expanded repository-history scope with your deployment's privacy owner. Files deleted
from the current checkout can remain accessible in history.

Remove `PRACTICE_REVIEW_EXECUTION_CAPTURE_ENABLED`; private execution capture is no longer supported.
After draining old attempts, remove the retired `execution/` directories under the configured worker
job storage. Existing archive manifests no longer retain CAS blobs. Do not treat archived transcripts
as admission verdicts or replay evidence.

Remove `HEPHAESTUS_FABRIC_GC_RETENTION_DAYS`. The CAS and replay retention sweep is removed;
after draining old attempts, remove the retired `cas/` and old per-job capture directories from
the configured fabric root. Do not remove active attempt folders or repository mirrors during an upgrade.
