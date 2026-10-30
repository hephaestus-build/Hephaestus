{{/*
A name of at most .max characters. A name that fits is used as it is. A longer name keeps its first
characters and ends with 8 characters of the SHA-256 of the whole name, so two long names that
differ only at the end stay different, and the same input always gives the same name.
Called with (dict "name" <name> "max" <limit>).
*/}}
{{- define "hephaestus.boundedName" -}}
{{- if le (len .name) (int .max) -}}
{{- .name -}}
{{- else -}}
{{- printf "%s-%s" (trunc (int (sub .max 9)) .name | trimSuffix "-") (sha256sum .name | trunc 8) -}}
{{- end -}}
{{- end -}}

{{/*
Object names: "<release>-<component>", so two releases in one namespace never collide. Every object
reference, selector and setting that names an object reads this helper. The limit is 52: the
StatefulSet controller labels each revision with the StatefulSet name, a dash and a hash of up to
10 characters, and a label value has at most 63. The same limit keeps the first pod's name
("<name>-0") and its statefulset.kubernetes.io/pod-name label within 63, and every Service name
within the 63 of a DNS label. The app.kubernetes.io/instance label keeps the full release name.
*/}}
{{- define "hephaestus.name" -}}
{{- include "hephaestus.boundedName" (dict "name" (printf "%s-%s" .root.Release.Name .component) "max" 52) -}}
{{- end -}}

{{/*
The PriorityClass of a tier: one that priority.yaml creates, or the operator's. A created
PriorityClass is cluster-wide, so its name carries the release's namespace as well as its name.
*/}}
{{- define "hephaestus.priorityClass" -}}
{{- if .root.Values.priorityClasses.create -}}
{{- include "hephaestus.boundedName" (dict "name" (printf "%s-%s-%s" .root.Release.Namespace .root.Release.Name .tier) "max" 63) -}}
{{- else -}}
{{- get .root.Values.priorityClasses .tier -}}
{{- end -}}
{{- end -}}

{{- define "hephaestus.selectorLabels" -}}
app.kubernetes.io/name: hephaestus
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
The labels of a Pod template: no release version and no chart version, so that an upgrade restarts
only the Pods whose own specification changed, not PostgreSQL and NATS on every release.
*/}}
{{- define "hephaestus.podLabels" -}}
{{ include "hephaestus.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: hephaestus
{{- end -}}

{{- define "hephaestus.labels" -}}
{{ include "hephaestus.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: hephaestus
app.kubernetes.io/version: {{ .root.Values.release.version | quote }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" | quote }}
{{- end -}}

{{/*
Restricted Pod Security Standard. The pod and container halves are applied to every workload the
chart runs; nothing in values can widen them.
*/}}
{{- define "hephaestus.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ .user }}
runAsGroup: {{ .group }}
fsGroup: {{ .group }}
fsGroupChangePolicy: OnRootMismatch
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "hephaestus.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
privileged: false
capabilities:
  drop:
    - ALL
{{- end -}}

{{/*
The sandbox namespace, of review sandboxes, the conversation host and turn sandboxes. The operator creates
it with the Restricted Pod Security level and names it here; the chart writes into it and never
creates it. The worker receives the same string, and the runtime derives no name of its own.
*/}}
{{- define "hephaestus.sandboxNamespace" -}}
{{- $namespace := required "worker.sandbox.namespace is required: name the namespace that the operator created for sandboxes" .root.Values.worker.sandbox.namespace -}}
{{- if eq $namespace .root.Release.Namespace -}}
{{- fail "worker.sandbox.namespace must not be the release namespace: sandboxes need a namespace of their own" -}}
{{- end -}}
{{- $namespace -}}
{{- end -}}

{{/*
The existing Sandbox Gateway port (SANDBOX_API_PORT, default 8081). The worker setting, the container
port and both gateway policies use this one value.
*/}}
{{- define "hephaestus.gatewayPort" -}}
8081
{{- end -}}

{{- define "hephaestus.image" -}}
{{- index .root.Values.images .name -}}
{{- end -}}

{{/* One environment variable read from the release Secret. */}}
{{- define "hephaestus.secretEnv" -}}
- name: {{ .env }}
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.secret.name }}
      key: {{ .key | default .env }}
      {{- if .optional }}
      optional: true
      {{- end }}
{{- end -}}

{{/*
PostgreSQL TLS: one existing Secret in the release namespace. The roles that connect mount only its
ca.crt; the bundled server mounts only its tls.crt and tls.key. No role receives the private key of
another, and the chart creates no certificate, key or CA.
*/}}
{{- define "hephaestus.postgresqlTlsSecret" -}}
{{- required "postgresql.tls.existingSecret is required: name the Secret with the PostgreSQL CA (ca.crt) and, for the bundled server, its certificate and key (tls.crt, tls.key)" .Values.postgresql.tls.existingSecret -}}
{{- end -}}

{{- define "hephaestus.postgresqlCaDirectory" -}}
/etc/hephaestus/postgresql-ca
{{- end -}}

{{/* The CA volume and its mount, for each role that connects to PostgreSQL. */}}
{{- define "hephaestus.postgresqlCaVolume" -}}
- name: postgresql-ca
  secret:
    secretName: {{ include "hephaestus.postgresqlTlsSecret" . }}
    items:
      - key: ca.crt
        path: ca.crt
    defaultMode: 292 # 0444
{{- end -}}

{{- define "hephaestus.postgresqlCaMount" -}}
- name: postgresql-ca
  mountPath: {{ include "hephaestus.postgresqlCaDirectory" . }}
  readOnly: true
{{- end -}}

{{/*
Every role verifies the server's certificate and name. The bundled server is dialled at its Service
name in the release namespace, so its certificate must name exactly that host.
*/}}
{{- define "hephaestus.databaseHost" -}}
{{- if .Values.postgresql.bundled -}}
{{- printf "%s.%s.svc" (include "hephaestus.name" (dict "root" . "component" "postgresql")) .Release.Namespace -}}
{{- else -}}
{{- required "postgresql.external.host is required when postgresql.bundled is false" .Values.postgresql.external.host -}}
{{- end -}}
{{- end -}}

{{- define "hephaestus.databasePort" -}}
{{- if .Values.postgresql.bundled -}}5432{{- else -}}{{ int .Values.postgresql.external.port }}{{- end -}}
{{- end -}}

{{- define "hephaestus.databaseUrl" -}}
{{- printf "postgresql://%s:%s/%s?sslmode=verify-full&sslrootcert=%s/ca.crt" (include "hephaestus.databaseHost" .) (include "hephaestus.databasePort" .) .Values.postgresql.database (include "hephaestus.postgresqlCaDirectory" .) -}}
{{- end -}}

{{- define "hephaestus.natsUrl" -}}
{{- if .Values.nats.bundled -}}
{{- printf "nats://%s:4222" (include "hephaestus.name" (dict "root" . "component" "nats")) -}}
{{- else -}}
{{- required "nats.external.url is required when nats.bundled is false" .Values.nats.external.url -}}
{{- end -}}
{{- end -}}

{{/*
Downward-API pod address. The management listener (9090) binds to it, the counterpart of
"$HOSTNAME.shared-network" in Compose: reachable for probes and the metrics scraper, never through a
Service or the Gateway.
*/}}
{{- define "hephaestus.env.pod" -}}
- name: POD_IP
  valueFrom:
    fieldRef:
      fieldPath: status.podIP
- name: MANAGEMENT_SERVER_ADDRESS
  value: $(POD_IP)
{{- end -}}

{{/* What the verified release lock says this container runs, reported under `release` in /actuator/info. */}}
{{- define "hephaestus.env.release" -}}
- name: APP_VERSION
  value: {{ trimPrefix "v" .Values.release.version | quote }}
- name: HEPHAESTUS_RELEASE_COMMIT
  value: {{ .Values.release.commit | quote }}
- name: HEPHAESTUS_IMAGE_APPLICATION_SERVER
  value: {{ include "hephaestus.image" (dict "root" . "name" "application-server") | quote }}
- name: DEPLOYMENT_ENVIRONMENT
  value: {{ .Values.deploymentEnvironment | quote }}
- name: TRACING_SAMPLING_PROBABILITY
  value: {{ .Values.config.tracing.samplingProbability | quote }}
- name: TRACING_OTLP_ENABLED
  value: {{ .Values.config.tracing.otlpEnabled | quote }}
- name: TRACING_OTLP_ENDPOINT
  value: {{ .Values.config.tracing.otlpEndpoint | quote }}
{{- end -}}

{{/* Settings every application role reads. */}}
{{- define "hephaestus.env.shared" -}}
- name: APPLICATION_HOST_URL
  value: {{ printf "https://%s" .Values.host | quote }}
- name: HEPHAESTUS_TRUSTED_PROXIES
  value: {{ .Values.trustedProxies | quote }}
- name: DATABASE_URL
  value: {{ include "hephaestus.databaseUrl" . | quote }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "DATABASE_USERNAME") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "DATABASE_PASSWORD") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_SECURITY_ENCRYPTION_KEY") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY") }}
- name: HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY_VERSION
  value: {{ .Values.config.credentialKey.version | int | quote }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_SECURITY_PRIOR_CREDENTIAL_ENCRYPTION_KEY" "optional" true) }}
- name: HEPHAESTUS_SECURITY_PRIOR_CREDENTIAL_ENCRYPTION_KEY_VERSION
  value: {{ .Values.config.credentialKey.priorVersion | quote }}
- name: HEPHAESTUS_SECURITY_CREDENTIAL_ROTATION_ENABLED
  value: {{ .Values.config.credentialKey.rotationEnabled | quote }}
{{- /* Both agent image guards load in every role (ADR 0031): a reference the workers cannot run
fails every pod, so each role is owed the lock's digest. */}}
- name: HEPHAESTUS_AGENT_IMAGE_REQUIRE_DIGEST
  value: "true"
- name: HEPHAESTUS_AGENT_IMAGE_REFERENCE
  value: {{ include "hephaestus.image" (dict "root" . "name" "agent-pi") | quote }}
{{- /* Outline and Slack load role-agnostic beans; every role gets the same switches. */}}
- name: HEPHAESTUS_INTEGRATION_OUTLINE_ENABLED
  value: {{ .Values.config.outline.enabled | quote }}
- name: HEPHAESTUS_INTEGRATION_OUTLINE_ALLOWED_ORIGINS
  value: {{ .Values.config.outline.allowedOrigins | quote }}
- name: HEPHAESTUS_INTEGRATION_SLACK_ENABLED
  value: {{ .Values.config.slack.enabled | quote }}
{{- end -}}

{{/* The server and webhook roles both talk to JetStream and verify webhook deliveries. */}}
{{- define "hephaestus.env.ingestion" -}}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "NATS_USERNAME") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "NATS_PASSWORD") }}
- name: NATS_DURABLE_CONSUMER_NAME
  value: {{ .Values.config.durableConsumerName | quote }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "WEBHOOK_SECRET") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "WEBHOOK_ROUTING_SECRET") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "WEBHOOK_ROUTING_PREVIOUS_SECRET" "optional" true) }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET" "optional" true) }}
{{- end -}}

{{/* The server and worker roles both reach the SCM. */}}
{{- define "hephaestus.env.scm" -}}
# JGit writes its own configuration (jgit/config and its lock) under XDG_CONFIG_HOME, which defaults to
# $HOME/.config on the read-only root: a repository preparation then fails. /tmp is an emptyDir.
- name: XDG_CONFIG_HOME
  value: /tmp/.config
- name: XDG_CACHE_HOME
  value: /tmp/.cache
- name: GH_APP_ID
  value: {{ .Values.config.github.appId | int | quote }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "GH_APP_PRIVATE_KEY" "optional" true) }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "GH_AUTH_TOKEN" "optional" true) }}
- name: GIT_CHECKOUT_ENABLED
  value: {{ .Values.config.gitCheckoutEnabled | quote }}
- name: GITLAB_ENABLED
  value: {{ .Values.config.gitlab.enabled | quote }}
- name: GITLAB_DEFAULT_SERVER_URL
  value: {{ .Values.config.gitlab.oauthBaseUrl | quote }}
- name: HEPHAESTUS_FABRIC_ROOT
  value: /data/git-repos
{{- end -}}

{{/*
Probes read the management listener's health groups, the same groups that /livez and /readyz serve
on the application port. The worker binds its application port to loopback, so only the management
port is reachable on every role.
*/}}
{{/*
A pause before the container stops, so that the Gateway and the Service stop sending it requests
first. The kubelet runs it; the image needs no shell.
*/}}
{{- define "hephaestus.preStop" -}}
lifecycle:
  preStop:
    sleep:
      seconds: 5
{{- end -}}

{{- define "hephaestus.probes" -}}
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: {{ .startupFailures }}
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  periodSeconds: 15
  timeoutSeconds: 5
  failureThreshold: 3
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: management
  periodSeconds: 10
  timeoutSeconds: 5
  failureThreshold: 3
{{- end -}}

{{/* A NetworkPolicy peer: pods with these labels in this namespace. */}}
{{- define "hephaestus.peer" -}}
- namespaceSelector:
    matchLabels:
      kubernetes.io/metadata.name: {{ .namespace }}
  podSelector: {{ dict "matchLabels" (.podLabels | default dict) | toJson }}
{{- end -}}

{{- define "hephaestus.componentPeer" -}}
- podSelector:
    matchLabels:
      {{- include "hephaestus.selectorLabels" . | nindent 6 }}
{{- end -}}

{{/* NetworkPolicy rules that several components share. Each is called with the root context. */}}
{{- define "hephaestus.policy.databaseEgress" -}}
{{- if .Values.postgresql.bundled -}}
- to:
    {{- include "hephaestus.componentPeer" (dict "root" . "component" "postgresql") | nindent 4 }}
  ports:
    - port: 5432
      protocol: TCP
{{- else -}}
- to:
    - ipBlock:
        cidr: {{ .Values.postgresql.external.cidr }}
  ports:
    - port: {{ .Values.postgresql.external.port }}
      protocol: TCP
{{- end -}}
{{- end -}}

{{- define "hephaestus.policy.natsEgress" -}}
{{- if .Values.nats.bundled -}}
- to:
    {{- include "hephaestus.componentPeer" (dict "root" . "component" "nats") | nindent 4 }}
  ports:
    - port: 4222
      protocol: TCP
{{- else -}}
- to:
    - ipBlock:
        cidr: {{ .Values.nats.external.cidr }}
  ports:
    - port: {{ .Values.nats.external.port }}
      protocol: TCP
{{- end -}}
{{- end -}}

{{/* Model calls go out from the server (connection test) and the worker (LLM proxy); both must agree. */}}
{{- define "hephaestus.env.modelEgress" -}}
- name: HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS
  value: {{ join "," .Values.config.llmPrivateHosts | quote }}
- name: HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS_ALLOW_HTTP
  value: {{ .Values.config.llmPrivateHostsAllowHttp | quote }}
{{- end -}}

{{/* Model endpoints on a private network outside the cluster, which externalEgress leaves out. */}}
{{- define "hephaestus.policy.modelEgress" -}}
{{- range .Values.networkPolicy.modelEndpoints }}
- to:
    - ipBlock:
        cidr: {{ .cidr }}
  ports:
    {{- range .ports }}
    - port: {{ . }}
      protocol: TCP
    {{- end }}
{{- end }}
{{- end -}}

{{/* HTTPS to the internet: Git providers, OAuth, AI providers. Private ranges stay excluded. */}}
{{- define "hephaestus.policy.externalEgress" -}}
- to:
    - ipBlock:
        cidr: {{ .Values.networkPolicy.externalEgress.cidr }}
        {{- with .Values.networkPolicy.externalEgress.except }}
        except:
          {{- toYaml . | nindent 10 }}
        {{- end }}
  ports:
    - port: 443
      protocol: TCP
{{- end -}}

{{/*
The sandbox pods that may reach the workers' gateway: review sandboxes and conversation hosts, by their kind.
A sandbox's credential, not this selector, binds it to its own worker. Turn sandboxes never match.
*/}}
{{- define "hephaestus.policy.gatewaySources" -}}
matchExpressions:
  - key: hephaestus.build/sandbox-kind
    operator: In
    values: ["review", "host"]
{{- end -}}

{{- define "hephaestus.policy.metricsIngress" -}}
{{- if and .Values.networkPolicy.metrics.namespace (not .Values.networkPolicy.metrics.podLabels) -}}
{{- fail "networkPolicy.metrics.podLabels needs at least one label: a namespace alone would admit every pod in it." -}}
{{- end -}}
{{- if .Values.networkPolicy.metrics.namespace -}}
- from:
    {{- include "hephaestus.peer" .Values.networkPolicy.metrics | nindent 4 }}
  ports:
    - port: management
      protocol: TCP
{{- end -}}
{{- end -}}

{{/* The pgBackRest repository, as deploy/postgresql/pgbackrest.conf has it, on the configured bucket. */}}
{{- define "hephaestus.backup.repository" -}}
{{- $s3 := .Values.postgresql.backup.s3 -}}
repo1-type=s3
repo1-path=/hephaestus
repo1-s3-bucket={{ $s3.bucket }}
repo1-s3-endpoint={{ $s3.endpoint }}
repo1-s3-region={{ $s3.region }}
repo1-s3-uri-style={{ $s3.uriStyle }}
repo1-storage-port={{ $s3.port | int }}
{{- if $s3.privateCa }}
repo1-storage-ca-file=/etc/hephaestus/s3-ca/s3-ca.crt
{{- end }}
repo1-cipher-type=aes-256-cbc
repo1-retention-full={{ .Values.postgresql.backup.retentionFull | int }}
log-level-console=info
log-level-file=off
lock-path=/tmp/pgbackrest
{{- end -}}

{{/* The repository's credentials, from the release Secret. */}}
{{- define "hephaestus.backup.env" -}}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "PGBACKREST_REPO1_S3_KEY" "key" "BACKUP_S3_KEY") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "PGBACKREST_REPO1_S3_KEY_SECRET" "key" "BACKUP_S3_KEY_SECRET") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "PGBACKREST_REPO1_CIPHER_PASS" "key" "BACKUP_CIPHER_PASS") }}
{{- end -}}

{{/* The repository, from PostgreSQL's Pod and the backup Jobs. */}}
{{- define "hephaestus.policy.backupEgress" -}}
{{- $s3 := .Values.postgresql.backup.s3 -}}
{{- if $s3.cidr -}}
- to:
    - ipBlock:
        cidr: {{ $s3.cidr }}
  ports:
    - port: {{ $s3.port }}
      protocol: TCP
{{- else -}}
- to:
    - ipBlock:
        cidr: {{ .Values.networkPolicy.externalEgress.cidr }}
        {{- with .Values.networkPolicy.externalEgress.except }}
        except:
          {{- toYaml . | nindent 10 }}
        {{- end }}
  ports:
    - port: {{ $s3.port }}
      protocol: TCP
{{- end }}
{{- end -}}

{{/* The CA of a private S3 endpoint, for each Pod that reaches the repository. */}}
{{- define "hephaestus.backup.caMount" -}}
{{- if .Values.postgresql.backup.s3.privateCa }}
- name: s3-ca
  mountPath: /etc/hephaestus/s3-ca
  readOnly: true
{{- end }}
{{- end -}}

{{- define "hephaestus.backup.caVolume" -}}
{{- if .Values.postgresql.backup.s3.privateCa }}
- name: s3-ca
  secret:
    secretName: {{ include "hephaestus.postgresqlTlsSecret" . }}
    items:
      - key: s3-ca.crt
        path: s3-ca.crt
    defaultMode: 292 # 0444
{{- end }}
{{- end -}}

{{/* The Pod of one backup Job: the pgBackRest client of the release's PostgreSQL image. */}}
{{- define "hephaestus.backup.pod" -}}
{{- $root := .root -}}
metadata:
  labels:
    {{- include "hephaestus.labels" (dict "root" $root "component" "postgresql-backup") | nindent 4 }}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  priorityClassName: {{ include "hephaestus.priorityClass" (dict "root" .root "tier" "roles") }}
  enableServiceLinks: false
  securityContext:
    {{- include "hephaestus.podSecurityContext" (dict "user" 999 "group" 999) | nindent 4 }}
  containers:
    - name: pgbackrest
      image: {{ include "hephaestus.image" (dict "root" $root "name" "postgres") | quote }}
      imagePullPolicy: IfNotPresent
      command: ["sh", "-c", {{ .command | quote }}]
      env:
        - name: PGBACKREST_CONFIG
          value: /etc/pgbackrest/client.conf
        {{- include "hephaestus.backup.env" $root | nindent 8 }}
      resources:
        {{- toYaml $root.Values.postgresql.backup.resources | nindent 8 }}
      securityContext:
        {{- include "hephaestus.containerSecurityContext" $root | nindent 8 }}
      volumeMounts:
        - name: config
          mountPath: /etc/pgbackrest/client.conf
          subPath: client.conf
          readOnly: true
        - name: tls
          mountPath: /etc/pgbackrest/tls
          readOnly: true
        - name: tmp
          mountPath: /tmp
        {{- include "hephaestus.backup.caMount" $root | nindent 8 }}
  volumes:
    {{- include "hephaestus.backup.caVolume" $root | nindent 4 }}
    - name: config
      configMap:
        name: {{ include "hephaestus.name" (dict "root" $root "component" "postgresql-backup") }}
    # The backup client certificate. The key belongs to root and the fsGroup 999.
    - name: tls
      secret:
        secretName: {{ required "postgresql.backup.clientTlsSecret is required with backups" $root.Values.postgresql.backup.clientTlsSecret }}
        items:
          - key: ca.crt
            path: ca.crt
          - key: tls.crt
            path: backup.crt
          - key: tls.key
            path: backup.key
        defaultMode: 288 # 0440
    - name: tmp
      emptyDir:
        sizeLimit: 1Gi
{{- end -}}

{{/*
"true" while a restore runs: the database roles and PostgreSQL stop, and the restore Job owns the
PostgreSQL volume.
*/}}
{{- define "hephaestus.restoring" -}}
{{- and .Values.postgresql.bundled .Values.postgresql.backup.enabled .Values.postgresql.backup.restore.enabled -}}
{{- end -}}

{{/*
The conversation turns that one worker runs at once, as a number: worker.conversationTurns, where auto is
the autoConversationTurns of sandbox-pod-requests.json, the number that the worker itself resolves auto to. The worker's capacity, the server's
capacity and the sandbox quotas all read it here.
*/}}
{{- define "hephaestus.conversationTurns" -}}
{{- if eq (toString .Values.worker.conversationTurns) "auto" -}}
{{- (.Files.Get "sandbox-pod-requests.json" | fromJson).autoConversationTurns | int -}}
{{- else -}}
{{- .Values.worker.conversationTurns | int -}}
{{- end -}}
{{- end -}}

{{/* A database role's replica count: its own, or 0 while a restore runs. */}}
{{- define "hephaestus.replicas" -}}
{{- if eq (include "hephaestus.restoring" .root) "true" -}}0{{- else -}}{{ .replicas }}{{- end -}}
{{- end -}}

{{/* PostgreSQL's side of pgBackRest: its configuration, the database user, the repository's credentials. */}}
{{- define "hephaestus.backup.serverEnv" -}}
- name: PGBACKREST_CONFIG
  value: /etc/pgbackrest/server.conf
- name: PGBACKREST_PG1_USER
  valueFrom:
    secretKeyRef:
      name: {{ .Values.secret.name }}
      key: DATABASE_USERNAME
{{ include "hephaestus.backup.env" . }}
{{- end -}}
