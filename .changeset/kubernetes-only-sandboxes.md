---
"hephaestus": minor
---

Sandbox work now runs only in Kubernetes. Reviews and Heph use the chart worker and the cluster's sandbox resource limits. Large sandbox command output now arrives with its exit status and stays within the output limit.

**Operators:** Stop every role and take a verified database backup before this upgrade. Run the new release through the Helm chart. Remove `HEPHAESTUS_SANDBOX_RUNTIME`, all `SANDBOX_DOCKER_*` keys, `DOCKER_GROUP_ID`, `SANDBOX_MEMORY_BYTES`, and `SANDBOX_PIDS_LIMIT`. The migration removes the unused internet-access flag from version 8 review configurations without changing their frozen models or prices. To return to the previous release, restore the backup.
