---
title: Alert rules
description: The alert rules that the chart ships, their severity labels, the scrape labels they need, and the reason for each threshold.
page_type: reference
---

This page lists the alert rules that the chart ships in `files/hephaestus-alerts.yml`, and gives the reason for each threshold.
The rule file is the home of each threshold, window, and summary.
`hephaestus-alerts.test.yml` beside the rules in the repository contains a `promtool` unit test for each alert.
The chart package does not contain it.
Each test gives series that fire the alert and series that do not.
CI runs these tests for each change to the rules.

## Severity

Each alert has one of three `severity` labels:

- `critical`: data, isolation or the service is at risk now. Send these alerts to a person at once. These alerts are critical:
  - `HephaestusPostgresVolumeFilling`, `HephaestusWalArchiveFailing` and `HephaestusBackupStale`.
  - `HephaestusDatabaseTransactionIdAgeHigh` and `HephaestusServerDown`.
  - `HephaestusAgentSandboxUnisolated`.
  - `HephaestusApiErrorBudgetBurnFast` and `HephaestusApiLatencyBudgetBurnFast`.
- `warning`: a fault needs work during working hours. All other alerts are warnings.
- `none`: `HephaestusWatchdog` only. It fires while the monitoring works. Route it to an outside dead-man check.

## Labels and exporters

The rules read the exported Prometheus names, not the dotted Micrometer names.
The rules of the group `hephaestus` read the application's metrics.
The rules aggregate the server's metrics.
They need these target labels from the scrape configuration:

- `job="hephaestus"` on each server target.
- `runtime_role="server"`.
- A different `instance` on each target.

`HephaestusMetricsUnavailable` fires only for a target that Prometheus still discovers.
When service discovery loses all server targets, no `up` series remains.
To detect that, add a rule for the server, for example `absent(up{job="hephaestus",runtime_role="server"})`.

The rules of the group `hephaestus-kubernetes` read cluster metrics.
Each rule needs its exporter, scraped by the same Prometheus:

| Exporter | Metrics | Alerts |
| --- | --- | --- |
| [kube-state-metrics](https://github.com/kubernetes/kube-state-metrics/blob/main/docs/metrics/workload/cronjob-metrics.md) | `kube_cronjob_status_last_successful_time`, `kube_cronjob_created`, `kube_cronjob_labels`, `kube_deployment_status_replicas_available`, `kube_deployment_status_replicas_unavailable`, `kube_deployment_spec_replicas`, `kube_deployment_labels` | `HephaestusBackupStale`, `HephaestusServerDown`, `HephaestusServerUnready` |
| The kubelet's `/metrics` endpoint, with a storage driver that reports volume statistics | `kubelet_volume_stats_available_bytes`, `kubelet_volume_stats_capacity_bytes` | `HephaestusPostgresVolumeFilling`, `HephaestusJetStreamVolumeFilling` |
| [node-exporter](https://github.com/prometheus/node_exporter) on each node | `node_filesystem_avail_bytes`, `node_filesystem_size_bytes`, `node_filesystem_readonly` | `HephaestusNodeDiskFilling` |
| [cert-manager](https://cert-manager.io/docs/devops-tips/prometheus-metrics/) | `certmanager_certificate_expiration_timestamp_seconds` | `HephaestusCertificateExpiring` |

The group `hephaestus-pull-deploy` reads the metrics of the [pull-based deployment](./pull-based-deployment#watching-it) reconciler.
node-exporter's textfile collector on each host that runs the reconciler exports them.
Load this group only when Prometheus scrapes such a host: `HephaestusDeployMetricsMissing` fires when no host reports.

A rule whose exporter is not scraped has no series and never fires.
Before you rely on a rule, query its metrics in Prometheus.
The kube-state-metrics, kubelet and cert-manager rules have no series outside Kubernetes.

The server rules find the release's server Deployment, `<release>-server`, by the chart's labels `app.kubernetes.io/name=hephaestus` and `app.kubernetes.io/component=server`.
kube-state-metrics exports these labels only when you allow them:

- Start kube-state-metrics with `--metric-labels-allowlist=deployments=[app.kubernetes.io/name,app.kubernetes.io/component],cronjobs=[app.kubernetes.io/name,app.kubernetes.io/component,app.kubernetes.io/instance]`.
- With kube-prometheus-stack, set the value `kube-state-metrics.metricLabelsAllowlist` to `["deployments=[app.kubernetes.io/name,app.kubernetes.io/component]", "cronjobs=[app.kubernetes.io/name,app.kubernetes.io/component,app.kubernetes.io/instance]"]`.

Without these labels, the two server rules find no Deployment and never fire.

The other rules find the release's objects by the names that the chart gives them: `data-<release>-postgresql-0`, `data-<release>-nats-0`, and the CronJobs `<release>-postgresql-backup-full` and `<release>-postgresql-backup-diff`.
Another application in the cluster can use the same names, for example another PostgreSQL chart.
To limit these rules to the release, add a matcher for its namespace, for example `namespace="hephaestus"`.
Do the same for `HephaestusCertificateExpiring`, which covers every certificate that cert-manager manages in the cluster.

The chart shortens a name `<release>-<component>` above 52 characters, and then the name loses its suffix and gets a hash.
Thus, the limit depends on the component:

| Object | Shortened when the release name is longer than |
| --- | --- |
| The backup CronJobs (`postgresql-backup`) | 34 characters |
| The PostgreSQL volume (`postgresql`) | 41 characters |
| The NATS volume (`nats`) | 47 characters |

`HephaestusBackupStale` then finds the backup CronJobs by their labels, which the `cronjobs` part of the allowlist above exports.
For the volume rules, change their name patterns.

## Alerts

| Alert | Signal | Why this threshold |
| --- | --- | --- |
| `HephaestusMetricsUnavailable` | `up` | Several failed scrapes in a row, not one missed scrape |
| `HephaestusInboxRetentionFailing` | `integration_consumer_inbox_retention_failures_total` | Retention runs every hour. More than one failure in 24 hours leaves handled events and dead letters in PostgreSQL past their retention. Ingestion continues, but the table grows until retention succeeds |
| `HephaestusConsumerPoison` | `integration_consumer_poison_total` | Each dead letter is an event that the application stopped retrying |
| `HephaestusConsumerHandlerTimedOut` | `integration_message_process_duration_seconds_count{outcome="timed_out"}` | A handler ran longer than `hephaestus.integration.consumer.handler-timeout` and gave its event back to the inbox |
| `HephaestusProviderEventsWaiting` | `integration_consumer_oldest_due_seconds`, per `provider` | The claim takes a due event within seconds. A due event that waits over 5 minutes for 15 minutes means that no server claims, or that a handler holds its resource's order. A suspended workspace's events also count |
| `HephaestusProviderEventBacklogUnsampled` | `integration_consumer_backlog_sampler_failures_total` | The sampler runs every 30 seconds. More than 10 failures in 15 minutes means the backlog gauges show old values, so the alert above cannot be trusted |
| `HephaestusEventsDelayed` | `integration_message_lag_seconds`, per `kind` | `HephaestusProviderEventsWaiting` reads the oldest due event. This one measures the wait of handled events. It fires when more than 10 % of at least 20 events in 10 minutes wait over 5 minutes, for 15 minutes. A catch-up after a restart stays silent, and so does an instance with fewer events |
| `HephaestusWebhookPublishFailing` | `webhook_publish_total{outcome="failure"}` | GitHub redelivery covers a short stall of the database, not a long one. GitLab and Outline do not retry |
| `HephaestusWebhookReceiverBusy` | `webhook_rejected_total{reason="busy"}` | Large deliveries arrive faster than the receiver's memory holds them. A short burst recovers by itself |
| `HephaestusWebhookRedeliveryNotRunning` | `github_webhook_redelivery_total` | Redelivery requests that GitHub refused or that could not read the App's deliveries. One refusal can be a race. Three in 30 minutes are not |
| `HephaestusWebhookRedeliveryTruncated` | `github_webhook_redelivery_total{outcome="truncated"}` | One truncated run can be a burst. Three in two hours mean that failed events can age out unrecovered |
| `HephaestusWebhookRedeliveriesRequested` | `github_webhook_redelivery_total{outcome="requested"}` divided by `webhook_publish_total{stream="github",outcome="success"}`, over one hour | The server asks for a redelivery when GitHub recorded no successful delivery of an event. This includes a delivery that the receiver never saw, for example at an ingress timeout or while the receiver restarted. One event can be asked for up to five times. A ratio scales with traffic: more than 1 request for each 20 received deliveries is more than occasional network faults. A minimum of three requests keeps a single redelivery in a quiet hour quiet. A client error from the receiver, such as from a wrong webhook secret, gives no request and no count. The alert stays up for up to one hour after the requests stop. Only GitHub reports its delivery outcomes: GitLab, Slack and Outline have no such metric |
| `HephaestusWebhookEventsUnrecovered` | `github_webhook_unrecovered` | Each counted event failed all six delivery attempts |
| `HephaestusBudgetExhausted` | `llm_budget_exhausted_total` | A workspace crossed the instance-funded monthly cap |
| `HephaestusBudgetBlocked` | `llm_budget_blocked_total` | Work refused at a cap: at admission, or as a model call of a running job (`surface="model_call"`). A single refusal is expected at a cap. Refusals that continue for 15 minutes need a decision on the budget, whichever surface refused them |
| `HephaestusModelRefusalsReachCallers` | `llm_pool_upstream_throttled_total` less `llm_pool_upstream_absorbed_total`, per pool | Callers wait out most refusals. Draft actions return provider throttles to the outbox for retry, so those throttles count too. Refusals that reach callers for 15 minutes mean a spent account, a pause longer than a call can wait, or an outage |
| `HephaestusModelLimitAtFloor` | `llm_pool_limit` at 2 while `llm_pool_limit_decreases_total` or `llm_pool_first_token_baseline_resets_total` rises | A pool that an admin capped at 2 does not fire it. A provider that refuses or queues even two calls for 30 minutes does |
| `HephaestusAgentJobsFailing` | `agent_job_total` with `failed` or `timed_out` | All terminal failures, whatever their cause. Three in one hour point to the platform also on a small instance that runs a few reviews each day. Cancellations do not count. The alert stays up for up to one hour after the last failure |
| `HephaestusConversationModelWaitLong` | `llm_pool_permit_wait_seconds` of `interactive` calls, per pool | A reader waits for the answer. More than 10 % of at least 10 calls over 5 seconds in 15 minutes |
| `HephaestusReviewModelWaitLong` | `llm_pool_permit_wait_seconds` of `batch` and `background` calls, per pool | Review calls queue by design. Calls over two minutes hold a sandbox idle. More than a quarter of at least 20 calls in one hour, for 15 minutes |
| `HephaestusModelPermitsExpired` | `llm_pool_permits_expired_total`, per pool | Three in one hour. A worker that stops with calls in flight leaves them to expire. The count rises when the pool next admits a call, so on an idle pool it comes late |
| `HephaestusModelPermitRenewalFailing` | `llm_pool_lease_renewal_failures_total` | Three in 15 minutes. A worker renews each permit several times per lease, so one failure is a passing error |
| `HephaestusPrivacyJobFailing` | `privacy_job_completed_total` with `failure` or `incomplete`, per `exported_job` | Twice in 26 hours: an hourly sweep failed twice, or a daily job failed on two days. Erasure is a legal duty, so two are enough |
| `HephaestusPrivacyJobNotRunning` | No `privacy_job_completed_total` of `account_erasure` or `export_retention` in four hours, for 90 minutes | Both sweep each hour, so four hours are more than one missed run. The 90 minutes let a new install reach its first run |
| `HephaestusResultsMarkedIncorrect` | `practice_result_marked_total{kind="incorrect"}`, set less removed, over `practice_result_recorded_total` with `met` or `not_met`, in seven days | A wrong claim costs more trust than a missing one. More than 5 %, with at least 5 marks. The ratio compares mark events with recorded results: a mark on an older result also counts |
| `HephaestusReviewSandboxFailing` | `agent_job_infra_retry_requeued_total{cause="sandbox"}` | The sandbox could not start, initialize or finish, and the attempt runs again. Three in one hour point to the cluster: the sandbox namespace, its quota, the agent image or the nodes. The alert stays up for up to one hour after the last failure |
| `HephaestusSandboxGatewayRefusing` | `sandbox_gateway_refused_total`, per `reason`, without `attempt_ended` | The sandbox gateway refused 60 requests for one reason in ten minutes. `source_limit`, `principal_limit`, `unauthenticated`, `forbidden`, `oversized`, `body_budget` and `unknown_route` each name what it refused. An attempt that ended gets `attempt_ended`, which is normal and does not count |
| `HephaestusSandboxGatewayConnectionsHigh` | `sandbox_gateway_connections` over `sandbox_gateway_connections_limit` | A sandbox gateway held more than 80 percent of its connections for 5 minutes. At the limit, new model calls wait |
| `HephaestusGitCacheNearlyFull` | `git_cache_bytes` over `git_cache_limit_bytes` | The volume of Git mirrors, review checkouts and staged archives stayed above 90 percent for 10 minutes. A role evicts mirrors above 80 percent, so this means that what is left cannot go: running reviews, or one very large mirror. At its size limit the kubelet evicts the whole Pod |
| `HephaestusGitMirrorsEvicted` | `git_mirror_evictions_total` | Five kept mirrors were evicted in one hour. Each eviction costs a full fetch at the next capture, so the monitored repositories do not fit the volume |
| `HephaestusAttemptKeysNotSyncing` | `attempt_key_sync_failures_total`, per `task` | A worker could not publish its key or load the other keys. It keeps the keys that it knows, so running attempts continue. A new worker's tokens may fail on the others |
| `HephaestusReviewUnreachable` | `agent_job_infra_retry_requeued_total` with `cause` `server_unreachable` or `provider_unreachable` | The sandbox reached neither the server's gateway nor the model provider. The `cause` label tells which. Three in one hour point to the network policy or to the provider. The alert stays up for up to one hour after the last failure |
| `HephaestusReviewServerError` | `agent_job_server_error_total` | The server answered a review's results with its own error. The review keeps the practices that it staged, and the rest end with `EXECUTION_FAILED`, with no retry. One is a defect in the server, so the alert fires on the first. The alert stays up for up to one hour after the last error |
| `HephaestusPersonErasureInterrupted` | `person_data_erasure_failures_total` | An erasure stopped after its retry allowance, met a permanent store failure, or could not record its failure state. The counter has no person or request labels. Automatic retry alone does not increment it. The alert stays up for up to one hour after the last failure |
| `HephaestusReviewPreparationFailing` | `agent_job_infra_retry_requeued_total{cause="preparation"}` | An attempt failed before its sandbox started, for example because the reviewed source was not captured yet. A few retries are normal after a push. Retries that continue point to provider sync or the database |
| `HephaestusFeedbackReadbackUnknown` | `practice_feedback_readback_total{outcome="unknown"}`, over one day | Hephaestus could not tell whether a copy reached the work, so the feedback stays Unconfirmed. The copy may be missing, and a retry could post it twice. Readback runs only when a write was in doubt, so one count already needs a look. A proved absence does not fire: there a resend is safe. The alert stays up for one day after the last count |
| `HephaestusReviewStageSlow` | `agent_job_stage_duration_seconds`, by `stage` | Three samples in six hours above 30 minutes for `sandbox`, or above 10 minutes for another stage. A stage that never ends has no sample: the attempt's deadline ends it, and `HephaestusAgentJobsFailing` reports that |
| `HephaestusReviewWorkerLost` | `agent_run_reaped_total{kind="review",outcome="attempts_exhausted"}` | Each count is a review that failed because the holder of each of its attempts stopped renewing its lease. Workers that stop that often are evicted, out of memory, or on lost nodes. The alert stays up for one hour after the count |
| `HephaestusAgentRunReaperFailing` | `agent_run_reaper_failure_total` | The reaper failed at least ten times in ten minutes, for ten minutes. It skips a run that it cannot take back until its next pass, so one broken run does not hold back the others. A run that fails on every pass stays leased and its review waits. The `agent.run.reaper.failed` event names the run |
| `HephaestusAgentSandboxReaperFailing` | `agent_sandbox_reaper_failure_total` | The sandbox reaper failed at least ten times in ten minutes, for ten minutes. A sandbox that it cannot delete keeps its Pod and its share of the sandbox quota, but its token no longer works. The event `agent.sandbox.claim_delete_failed` names the sandbox |
| `HephaestusAgentSandboxDispatchFailing` | `agent_sandbox_dispatch_failure_total` | The dispatch failed at least ten times in ten minutes, for ten minutes. No new sandbox starts, so queued reviews wait. The event `agent.sandbox.dispatch.failed` names the error |
| `HephaestusAgentSandboxUnisolated` | `agent_sandbox_revoked_total{reason="unisolated"}` | A sandbox could still reach the Kubernetes API 10 seconds after it started. Its driver started none of its run's work and gave the run back, and the server took the sandbox back. The event `agent.sandbox.taken_back` with `sandbox.reason` `unisolated` names the sandbox |
| `HephaestusEvidenceReconcileStalled` | `evidence_reconcile_last_success_seconds` | A server's reconcile pass, which runs every 30 seconds, has not succeeded for ten minutes, for five minutes. The pass removes the folders of erased and ended attempts, so erased evidence can stay on that server. `evidence_reconcile_failures_total` counts copies left for cleanup and failed cleanup steps |
| `HephaestusCheckoutUnverified` | `evidence_checkout_copies_total` with outcome `mismatch`, `fetch_failed` or `unavailable` | A server could not build a checkout that another server captured. The attempt ends as a capture failure and runs again. The outcomes `hit` and `rebuilt` count the copies that the server took from its cache or built |
| `HephaestusReviewFeedbackUnconfirmed` | `agent_job_composition_stopped_total` with outcome `unrecorded` | Each count is a review that closed as Partial because every attempt ended before it confirmed where its feedback went. The results stay. |
| `HephaestusOutboxStalled` | `action_oldest_due_seconds`, by `kind` | An action of the kind has been due for more than 15 minutes, for 10 minutes. No server replica carries it out, or an action that does not end holds its lane |
| `HephaestusOutboxUncertain` | `action_ended_total{state="UNCERTAIN"}`, by `kind` | An action ended uncertain in the last hour: its write may have reached the provider, and nothing confirms it. An uncertain email is never sent again. The alert stays up for one hour after the count |
| `HephaestusInteractiveRunWaiting` | `agent_run_oldest_due_age_seconds{band="interactive"}` | A person waits for a turn that can start. A turn that no server starts within 3 minutes ends as not started and leaves the queue, so the hour of `HephaestusAgentQueueStalled` never sees it. 60 seconds leaves two minutes to act before the turn expires, and one minute of it separates a stall from a short spike. A run on a hold is not due, so it does not count |
| `HephaestusAgentQueueStalled` | `agent_run_oldest_due_age_seconds`, by `band` | A practice review usually runs for 10 to 16 minutes. A run that is due and waits for several review durations shows that no server claims work. The alert names the band |
| `HephaestusWalArchiveFailing` | `database_wal_archive_failing`, and `database_wal_archive_sample_failures_total` | PostgreSQL retries a failed segment about once each minute, so a short fault of the bucket clears itself. A longer failure stops point-in-time recovery after the last archived segment. A server that cannot read the archiver for the same time cannot show that the archive works, so the alert also fires then |
| `HephaestusDatabaseConnectionTimeouts` | `hikaricp_connections_timeout_total`, by `runtime_role` | The pool could not give a connection within its timeout, so the request or the job that asked failed. A single timeout is a failure that a user meets. The alert stays up for up to 15 minutes after the last timeout |
| `HephaestusDatabasePoolSlow` | `hikaricp_connections_acquire_seconds`, by `runtime_role` | The sizing target is an acquire p95 below 50 ms ([Sizing](./sizing.mdx)). More than 5 % of the acquires of 30 minutes above 50 ms misses it. A process with fewer than 300 acquires in 30 minutes does not fire, so a few slow acquires during a database restart do not count |
| `HephaestusTenancyViolations` | `tenancy_violation_total`, by `table` and `mode` | Each count is a statement that read a workspace's table without binding the workspace. In `throw` mode the server refused it, so its request or job failed. One count is a defect, so the alert has no threshold |
| `HephaestusBrowserFaultsRising` | `browser_faults_total` | A page that fails for one reader repeats its failure. Twenty of one kind in one hour point to a fault that many readers meet, such as a release that broke a page. The alert stays up for up to one hour after the faults stop |
| `HephaestusContainerOomKilled` | `kube_pod_container_status_last_terminated_reason` and `kube_pod_container_status_restarts_total`, or `kube_pod_container_status_terminated_reason`, with `OOMKilled` | Each kill loses work in flight. It reads every namespace. On a shared cluster, add a namespace matcher. A sandbox Pod that the worker deletes quickly can leave no sample |
| `HephaestusServerDown` | `kube_deployment_status_replicas_available` of the server Deployment, while its desired replicas are above 0 | No available server means that the API is down and every provider delivery fails. The wait covers the start of a replacement pod. A Deployment scaled to 0 for maintenance does not fire. A server that is unavailable for a shorter time than the wait does not fire. `HephaestusWebhookRedeliveriesRequested` can then fire for the GitHub deliveries that it lost, when they reach its threshold. Deliveries of other providers have no such alert |
| `HephaestusServerUnready` | `kube_deployment_status_replicas_unavailable` of the server Deployment, while another replica is available | The other replicas serve, so this is a warning. The wait is longer than the 10 minutes that a new pod waits for the schema |
| `HephaestusBackupStale` | The newest successful full or differential backup of a release, or the creation time of CronJobs with no successful backup | A differential backup runs each day. The hours above one day let a full backup run |
| `HephaestusPostgresVolumeFilling` | Free space of the PostgreSQL volume | A failed WAL archive fills the volume. The warning gives time to repair the archive. The critical level comes shortly before PostgreSQL stops. The warning covers only the band above the critical level, so the two never fire together |
| `HephaestusJetStreamVolumeFilling` | Free space of the JetStream volume | The volume is `max_file` plus 25 %, so a broker at its storage budget leaves 20 % free. Below the threshold, something outside the budget fills the volume. A full file system wedges the broker |
| `HephaestusNodeDiskFilling` | Free space of a writable node file system, not the volumes that the kubelet mounts for pods | The kubelet's image garbage collection starts at 85 % use and frees space to 80 %. When the space stays low, eviction comes next. The volume rules cover pod volumes |
| `HephaestusDeployReconcileFailing` | `hephaestus_deploy_reconcile_success` | The timer runs the reconciler each minute. A run that fails for 15 minutes does not clear itself. For example, a chart that refuses the host's values fails each run in the same way. The host then keeps its old release |
| `HephaestusDeployReconcileStale` | `hephaestus_deploy_reconcile_timestamp_seconds` | The reconciler writes the timestamp when a run ends, and one apply may take up to the unit's 30-minute start timeout. No finished run for 40 minutes means that the timer or the unit stopped |
| `HephaestusDeployMetricsMissing` | No `hephaestus_deploy_reconcile_timestamp_seconds` | The textfile collector, the metrics file or the reconciler is gone, which no other rule of the group shows |
| `HephaestusDeployToolingPending` | `hephaestus_deploy_tooling_pending` | The host runs tooling that no apply recorded. The next promotion of the release that the host runs ends it. An hour leaves time for that promotion |
| `HephaestusCertificateExpiring` | `certmanager_certificate_expiration_timestamp_seconds` | cert-manager renews a certificate when one third of its lifetime remains: 15 days of a 45-day certificate. For a certificate with a lifetime of less than 21 days, lower the threshold. cert-manager reports 0 for a certificate that it did not issue yet. Therefore, the alert also fires when a first issuance takes longer than its wait |
| `HephaestusFeedbackSendRetrying` | `practice_feedback_send_attempts` at half `practice_feedback_send_readback_limit` for one minute | A send repeatedly needs recovery or readback. A manual retry is a new send with its own count |
| `HephaestusFeedbackSendMetricsUnavailable` | An absent or `NaN` attempt gauge for five minutes on a scraped server | The database read did not succeed, so the retry alert has no reliable input |

The WAL archive rule reads the server's [WAL archive metrics](./metrics#wal-archive-metrics).
It also applies to an external PostgreSQL that archives its WAL.
No rule reads the age of the last archived segment.
PostgreSQL switches a segment only after WAL activity, so an idle database archives nothing, and an age rule would fire falsely.
An archive command that hangs without a failure stops the next backup, because pgBackRest waits for the backup's WAL in the archive.
`HephaestusBackupStale` and `HephaestusPostgresVolumeFilling` then fire.

[Load the alert rules](./monitoring#load-the-alert-rules) gives the setup, and [Service level objectives](./observability#service-level-objectives) gives the objectives.
