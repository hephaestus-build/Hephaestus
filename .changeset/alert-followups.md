---
"hephaestus": minor
---

New and changed alert rules. Load the new rule file from this release to use them.

- New alerts: no available webhook receiver, an unavailable receiver replica, a high share of GitHub redelivery requests, and review failures by cause (sandbox, unreachable server or model provider, preparation, lost workers).
- The webhook receiver alerts select the `<release>-webhook` Deployment by its chart labels. kube-state-metrics must export them: allow `deployments=[app.kubernetes.io/name,app.kubernetes.io/component]` with `--metric-labels-allowlist`. Without it, these alerts never fire.
- `agent_job_infra_retry_requeued_total` has a new `cause` label, and `webhook_publish_total` a new `stream` label.
- The WAL archive alert also fires when the server cannot read the archiver.
- Agent-job failure alerts fire at three failures in one hour, so a small instance alerts too.
- The PostgreSQL volume warning covers 5 % to 15 % free space and no longer fires beside the critical alert.
