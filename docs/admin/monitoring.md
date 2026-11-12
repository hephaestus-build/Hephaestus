---
title: Set up monitoring
description: Scrape the metrics of each role, run the bundled monitoring stack, load the alert rules, and collect logs and traces.
page_type: how-to
---

By default, Hephaestus sends no metrics, logs or traces to an outside service.
Use these procedures to collect them with tools that you operate.
[Observability](./observability) explains which questions each signal answers.

## Scrape metrics

Each runtime role serves `GET /actuator/prometheus` without a user token on a separate management
listener, port **9090** by default. The
application listener refuses this metrics path, including requests with a user token. Port 8081 is
the sandbox gateway, not a metrics endpoint. Liveness and readiness also remain available on the
application listener at `/livez` and `/readyz`. Container probes use these application-listener paths.

The chart exposes this listener only to the private cluster network.
Set `networkPolicy.metrics` to the namespace and Pod labels of your trusted Prometheus.
Do not create an internet route to management.
The application exports no metrics to an external service.
Operators without Prometheus need no scrape configuration.

For multiple replicas:

1. Discover and scrape **all Pods**, not a load-balanced service address that can switch replicas between scrapes.
2. Keep each target's `instance` label distinct.
3. Apply `increase` or `rate` to each counter before you sum them.
4. Never add `workspace.id` or `job.id` as metric labels.

This handles resets per process.
`runtime_role` above is an operator-supplied target label, not an application meter label.
`workspace.id` and `job.id` remain log fields.

Example `prometheus.yml` for the [Helm chart](./kubernetes.mdx), with the release in the namespace `hephaestus`:

```yaml
scrape_configs:
  - job_name: hephaestus
    scrape_interval: 30s
    scrape_timeout: 10s
    metrics_path: /actuator/prometheus
    kubernetes_sd_configs:
      - role: pod
        namespaces:
          names: [hephaestus]
    relabel_configs:
      - source_labels:
          - __meta_kubernetes_pod_label_app_kubernetes_io_name
          - __meta_kubernetes_pod_container_port_name
        regex: hephaestus;management
        action: keep
      - source_labels: [__meta_kubernetes_pod_label_app_kubernetes_io_component]
        target_label: runtime_role
      - source_labels: [__meta_kubernetes_namespace]
        target_label: namespace
      - source_labels: [__meta_kubernetes_pod_name]
        target_label: pod
rule_files:
  - hephaestus-alerts.yml
```

Each pod of the server, the webhook role and the worker is then one target.
The `app.kubernetes.io/component` label of the pod gives `runtime_role`.
Set `networkPolicy.metrics` to the namespace and the pod labels of this Prometheus ([Network policy](./helm-chart.md#network-policy)).
With the Prometheus Operator, a PodMonitor must give the same labels.
Set `job` to `hephaestus` with a relabeling.

## Run the monitoring stack

This is optional.
`observability.stack.enabled` runs a small monitoring stack in the release's namespace:

| Part | Does |
| --- | --- |
| VictoriaMetrics | Scrapes the management port of each role of this release every 30 seconds, as [Scrape metrics](#scrape-metrics) describes. Keeps the samples for `observability.stack.retention`, by default 30 days, on a volume of `observability.stack.persistence.size` |
| vmalert | Evaluates the release's [alert rules](./alert-rules) every 30 seconds |
| Alertmanager | Sends the alerts to the receivers of your configuration |

VictoriaMetrics also serves the [dashboards](#open-the-dashboards).

Before you turn it on:

1. Write the routes and receivers in a file `alertmanager.yml`.
   Route each alert by its [severity](./alert-rules):
   - Send `critical` alerts to a person at once, for example through a paging service.
   - Send `warning` alerts to a queue that you read during working hours, and repeat them once a day.
   - Route `HephaestusWatchdog` to an outside dead-man check: a service that alerts you when the alert stops arriving.

   ```yaml title="alertmanager.yml"
   route:
     receiver: tickets
     group_by: [alertname]
     repeat_interval: 24h
     routes:
       - matchers: ['alertname="HephaestusWatchdog"']
         receiver: dead-man
         repeat_interval: 5m
       - matchers: ['severity="critical"']
         receiver: pager
         repeat_interval: 1h
   inhibit_rules:
     # A fast budget burn pages. Its slower windows of the same objective add nothing.
     - source_matchers: ['alertname="HephaestusApiErrorBudgetBurnFast"']
       target_matchers: ['alertname=~"HephaestusApiErrorBudgetBurn(Slow|Ticket)"']
     - source_matchers: ['alertname="HephaestusApiLatencyBudgetBurnFast"']
       target_matchers: ['alertname=~"HephaestusApiLatencyBudgetBurn(Slow|Ticket)"']
     # No ready receiver includes each unavailable one.
     - source_matchers: ['alertname="HephaestusWebhookReceiverDown"']
       target_matchers: ['alertname="HephaestusWebhookReceiverUnready"']
   receivers:
     - name: pager
       webhook_configs:
         - url: https://pager.example.com/hooks/<key>
     - name: tickets
       email_configs:
         - to: ops@example.com
           from: alerts@example.com
           smarthost: smtp.example.com:587
           auth_username: alerts@example.com
           auth_password_file: /etc/alertmanager/smtp-password
     - name: dead-man
       webhook_configs:
         - url: https://dead-man.example.com/ping/<id>
   ```

   Replace `<key>` with the key of your paging service, and `<id>` with the check ID that your dead-man service gives you.

2. Put it in a Secret in the release's namespace, with any password file beside it:
   `kubectl create secret generic hephaestus-alertmanager --from-file=alertmanager.yml --from-file=smtp-password`.
3. Set `observability.stack.alertmanager.configSecret` to the Secret's name.
4. Set `networkPolicy.kubernetesApi`. VictoriaMetrics finds the Pods through the Kubernetes API, with a Role in the release's namespace.

Alertmanager reaches its receivers on ports 443, 465 and 587 of `networkPolicy.externalEgress`.
Silences live only until Alertmanager restarts.

The stack needs no host path and no cluster-wide access, so it does not run the node and cluster exporters.
Thus, the stack does not load the groups `hephaestus-kubernetes` and `hephaestus-pull-deploy`: they read kube-state-metrics, the kubelet's volume statistics, node-exporter, and cert-manager.
Without these series, `HephaestusDeployMetricsMissing` would fire at all times.

To use those groups, run the exporters in a namespace that allows host paths. Scrape them, and evaluate the [rule file](./alert-rules), with your own Prometheus or vmalert.
The stack collects no logs: see [Collecting logs](#collect-logs).

On the project's test instance, VictoriaMetrics used at most 470 MiB while it scraped the roles and the kubelets, and vmalert used 22 MiB.
Both used less than 0.05 of a CPU.
The stack's requests and limits follow these numbers.

## Open the dashboards

The [monitoring stack](#run-the-monitoring-stack) serves five dashboards in the web view of VictoriaMetrics.
They come with the chart, so each upgrade brings the dashboards of its release.
To open them, forward the port and open `http://localhost:8428/vmui`, then choose **Dashboards**:

```bash
kubectl --namespace <namespace> port-forward service/<release>-victoria-metrics 8428
```

| Dashboard | Answers |
| --- | --- |
| Overview | Is the release up, do the objectives hold, and what runs out first? |
| Reviews and feedback | Do reviews finish, where does an attempt spend its time, and does feedback reach people? |
| Model calls | Do calls wait, how do they end, and do workers keep their calls? |
| Database and tenancy | Does the pool make requests wait, does tenancy hold, and do privacy jobs run? |
| Provider events and messaging | Do events arrive, and do consumers keep up? |

The files are in `files/dashboards/` of the chart.
A panel's description names what it shows.
For an external VictoriaMetrics, set `observability.dashboards.namespace` to its existing namespace.
The chart then creates `<release>-victoria-metrics-dashboards` there on each upgrade, without the bundled stack.

Mount that ConfigMap as a directory at the path of `-vmui.customDashboardsPath` in your VictoriaMetrics workload.
Keep the mount in your deployment configuration. Do not copy the dashboard files by hand.
Use a directory mount. A `subPath` mount does not receive ConfigMap updates.

The chart owns the ConfigMap. The Helm identity needs permission to create, update and delete ConfigMaps in that namespace.
If that name already exists without this release's ownership, Helm refuses the install.
Before you remove the namespace value or the Helm release, change or remove the external workload's mount.
Otherwise the workload cannot start on its next restart.

With the bundled stack, leave the namespace empty, or use the release namespace.
A different namespace with the bundled stack fails the render because its workload cannot mount a ConfigMap from another namespace.

With your own Prometheus, use the queries in these files.

## Load the alert rules

The chart contains the alert rules in `files/hephaestus-alerts.yml`.
Each upgrade of the chart brings the rules of its release.
Turn on one of two values, in the release's namespace:

| Value | Renders | For |
| --- | --- | --- |
| `observability.alertRules.configMap` | A ConfigMap with the file as it is, under the key `hephaestus-alerts.yml` | A Prometheus or a vmalert that mounts the ConfigMap |
| `observability.alertRules.prometheusRule` | A PrometheusRule with the `groups` of the file | The Prometheus Operator. Its CRD must exist |

Both are off by default.
`observability.alertRules.labels` adds labels to both, so that the rule selector of your Prometheus finds them.
For kube-prometheus-stack, set the `release` label of your monitoring release, or set its `ruleSelector`.
Mount the ConfigMap into your Prometheus or vmalert, and let its rule loader read the mounted file.
Without the chart, load the file as it is from the release that you run: `https://github.com/hephaestus-build/Hephaestus/blob/v<version>/deploy/chart/hephaestus/files/hephaestus-alerts.yml`.

The rules of the group `hephaestus` read the `job` and `runtime_role` labels that your scrape configuration sets, not labels that the chart controls.
[Alert response](./production-operations-runbook.mdx#alert-response) gives the response to each alert.
Each alert carries that section's address in its `runbook_url` annotation, so a notification can link the reader to it.

## Adapt the alert rules

To adapt the rules, load your own copy of the file instead of the chart's values, and change the copy:

1. Adjust windows, thresholds, and routing to your traffic and recovery objectives.
2. If you change the monitor interval, increase the poll-age threshold.
3. If you change `postgresql.backup.schedule`, change the age of `HephaestusBackupStale`.
4. Enable stream rules only where you expect the webhook stream monitor.
5. To detect an individually missing stream, add an `absent_over_time` rule for each expected `stream` and receiver target.

   The missing-series rule detects loss of the whole monitor.
   Counters created on their first event can be absent before that event.
   An `increase` rule needs at least two samples.
   It cannot recover an event that occurred before its first sample.
   Scraping does not replace warning and error logs.

   Poison counts mean the application stopped retrying a delivery.
   They are not a durable dead-letter queue.
   See [Webhook ingestion operations](./webhook-ingestion-operations.mdx#what-to-alert-on).

6. Validate the config with:

   ```bash
   promtool check config prometheus.yml
   ```

7. Validate the rules and run their tests with:

   ```bash
   promtool check rules hephaestus-alerts.yml
   promtool test rules hephaestus-alerts.test.yml
   ```

   A changed threshold also needs a changed test.

8. Before you rely on the rules, test the notification route.

Prometheus evaluates rules.
Your Alertmanager or equivalent controls notification delivery, grouping, and silences.
See the [Prometheus alerting guidance](https://prometheus.io/docs/practices/alerting/).

## Turn on database metrics

With `postgresql.metrics.enabled=true`, the release runs [postgres_exporter](https://github.com/prometheus-community/postgres_exporter) as a Deployment of its own.
It reads PostgreSQL through its Service, with TLS, as the role `hephaestus_monitor`.
That role is a member of `pg_monitor` only, and it can open at most 2 connections.
The exporter does not run in the PostgreSQL Pod, so turning it on or off does not restart the database.

PostgreSQL counters keep their totals across exporter restarts.
The bundled monitoring stack uses the Prometheus-compatible counter rollup, so the first sample does not count as a new deadlock.

To turn it on:

1. Add `DATABASE_MONITOR_PASSWORD` to the release Secret: `sh hephaestus-keys.sh --metrics`.
   When Helm can read the Secret and it lacks the key, the install stops with a message.
   Otherwise the exporter Pod waits, and its events name the missing key.
2. With an external PostgreSQL, set up the role yourself.
   Get the folder `files/` from the verified chart of the release, as [Verify the release chart](./kubernetes#verify-the-release-chart) describes.
   Export `DATABASE_MONITOR_PASSWORD` with its value. Then run the script as an administrator, with the application's database:

   ```bash
   sh "chart-$VERSION/hephaestus/files/database-monitor.sh" \
     "host=<host> dbname=hephaestus user=<administrator> sslmode=verify-full"
   ```

   The script sends PostgreSQL only the verifier of the password.
   It creates `hephaestus_monitor` in `pg_monitor`, the schema `monitoring` and the extension `pg_stat_statements` in it.
   Its quiet settings keep the verifier out of the statement statistics and the server log, and the run stops at the first error.

   An administrator that is not a superuser may not change these settings.
   Set them at the service first, as [Database roles on an external PostgreSQL](./external-database#set-up-the-database-roles) lists.
   The server must preload `pg_stat_statements` (`shared_preload_libraries`).
   With the bundled PostgreSQL, a Job of each release runs the script and sets the password again.
3. Set `postgresql.metrics.enabled=true` and upgrade.
4. Scrape the exporter's port `metrics` (9187) on the path `/metrics`.
   The [monitoring stack](#run-the-monitoring-stack) does this as the job `postgresql`.
   Another scraper needs the namespace and labels of `networkPolicy.metrics`.

The exporter reports the default collectors, the oldest open transaction, the transaction ID age of each database, and the statement statistics.
The statement statistics hold the `postgresql.metrics.statementLimit` statements with the most total execution time, 20 by default.
Their only labels are `user`, `datname` and `queryid`.
The query text stays in PostgreSQL.
To read the text of a `queryid`, run `SELECT query FROM monitoring.pg_stat_statements WHERE queryid = <id>` in the application's database.

The rule group `hephaestus-postgresql` alerts on these metrics:

| Alert | Fires when |
| --- | --- |
| `HephaestusDatabaseDeadlocks` | PostgreSQL broke a deadlock in the last 15 minutes |
| `HephaestusDatabaseTransactionOpenLong` | A transaction has been open for more than 5 minutes, for 5 minutes |
| `HephaestusDatabaseDeadRowsHigh` | A table has more than 10,000 dead rows and more than 20 % of its live rows, for an hour |
| `HephaestusDatabaseTransactionIdAgeHigh` | The oldest unfrozen transaction ID of a database is more than 1 billion old, for 15 minutes. It is critical. |

Without the exporter, these rules have no series and stay silent.
The [runbook](./production-operations-runbook#alert-response) gives the response to each.

To change the password, set a new `DATABASE_MONITOR_PASSWORD` and upgrade.
The Job sets the new password.
Then restart the exporter, because it reads the Secret only when it starts: `kubectl -n hephaestus rollout restart deployment/hephaestus-postgresql-exporter`.

To turn it off, set `postgresql.metrics.enabled=false` and upgrade.
The role, the schema and the extension stay.
To remove them, run as a superuser in the application's database:

```sql
DROP EXTENSION IF EXISTS pg_stat_statements;
DROP SCHEMA IF EXISTS monitoring;
DROP ROLE IF EXISTS hephaestus_monitor;
```

## Read database statement statistics

The bundled PostgreSQL image loads [`pg_stat_statements`](https://www.postgresql.org/docs/current/pgstatstatements.html).
It records the run count and time of each statement for all databases.
To read the statistics, create the extension once in the `postgres` database.
Then the application schema stays unchanged.
For a release named `hephaestus` in namespace `hephaestus`, run:

```bash
kubectl -n hephaestus exec hephaestus-postgresql-0 -c postgresql -- \
  sh -c 'psql -U "$POSTGRES_USER" -d postgres -c "CREATE EXTENSION IF NOT EXISTS pg_stat_statements"'
kubectl -n hephaestus exec hephaestus-postgresql-0 -c postgresql -- \
  sh -c 'psql -U "$POSTGRES_USER" -d postgres -c "SELECT calls, round(mean_exec_time) AS mean_ms, round(total_exec_time) AS total_ms, left(query, 120) AS query FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 20"'
```

For most statements, the statistics replace constants with placeholders.
A clean restart keeps them, and `SELECT pg_stat_statements_reset()` clears them.

## Collect logs

Hephaestus sends no logs and no error reports to any service.
The browser app sends only its [page experience metrics](./metrics#page-experience-metrics), and only to your server.

### What the cluster keeps by default

Each runtime role writes its log records to standard output.
The kubelet writes them to files below `/var/log/pods` on the node and rotates them.
It keeps at most `containerLogMaxFiles` files of `containerLogMaxSize` for each container, by default 5 files of 10 MiB.
When the kubelet deletes a Pod, it also deletes that Pod's log files.
A replaced Pod, such as after an upgrade or a crash, therefore takes its lines with it, unless a collector shipped them before.

### Ship logs to a log store

This is optional.
A collector on each node reads the Pods' log files.
It sends the records to a log store that you operate, such as Loki or VictoriaLogs.
The [OpenTelemetry Collector Helm chart](https://github.com/open-telemetry/opentelemetry-helm-charts/tree/main/charts/opentelemetry-collector) runs one as a DaemonSet.
Hephaestus ships no collector and names no destination.

Read only the server, worker, webhook and migration Pods, and NATS.
Their records hold no content.

Do not read the webapp, PostgreSQL or any Pod of the sandbox namespace:

- The webapp's nginx lines can name a client address and a request with its query.
- PostgreSQL's error lines can quote row values.
- A sandbox's output can hold reviewed code, a tool result, a provider's error message, the conversation or a credential.
  This includes the lines that the review runtime writes itself.

The example below is a `values.yaml` for the collector chart.
It reads the release `hephaestus` in the namespace `hephaestus`.
The `k8s` distribution of the collector includes the `filelog` receiver.
Replace the exporter with your log store's OTLP endpoint:

```yaml
mode: daemonset
image:
  repository: otel/opentelemetry-collector-k8s
presets:
  logsCollection:
    enabled: true
config:
  receivers:
    filelog:
      include:
        - /var/log/pods/hephaestus_hephaestus-server-*/*/*.log
        - /var/log/pods/hephaestus_hephaestus-webhook-*/*/*.log
        - /var/log/pods/hephaestus_hephaestus-migration-*/*/*.log
        - /var/log/pods/hephaestus_hephaestus-nats-*/*/*.log
      exclude: []
      operators:
        - type: container
        - type: json_parser
          if: 'body matches "^\\{"'
          parse_to: attributes
          on_error: send_quiet
        - type: move
          if: "attributes.message != nil"
          from: attributes.message
          to: body
  exporters:
    otlphttp:
      logs_endpoint: https://logs.example.org/insert/opentelemetry/v1/logs
  service:
    pipelines:
      logs:
        exporters: [otlphttp]
```

A record's fields become log attributes, for example `context.traceId` and `event.name`.
Index only labels from a small fixed set, such as the namespace and the container.
Keep workspace, job, user and repository identifiers and the trace ID out of the index: each value becomes its own stream ([Loki label best practices](https://grafana.com/docs/loki/latest/get-started/labels/bp-labels/)).
Set the store's retention to the period in your record of processing.

Each stage has a bound, and each bound can lose lines:

| Stage | Bound | When it is reached |
| --- | --- | --- |
| The kubelet's log files | `containerLogMaxFiles` × `containerLogMaxSize` for each container, and the Pod's life | The kubelet deletes the oldest file, or all files with the Pod |
| Collector start or restart | The chart's `filelog` receiver starts at the end of each file and keeps no read position | Lines written while no collector ran are not sent, also when their file still exists |
| Collector to the log store | The exporter's retry and queue settings | The collector drops the batch |

## Export traces

Hephaestus uses Spring Boot's OpenTelemetry starter and the OTLP HTTP exporter. Export is disabled
by default. Configure these settings on the runtime
roles whose spans you want to collect:

| Variable                       | Default                           | Purpose                                                                        |
| ------------------------------ | --------------------------------- | ------------------------------------------------------------------------------ |
| `TRACING_OTLP_ENABLED`         | `false`                           | Enable the OTLP trace exporter.                                                |
| `TRACING_OTLP_ENDPOINT`        | `http://localhost:4318/v1/traces` | Full OTLP HTTP receiver URL, reachable from the application container.         |
| `TRACING_SAMPLING_PROBABILITY` | `0.0`                             | Fraction of new traces sampled. Use `1.0` for a dedicated evaluation instance. |

The chart ships no collector and no trace store. To send traces to your own OpenTelemetry collector:

1. Run an OpenTelemetry collector in the cluster, with an OTLP HTTP receiver. Configure the exporter to your trace store in the collector.
2. Set the trace values in your site values. Use the collector's namespace and pod labels, and its receiver URL:

   ```yaml
   config:
     tracing:
       otlpEnabled: true
       otlpEndpoint: http://otel-collector.observability:4318/v1/traces
       samplingProbability: "0.1"
   networkPolicy:
     traceCollector:
       namespace: observability
       podLabels:
         app.kubernetes.io/name: opentelemetry-collector
   ```

3. Upgrade the release. Then find a request's trace in your trace store by the `X-Request-Id` that the server returned.

The chart sets the variables above on the server, worker and webhook from `config.tracing.otlpEnabled`, `config.tracing.otlpEndpoint` and `config.tracing.samplingProbability`.
By default, the chart's network policy admits no collector inside the cluster.
Set `networkPolicy.traceCollector` to the namespace and pod labels of that collector ([Network policy](./helm-chart.md#network-policy)).
The roles can then reach it on the port that `config.tracing.otlpEndpoint` names.

Run the collector in the cluster.
Outside the cluster, only the server and the worker reach a collector, and only on port 443, through `networkPolicy.externalEgress`.
The webhook has no path to an external collector, so its traces are lost.

Each span carries the running version as `service.version` and the deployment environment as `deployment.environment.name`.
The Helm chart exposes no value for authenticated OTLP headers.
Use an in-cluster collector that accepts the roles without those headers.
Configure authentication for the collector's upstream destination in the collector itself.

Do not put collector credentials in a URL. OTLP log and metric export stay disabled: enabling traces does not
start exporting application logs, private transcripts or a second metrics stream. Prometheus metrics
are available through the private listener described in [Scrape metrics](#scrape-metrics).

Each practice-review attempt starts a `practice_review.execute` span. Its trace ID can differ from
the job's submission correlation ID, which is carried as the `hephaestus.job.submission_trace_id`
attribute rather than as a parent span. The span names its agent run as `hephaestus.run.id`,
`hephaestus.run.epoch` and `hephaestus.subject.key`. A gateway request for one run names the same attributes and `hephaestus.sandbox.name` on its server span.

Sandbox proxy requests propagate the execution trace's sampling decision. A `gen_ai.chat` span covers each authenticated model request, streamed or not. It records the model,
the HTTP outcome and the token usage that the provider reported, as `gen_ai.usage.input_tokens` and
`gen_ai.usage.output_tokens`.

A provider event crosses the message broker between the webhook role and the server.
The webhook role writes the trace context of its request into the message, as the W3C `traceparent` header.
The server continues that trace in a consumer span, `process <stream>`, around the handler.
The handler's provider calls are child spans of it.
Thus, one trace shows the webhook request, the handling of the event, and the calls that the handling made.

The consumer span records `messaging.system`, `messaging.operation.type` and `messaging.destination.name`, the message subject.
A subject names the provider's owner and repository, as the URL of a provider call does.
A message that a release before this one stored has no trace context, so its handling starts a new trace.

| Prometheus name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `integration_message_lag_seconds` | Histogram | `kind` | How long an event waited in its stream before its first handling. A retry has no sample |
| `integration_message_process_duration_seconds` | Histogram | `kind`, `outcome` | How long the handling of one delivery took. `outcome` is `completed` or `failed` |

Framework HTTP spans describe the transport. Prompts, tool results, request bodies,
headers and tokens used for authentication are not span attributes.

For streaming requests, `hephaestus.stream.outcome` records transport completion separately from
the HTTP status. A client disconnect or stream failure marks the span as an error with a bounded
`error.type`, even if HTTP 200 headers were already sent. No replacement response body is sent
after the stream has been committed.

A trace ID is not proof of export. Sampling, exporter errors, collector retention and abrupt
process termination can all leave partial coverage. A trace describes when calls happened and how
they ended. It is not a record of what a review read or said.

Durable review records retain admitted results, their problems and citation checks, not raw inputs,
model requests or session transcripts. The [payload retention](./data-retention#postgresql) strips diagnostic job output.

The worker can separately retain bounded debug session transcripts. The
[retention decision](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0041-compose-1x-kubernetes-2.md#update--2026-10-09-debug-transcripts)
defines their ownership checks, metadata fallback, erasure and expiry.

## Find the records of one request

When tracing is enabled, HTTP responses carry an `X-Request-Id` header containing the request's W3C
trace ID. It is generated by tracing or extracted from valid incoming trace context, not derived
from user attributes. While a trace scope is active, JSON logs carry its IDs as `context.traceId` and
`context.spanId`. With tracing disabled, the correlation filter passes through without inventing an ID.
Access logs stay off at every layer.

A queued review records its submission correlation, but an execution attempt starts its own trace.
Join the request and subsequent lifecycle by `job.id`. Do not assume every log for a job shares the
request's trace ID. Sandbox proxy calls propagate the execution trace. The
[trace export contract](#export-traces) describes the separate submission attribute.

Every problem response also carries the ID in its body as `requestId`.
The web app's error page shows the first eight characters as **Reference**, and its **Copy reference** button copies the whole ID.
A reader who reports a failure can quote either one.

No collector or log aggregator is necessary.
On Kubernetes:

1. Take the reference that a reader reports, or the `X-Request-Id` from a failed response.
2. Search the logs of the release's Pods. Eight characters match the start of the ID.
   Without `--tail=-1`, a selector returns only the last 10 lines of each container:

   ```bash
   kubectl logs --namespace <namespace> --selector app.kubernetes.io/name=hephaestus,app.kubernetes.io/instance=<release> \
     --all-containers --prefix --since=24h --tail=-1 | grep '"traceId":"<the-reference>'
   ```

   That command finds records in the matching trace scope.
   To follow a queued job into later execution attempts:

3. Take its `job.id` from the queued event.
4. Search that identifier too.

Scheduler work can have execution traces without an original HTTP response.
A trace ID proves that correlation context was present, not that a collector received the trace.

## Check that it worked

1. In Prometheus, run the query `up{job="hephaestus"}`.
   It returns `1` for each Pod of the server, the webhook role and the worker.
2. Check that your notification route receives `HephaestusWatchdog`.
   It fires while the monitoring works.
