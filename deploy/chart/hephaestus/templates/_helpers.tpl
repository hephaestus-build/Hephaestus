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
only the Pods whose own specification changed, not PostgreSQL on every release.
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
The namespace of review and conversation sandboxes. The operator creates
it with the Restricted Pod Security level and names it here; the chart writes into it and never
creates it. The server receives the same string, and the runtime derives no name of its own.
*/}}
{{- define "hephaestus.sandboxNamespace" -}}
{{- $namespace := required "sandbox.namespace is required: name the namespace that the operator created for sandboxes" .root.Values.sandbox.namespace -}}
{{- if eq $namespace .root.Release.Namespace -}}
{{- fail "sandbox.namespace must not be the release namespace: sandboxes need a namespace of their own" -}}
{{- end -}}
{{- $namespace -}}
{{- end -}}

{{/*
The Sandbox Gateway port (SANDBOX_API_PORT, default 8081). The server setting, the container
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
A private CA for Git providers. The buildpack's launcher adds this file to the
JVM truststore at start, so sign-in, API calls and Git fetches all trust it, and the public CAs stay trusted.
*/}}
{{- define "hephaestus.privateCaFile" -}}
/etc/hephaestus/private-ca/ca.crt
{{- end -}}

{{- define "hephaestus.privateCaEnv" -}}
{{- if .Values.config.privateCa.existingSecret }}
- name: SSL_CERT_FILE
  value: {{ include "hephaestus.privateCaFile" . | quote }}
{{- end }}
{{- end -}}

{{- define "hephaestus.privateCaVolume" -}}
{{- if .Values.config.privateCa.existingSecret }}
- name: private-ca
  secret:
    secretName: {{ .Values.config.privateCa.existingSecret }}
    items:
      - key: {{ .Values.config.privateCa.key }}
        path: ca.crt
    defaultMode: 292 # 0444
{{- end }}
{{- end -}}

{{- define "hephaestus.privateCaMount" -}}
{{- if .Values.config.privateCa.existingSecret }}
- name: private-ca
  mountPath: /etc/hephaestus/private-ca
  readOnly: true
{{- end }}
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

{{/*
Each database purpose has its own login (docs/admin/helm-chart.md § Database roles). When the release Secret
exists, a missing key fails the render, and so do the keys of the single superuser login before 1.0, which no
Pod reads. Without a cluster, as in `helm template`, the kubelet refuses a Pod whose key is missing.
*/}}
{{- define "hephaestus.databaseSecretCheck" -}}
{{- $secret := lookup "v1" "Secret" .Release.Namespace .Values.secret.name -}}
{{- if $secret -}}
{{- $data := $secret.data | default dict -}}
{{- $required := list "DATABASE_OWNER_PASSWORD" "DATABASE_APP_PASSWORD" -}}
{{- if .Values.postgresql.bundled -}}
{{- $required = concat (list "DATABASE_ADMIN_USERNAME" "DATABASE_ADMIN_PASSWORD") $required -}}
{{- end -}}
{{- range $required -}}
{{- if not (hasKey $data .) -}}
{{- fail (printf "the Secret %s needs the key %s: see docs/admin/helm-chart.md § Database roles" $.Values.secret.name .) -}}
{{- end -}}
{{- end -}}
{{- range list "DATABASE_USERNAME" "DATABASE_PASSWORD" -}}
{{- if hasKey $data . -}}
{{- fail (printf "the Secret %s still has the key %s: the administrator's login is DATABASE_ADMIN_USERNAME and DATABASE_ADMIN_PASSWORD, and no Hephaestus Pod uses it. Follow the 1.0 upgrade guide, then remove the key" $.Values.secret.name .) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "hephaestus.databaseUrl" -}}
{{- printf "postgresql://%s:%s/%s?sslmode=verify-full&sslrootcert=%s/ca.crt" (include "hephaestus.databaseHost" .) (include "hephaestus.databasePort" .) .Values.postgresql.database (include "hephaestus.postgresqlCaDirectory" .) -}}
{{- end -}}



{{/*
Downward-API pod address. The management listener (9090) binds to it: reachable for probes and the metrics scraper, never through a
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

{{/*
The release that the verified release lock names, for every container of the application image. The image is
promoted without a rebuild, so the JAR cannot know its release: each log record, span and metric of the
container names this version, commit and environment.
*/}}
{{- define "hephaestus.env.releaseIdentity" -}}
- name: APP_VERSION
  value: {{ trimPrefix "v" .Values.release.version | quote }}
- name: HEPHAESTUS_RELEASE_COMMIT
  value: {{ .Values.release.commit | quote }}
- name: DEPLOYMENT_ENVIRONMENT
  value: {{ .Values.deploymentEnvironment | quote }}
{{- end -}}

{{/* What the verified release lock says this container runs, reported under `release` in /actuator/info. */}}
{{- define "hephaestus.env.release" -}}
{{ include "hephaestus.env.releaseIdentity" . }}
- name: HEPHAESTUS_IMAGE_APPLICATION_SERVER
  value: {{ include "hephaestus.image" (dict "root" . "name" "application-server") | quote }}
- name: TRACING_SAMPLING_PROBABILITY
  value: {{ .Values.config.tracing.samplingProbability | quote }}
- name: TRACING_OTLP_ENABLED
  value: {{ .Values.config.tracing.otlpEnabled | quote }}
- name: TRACING_OTLP_ENDPOINT
  value: {{ .Values.config.tracing.otlpEndpoint | quote }}
{{- end -}}

{{/* The active system key. The migration Job reads it too, because it migrates sealed values. */}}
{{- define "hephaestus.env.systemKey" -}}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_SECURITY_ENCRYPTION_KEY") }}
{{- end -}}

{{/* The application roles use the same stored-secret keys. */}}
{{- define "hephaestus.env.encryptionKeys" -}}
{{ include "hephaestus.env.systemKey" . }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_SECURITY_PRIOR_ENCRYPTION_KEY" "optional" true) }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY") }}
- name: HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY_VERSION
  value: {{ .Values.config.credentialKey.version | int | quote }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_SECURITY_PRIOR_CREDENTIAL_ENCRYPTION_KEY" "optional" true) }}
- name: HEPHAESTUS_SECURITY_PRIOR_CREDENTIAL_ENCRYPTION_KEY_VERSION
  value: {{ .Values.config.credentialKey.priorVersion | quote }}
{{- end -}}

{{/* Settings every application role reads. */}}
{{- define "hephaestus.env.shared" -}}
- name: APPLICATION_HOST_URL
  value: {{ printf "https://%s" .Values.host | quote }}
- name: HEPHAESTUS_TRUSTED_PROXIES
  value: {{ .Values.trustedProxies | quote }}
{{- /* Both agent image guards load in every role (ADR 0031): a reference the server cannot run
fails every pod, so each role is owed the lock's digest. */}}
- name: HEPHAESTUS_AGENT_IMAGE_REQUIRE_DIGEST
  value: "true"
- name: HEPHAESTUS_AGENT_IMAGE_REFERENCE
  value: {{ include "hephaestus.image" (dict "root" . "name" "agent-pi") | quote }}
{{- /* Outline and Slack switches. */}}
- name: HEPHAESTUS_INTEGRATION_OUTLINE_ENABLED
  value: {{ .Values.config.outline.enabled | quote }}
- name: HEPHAESTUS_INTEGRATION_OUTLINE_ALLOWED_ORIGINS
  value: {{ .Values.config.outline.allowedOrigins | join "," | quote }}
- name: HEPHAESTUS_INTEGRATION_SLACK_ENABLED
  value: {{ .Values.config.slack.enabled | quote }}
{{- end -}}

{{/* The server reads the database and what it seals. */}}
{{- define "hephaestus.env.storedData" -}}
- name: DATABASE_URL
  value: {{ include "hephaestus.databaseUrl" . | quote }}
- name: DATABASE_USERNAME
  value: hephaestus_app
{{ include "hephaestus.secretEnv" (dict "root" . "env" "DATABASE_PASSWORD" "key" "DATABASE_APP_PASSWORD") }}
{{ include "hephaestus.env.encryptionKeys" . }}
- name: HEPHAESTUS_SECURITY_CREDENTIAL_ROTATION_ENABLED
  value: {{ .Values.config.credentialKey.rotationEnabled | quote }}
- name: HEPHAESTUS_SECURITY_ENCRYPTION_ROTATION_ENABLED
  value: {{ .Values.config.systemKey.rotationEnabled | quote }}
{{- end -}}

{{/* The server verifies webhook deliveries. */}}
{{- define "hephaestus.env.ingestion" -}}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "WEBHOOK_SECRET") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "WEBHOOK_ROUTING_SECRET") }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "WEBHOOK_ROUTING_PREVIOUS_SECRET" "optional" true) }}
{{ include "hephaestus.secretEnv" (dict "root" . "env" "HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET" "optional" true) }}
{{- end -}}

{{/* The server reaches the SCM. */}}
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
  value: "true"
- name: GITLAB_ENABLED
  value: {{ .Values.config.gitlab.enabled | quote }}
- name: GITLAB_DEFAULT_SERVER_URL
  value: {{ .Values.config.gitlab.oauthBaseUrl | quote }}
- name: HEPHAESTUS_FABRIC_ROOT
  value: /data/git-repos
{{- end -}}

{{/*
Probes read the management listener's health groups, the same groups that /livez and /readyz serve
on the application port. Only the management port is reachable for probes on every role.
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



{{/* OTLP traces to an in-cluster collector, on the port that the endpoint names. */}}
{{- define "hephaestus.policy.traceEgress" -}}
{{- $collector := .Values.networkPolicy.traceCollector -}}
{{- if and $collector.namespace (not $collector.podLabels) -}}
{{- fail "networkPolicy.traceCollector.podLabels needs at least one label: a namespace alone would admit every pod in it." -}}
{{- end -}}
{{- if and $collector.namespace .Values.config.tracing.otlpEnabled -}}
{{- $endpoint := urlParse .Values.config.tracing.otlpEndpoint -}}
{{- $port := regexFind ":[0-9]+$" $endpoint.host | trimPrefix ":" | default (ternary "443" "80" (eq $endpoint.scheme "https")) | atoi -}}
{{- if or (lt $port 1) (gt $port 65535) -}}
{{- fail "config.tracing.otlpEndpoint names a port outside 1-65535." -}}
{{- end -}}
- to:
    {{- include "hephaestus.peer" $collector | nindent 4 }}
  ports:
    - port: {{ $port }}
      protocol: TCP
{{- end -}}
{{- end -}}

{{/* Model calls go out from the server: the connection test and the LLM proxy. */}}
{{- define "hephaestus.env.modelEgress" -}}
- name: HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS
  value: {{ join "," .Values.config.llmPrivateHosts | quote }}
- name: HEPHAESTUS_LLM_EGRESS_PRIVATE_HOSTS_ALLOW_HTTP
  value: {{ .Values.config.llmPrivateHostsAllowHttp | quote }}
- name: HEPHAESTUS_SCM_EGRESS_PRIVATE_HOSTS
  value: {{ join "," .Values.config.scmPrivateHosts | quote }}
{{- end -}}

{{/* Model endpoints and Git providers on a private network outside the cluster, which externalEgress leaves out. */}}
{{- define "hephaestus.policy.modelEgress" -}}
{{- range concat .Values.networkPolicy.modelEndpoints .Values.networkPolicy.scmEndpoints }}
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
The sandbox pods that may reach the servers' gateway, by their kind: review sandboxes and the sandboxes of
every SandboxTemplate kind. A sandbox's credential, not this selector, binds it to its own subject. Tools
Pods never match.
*/}}
{{- define "hephaestus.policy.gatewaySources" -}}
matchExpressions:
  - key: hephaestus.build/sandbox-kind
    operator: In
    values: ["review", "conversation"]
{{- end -}}

{{/* The scraper's way in, on the named port of the metrics: management for the roles, metrics for the exporter. */}}
{{- define "hephaestus.policy.metricsIngress" -}}
{{- $root := .root -}}
{{- if and $root.Values.networkPolicy.metrics.namespace (not $root.Values.networkPolicy.metrics.podLabels) -}}
{{- fail "networkPolicy.metrics.podLabels needs at least one label: a namespace alone would admit every pod in it." -}}
{{- end -}}
{{- if $root.Values.networkPolicy.metrics.namespace }}
- from:
    {{- include "hephaestus.peer" $root.Values.networkPolicy.metrics | nindent 4 }}
  ports:
    - port: {{ .port }}
      protocol: TCP
{{- end }}
{{- if $root.Values.observability.stack.enabled }}
- from:
    {{- include "hephaestus.componentPeer" (dict "root" $root "component" "victoria-metrics") | nindent 4 }}
  ports:
    - port: {{ .port }}
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

{{/* CPU quantities in millicores, for resident role reservations. */}}
{{- define "hephaestus.cpuMillis" -}}
{{- $cpu := toString . -}}
{{- if not (regexMatch "^[0-9]+([.][0-9]+)?m?$" $cpu) -}}
{{- fail "Automatic turn admission requires CPU requests in cores or millicores" -}}
{{- end -}}
{{- if hasSuffix "m" $cpu -}}
{{- trimSuffix "m" $cpu | float64 | ceil | int -}}
{{- else -}}
{{- mulf (float64 $cpu) 1000 | ceil | int -}}
{{- end -}}
{{- end -}}

{{/* A memory quantity in MiB. The chart's sizes are whole Mi or Gi, so any other unit is refused rather than guessed. */}}
{{- define "hephaestus.memoryMi" -}}
{{- $value := .value | toString -}}
{{- if regexMatch "^[1-9][0-9]*Gi$" $value -}}{{- mul (trimSuffix "Gi" $value | int) 1024 -}}
{{- else if regexMatch "^[1-9][0-9]*Mi$" $value -}}{{- trimSuffix "Mi" $value | int -}}
{{- else -}}{{- fail (printf "%s must be a whole number of Mi or Gi" .name) -}}
{{- end -}}
{{- end -}}

{{/* The bytes of one role's Git cache volume, so the role evicts mirrors before the kubelet evicts the Pod. */}}
{{- define "hephaestus.gitCacheBytes" -}}
{{- mul (include "hephaestus.memoryMi" (dict "value" .gitCache.sizeLimit "name" (printf "%s.gitCache.sizeLimit" .role)) | int64) 1048576 -}}
{{- end -}}



{{/* Schema-supported Kubernetes quantities, rounded up to the quota's MiB or millicore unit. */}}
{{- define "hephaestus.quantityUnits" -}}
{{- $value := toString .value -}}
{{- $amount := regexFind "^[0-9]+([.][0-9]+)?" $value -}}
{{- $suffix := trimPrefix $amount $value -}}
{{- $scales := dict "" 1 "m" 0.001 "k" 1000 "M" 1000000 "G" 1000000000 "T" 1000000000000 "P" 1000000000000000 "E" 1000000000000000000 "Ki" 1024 "Mi" 1048576 "Gi" 1073741824 "Ti" 1099511627776 "Pi" 1125899906842624 "Ei" 1152921504606846976 -}}
{{- $units := divf (mulf $amount (get $scales $suffix)) .unit -}}
{{- if .roundDown -}}{{- $units | floor | int64 -}}{{- else -}}{{- $units | ceil | int64 -}}{{- end -}}
{{- end -}}

{{/*
What one template sandbox's Pod counts in a quota, as JSON: the requests of its SandboxTemplate, which the
template Pod takes as they are, and the overhead of the templates' RuntimeClass.
*/}}
{{- define "hephaestus.templateSandbox" -}}
{{- $templates := .root.Values.sandbox.templates -}}
{{- $requests := (get $templates .kind).resources.requests -}}
{{- dict
  "memoryMi" (add (include "hephaestus.quantityUnits" (dict "value" (get $requests "memory") "unit" 1048576) | int) (int $templates.podOverhead.memoryMi))
  "cpuMillis" (add (include "hephaestus.quantityUnits" (dict "value" (get $requests "cpu") "unit" 0.001) | int) (int $templates.podOverhead.cpuMillis))
  "ephemeralStorageMi" (include "hephaestus.quantityUnits" (dict "value" (get $requests "ephemeral-storage") "unit" 1048576) | int)
  | toJson -}}
{{- end -}}

{{/*
The reviews that the sandbox nodes hold at once: the memory they give review sandboxes over what one review sandbox
counts. A smaller request in the review template runs more reviews in the same memory.
*/}}
{{- define "hephaestus.clusterReviews" -}}
{{- $memory := include "hephaestus.memoryMi" (dict "value" .Values.sandbox.reviewMemory "name" "sandbox.reviewMemory") | int -}}
{{- $review := int (include "hephaestus.templateSandbox" (dict "root" . "kind" "review") | fromJson).memoryMi -}}
{{- if lt $memory $review -}}
{{- fail (printf "sandbox.reviewMemory holds no review: one review sandbox requests %dMi" $review) -}}
{{- end -}}
{{- div $memory $review -}}
{{- end -}}

{{/*
The reviews that one server runs at once: the sandbox nodes' reviews. Each costs the server about 18 MiB of threads
and heap (estimated), which server.resources holds.
*/}}
{{- define "hephaestus.serverReviews" -}}
{{- include "hephaestus.clusterReviews" . -}}
{{- end -}}

{{/* The reviews that all servers run at once, which the reviews tier's quota holds. */}}
{{- define "hephaestus.reviews" -}}
{{- include "hephaestus.clusterReviews" . -}}
{{- end -}}

{{/* The review tier CPU requests, including RuntimeClass overhead for each review sandbox. */}}
{{- define "hephaestus.reviewCpuMillis" -}}
{{- $requests := include "hephaestus.templateSandbox" (dict "root" . "kind" "review") | fromJson -}}
{{- mul (int (include "hephaestus.reviews" .)) (int $requests.cpuMillis) -}}
{{- end -}}

{{/* CPU and memory requests of a rendered Pod, including its largest init container. */}}
{{- define "hephaestus.podCapacity" -}}
{{- $result := dict "cpu" 0 "memory" 0 -}}
{{- range $resource, $unit := dict "cpu" 0.001 "memory" 1048576 -}}
{{- $running := 0 -}}
{{- range $.containers -}}
{{- $requests := (default dict .resources).requests | default dict -}}
{{- $running = add $running (include "hephaestus.quantityUnits" (dict "value" (get $requests $resource | default "0") "unit" $unit) | int64) -}}
{{- end -}}
{{- $initializing := 0 -}}
{{- range $.initContainers -}}
{{- $requests := (default dict .resources).requests | default dict -}}
{{- $initializing = max $initializing (include "hephaestus.quantityUnits" (dict "value" (get $requests $resource | default "0") "unit" $unit) | int64) -}}
{{- end -}}
{{- $_ := set $result $resource (max $running $initializing) -}}
{{- end -}}
{{- $result | toJson -}}
{{- end -}}

{{/*
Fixed requests from the workload templates themselves. The measurement render disables turn admission,
so the server's capacity environment cannot recursively ask for its own fixed requests. Resource inputs
are unchanged. Steady includes one backup; upgrade includes migration/stanza Jobs and rollout surge.
*/}}
{{- define "hephaestus.fixedCapacity" -}}
{{- $values := deepCopy .Values -}}
{{- $_ := set $values.sandbox "conversationTurns" 0 -}}
{{- $_ := set $values.sandbox "budget" dict -}}
{{- $context := omit . "Values" -}}
{{- $_ := set $context "Values" $values -}}
{{- $totals := dict "steady" (dict "cpu" 0 "memory" 0) "upgrade" (dict "cpu" 0 "memory" 0) -}}
{{- $backup := false -}}
{{- range $file := list "server.yaml" "webapp.yaml" "postgresql.yaml" "migration.yaml" "postgresql-backup.yaml" "postgresql-metrics.yaml" "observability-stack.yaml" "agent-sandbox-controller.yaml" "sandbox-templates.yaml" -}}
{{- range $document := splitList "\n---" (include (print $.Template.BasePath "/" $file) $context) -}}
{{- $object := fromYaml $document | default dict -}}
{{- $kind := get $object "kind" | default "" -}}
{{- if has $kind (list "Deployment" "StatefulSet" "DaemonSet" "Job" "CronJob") -}}
{{- $spec := $object.spec -}}
{{- $steady := 1 -}}
{{- $upgrade := 1 -}}
{{- if has $kind (list "Deployment" "StatefulSet") -}}
{{- if hasKey $spec "replicas" -}}{{- $steady = int $spec.replicas -}}{{- end -}}
{{- $upgrade = $steady -}}
{{- $strategy := $spec.strategy | default dict -}}
{{- if and (eq $kind "Deployment") (gt $steady 0) (ne (get $strategy "type") "Recreate") -}}
{{- $rolling := $strategy.rollingUpdate | default dict -}}
{{- $surge := "25%" -}}
{{- if hasKey $rolling "maxSurge" -}}{{- $surge = toString $rolling.maxSurge -}}{{- end -}}
{{- if hasSuffix "%" $surge -}}
{{- $upgrade = add $upgrade (divf (mulf $steady (trimSuffix "%" $surge)) 100 | ceil | int) -}}
{{- else -}}{{- $upgrade = add $upgrade (int $surge) -}}{{- end -}}
{{- end -}}
{{- if and (eq $file "server.yaml") (gt $steady 0) -}}
{{- $upgrade = int (include "hephaestus.serverRolloutInstances" $) -}}
{{- end -}}
{{- else if eq $kind "Job" -}}
{{- $steady = 0 -}}
{{- else if eq $kind "CronJob" -}}
{{- /* Full and differential backups share one pgBackRest stanza; only one backup runs at once. */ -}}
{{- if or $backup ($spec.suspend | default false) -}}
{{- $steady = 0 -}}{{- $upgrade = 0 -}}
{{- else -}}{{- $backup = true -}}{{- end -}}
{{- $spec = $spec.jobTemplate.spec -}}
{{- end -}}
{{- $pod := $spec.template.spec -}}
{{- $requests := include "hephaestus.podCapacity" $pod | fromJson -}}
{{- if and $pod.runtimeClassName (eq $pod.runtimeClassName $.Values.sandbox.templates.runtimeClassName) -}}
{{- $_ := set $requests "cpu" (add $requests.cpu $.Values.sandbox.templates.podOverhead.cpuMillis) -}}
{{- $_ := set $requests "memory" (add $requests.memory $.Values.sandbox.templates.podOverhead.memoryMi) -}}
{{- end -}}
{{- range $phase, $count := dict "steady" $steady "upgrade" $upgrade -}}
{{- range $resource := list "cpu" "memory" -}}
{{- $_ := set (get $totals $phase) $resource (add (get (get $totals $phase) $resource) (mul $count (get $requests $resource))) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- $totals | toJson -}}
{{- end -}}

{{/* The largest turn count within both steady and drained-upgrade budgets. */}}
{{- define "hephaestus.conversationTurns" -}}
{{- $value := .Values.sandbox.conversationTurns -}}
{{- $auto := eq (toString $value) "auto" -}}
{{- $budget := .Values.sandbox.budget | default dict -}}
{{- if or $auto (gt (len $budget) 0) -}}
{{- $help := "Set sandbox.budget.cpu and sandbox.budget.memory to the allocatable budget of the sandbox node pool; see https://docs.hephaestus.build/admin/helm-chart#sandbox-capacity-budget" -}}
{{- $cpu := required (printf "sandbox.budget.cpu is required for auto capacity or a declared budget. %s" $help) $budget.cpu -}}
{{- $memory := required (printf "sandbox.budget.memory is required for auto capacity or a declared budget. %s" $help) $budget.memory -}}
{{- $room := dict
  "cpu" (include "hephaestus.quantityUnits" (dict "value" $cpu "unit" 0.001 "roundDown" true) | int64)
  "memory" (include "hephaestus.quantityUnits" (dict "value" $memory "unit" 1048576 "roundDown" true) | int64) -}}
{{- $fixed := include "hephaestus.fixedCapacity" . | fromJson -}}
{{- $review := include "hephaestus.templateSandbox" (dict "root" . "kind" "review") | fromJson -}}
{{- $conversation := include "hephaestus.templateSandbox" (dict "root" . "kind" "conversation") | fromJson -}}
{{- $reviews := 0 -}}
{{- if .Values.sandbox.enabled -}}{{- $reviews = include "hephaestus.reviews" . | int -}}{{- end -}}
{{- $maximum := 2147483647 -}}
{{- range $phase := list "steady" "upgrade" -}}
{{- range $resource, $field := dict "cpu" "cpuMillis" "memory" "memoryMi" -}}
{{- $reserved := int64 (get (get $fixed $phase) $resource) -}}
{{- /* Reviews finish before the drained-upgrade peak. Warm conversation sandboxes can remain. */ -}}
{{- if eq $phase "steady" -}}{{- $reserved = add $reserved (mul $reviews (get $review $field)) -}}{{- end -}}
{{- $available := sub (get $room $resource) $reserved -}}
{{- if lt $available 0 -}}
{{- fail (printf "sandbox.budget.%s cannot hold fixed %s requests: %d required, %d available. %s" $resource $phase $reserved (get $room $resource) $help) -}}
{{- end -}}
{{- $maximum = min $maximum (div $available (get $conversation $field | int64)) -}}
{{- end -}}
{{- end -}}
{{- if not .Values.sandbox.enabled -}}0
{{- else if $auto -}}
{{- if lt $maximum 1 -}}{{- fail (printf "Automatic turn admission has no room for one conversation sandbox in the steady or drained-upgrade budget. %s" $help) -}}{{- end -}}
{{- $maximum -}}
{{- else -}}
{{- $turns := int $value -}}
{{- if gt $turns $maximum -}}
{{- fail (printf "sandbox.conversationTurns=%d exceeds sandbox.budget at steady state or the drained-upgrade peak; at most %d turns fit. %s" $turns $maximum $help) -}}
{{- end -}}
{{- $turns -}}
{{- end -}}
{{- else -}}{{- ternary (int $value) 0 .Values.sandbox.enabled -}}{{- end -}}
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
      key: DATABASE_ADMIN_USERNAME
{{ include "hephaestus.backup.env" . }}
{{- end -}}

{{/* Native Spring properties override profile defaults for this role only. */}}
{{- define "hephaestus.env.databasePool" -}}
{{- if gt (int .minimumIdle) (int .maximumPoolSize) -}}
{{- fail "databasePool.minimumIdle must not exceed maximumPoolSize" -}}
{{- end -}}
- name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
  value: {{ .maximumPoolSize | quote }}
- name: SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE
  value: {{ .minimumIdle | quote }}
{{- end -}}

{{/* A rollout can retain every old server in its drain beside every replacement, beyond maxSurge. */}}
{{- define "hephaestus.serverRolloutInstances" -}}
{{- mul 2 (int .Values.server.replicas) -}}
{{- end -}}

{{/*
The same inbox and review caps that the server reads size its pool. Review preparation can hold its
capture lease, preparation ClusterLock and publication transaction together. A composition can also
hold three connections while it records withholding. Long loads retain at most two capture/transaction pairs (TurnContextLoads),
and LlmCallPermits has two maintenance threads. Model streaming itself holds no pooled connection.
Boot has one pooled connection of its own. The reserve is eight connections for four syncs with two holders each and four for HTTP/control work.
Hikari queues excess callers, then throws SQLException at connection-timeout (45000 ms in application.yml).
Loaded qualification measures that reserve; the bounded holders already fit without it.
The full overlap budget also reserves optional monitoring and backup sessions when disabled.
*/}}
{{- define "hephaestus.databaseConnectionCapacity" -}}
{{- $captures := 0 -}}
{{- $loads := 0 -}}
{{- $permits := 0 -}}
{{- if .Values.sandbox.enabled -}}
{{- $captures = int (include "hephaestus.serverReviews" .) -}}
{{- $loads = 2 -}}
{{- $permits = 2 -}}
{{- end -}}
{{- $inbox := int .Values.server.inboxClaimers -}}
{{- $captureConnections := mul 3 $captures -}}
{{- $loadConnections := mul 2 $loads -}}
{{- $sync := mul 4 2 -}}
{{- $http := 4 -}}
{{- $boot := 1 -}}
{{- $required := add $inbox $captureConnections $loadConnections $permits $boot $sync $http -}}
{{- $holders := dict "inbox" $inbox "captures" $captureConnections "loads" $loadConnections "permits" $permits
  "boot" $boot "sync" $sync "http" $http "required" $required -}}
{{- $servers := int (include "hephaestus.serverRolloutInstances" .) -}}
{{- $pool := int $holders.required -}}
{{- if ne (toString .Values.server.databasePool.maximumPoolSize) "auto" -}}
{{- $pool = int .Values.server.databasePool.maximumPoolSize -}}
{{- if lt $pool (int $holders.required) -}}
{{- fail (printf "server.databasePool.maximumPoolSize=%d needs at least %d: inbox=%d, capture=%d, long loads=%d, permit maintenance=%d, boot=%d, sync reserve=%d, HTTP/control reserve=%d"
  $pool (int $holders.required) (int $holders.inbox) (int $holders.captures) (int $holders.loads)
  (int $holders.permits) (int $holders.boot) (int $holders.sync) (int $holders.http)) -}}
{{- end -}}
{{- end -}}
{{- $pooled := mul $servers $pool -}}
{{- /* Run wakeups, conversation ledger, evidence erasure, model pools and sync hints each own one LISTEN connection. */ -}}
{{- $listeners := mul 5 $servers -}}
{{- $migration := int .Values.migration.databasePool.maximumPoolSize -}}
{{- /* database-monitor.sql bounds the monitor role to two sessions across old and new exporters. */ -}}
{{- $exporter := 2 -}}
{{- $backup := 2 -}}
{{- /* Two overlapping PostgreSQL probes and one monitor/role setup session. Init waits precede their pool. */ -}}
{{- $maintenance := 3 -}}
{{- $reserved := 3 -}}
{{- $margin := 4 -}}
{{- $ordinary := add $pooled $listeners $migration $exporter $backup $maintenance -}}
{{- dict "servers" $servers "serverPool" $pool "serverPools" $pooled "listeners" $listeners
  "migration" $migration "exporter" $exporter "backup" $backup "maintenance" $maintenance
  "superuserReserved" $reserved "operatorMargin" $margin "ordinary" $ordinary
  "required" (add $ordinary $reserved $margin)
  "maximum" (max 120 (mul 20 (divf (add $ordinary $reserved $margin) 20 | ceil | int))) | toJson -}}
{{- end -}}

{{/* An explicit max_connections must preserve reserved slots and the operator margin after all clients. */}}
{{- define "hephaestus.databaseConnectionBudget" -}}
{{- if hasKey .Values.postgresql.parameters "max_connections" -}}
{{- $capacity := include "hephaestus.databaseConnectionCapacity" . | fromJson -}}
{{- if gt (int $capacity.required) (int .Values.postgresql.parameters.max_connections) -}}
{{- fail (printf "PostgreSQL max_connections needs at least %d: %d servers x pool %d=%d, LISTEN=%d, migration=%d, exporter=%d, backup=%d, probes/setup=%d, superuser reserved=%d, operator margin=%d"
  (int $capacity.required) (int $capacity.servers) (int $capacity.serverPool) (int $capacity.serverPools)
  (int $capacity.listeners) (int $capacity.migration) (int $capacity.exporter) (int $capacity.backup)
  (int $capacity.maintenance) (int $capacity.superuserReserved) (int $capacity.operatorMargin)) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* The ConfigMap of files/database-*: the migration Job and the monitor Job run them from this directory. */}}
{{- define "hephaestus.databaseSetupDirectory" -}}/etc/hephaestus/database{{- end -}}

{{- define "hephaestus.databaseSetupMount" -}}
- name: database-setup
  mountPath: {{ include "hephaestus.databaseSetupDirectory" . }}
  readOnly: true
{{- end -}}

{{- define "hephaestus.databaseSetupVolume" -}}
- name: database-setup
  configMap:
    name: {{ include "hephaestus.name" (dict "root" . "component" "database-setup") }}
{{- end -}}

{{/*
The server's sandbox settings, with sandbox.enabled: the sandbox namespace, the gateway, the review capacity and the
drain. docs/admin/kubernetes.mdx § Sandbox prerequisites.
*/}}
{{- define "hephaestus.env.sandbox" -}}
{{- $sandbox := .Values.sandbox -}}
{{- $runtime := $sandbox.runtime -}}
{{- $port := include "hephaestus.gatewayPort" . -}}
- name: SANDBOX_API_PORT
  value: {{ $port | quote }}
- name: SANDBOX_API_MAX_CONNECTIONS
  value: {{ $sandbox.gateway.maxConnections | quote }}
{{- $capacity := int (include "hephaestus.conversationTurns" .) }}
{{- if .Values.sandbox.enabled }}
# The installation's conversation turns. Each server's claim checks this cap across all servers.
- name: HEPHAESTUS_MENTOR_TURN_CAPACITY
  value: {{ $capacity | quote }}
{{- end }}
# Also the review capacity: a server claims no more reviews than it may run sandboxes.
- name: SANDBOX_MAX_CONCURRENT
  value: {{ include "hephaestus.serverReviews" . | quote }}
# The buildpack sizes the heap from this count: its default of 250 for the server, and 8 more per review.
- name: BPL_JVM_THREAD_COUNT
  value: {{ add 250 (mul 8 (int (include "hephaestus.serverReviews" .))) | quote }}
- name: HEPHAESTUS_AGENT_DRAIN_TIMEOUT
  value: {{ printf "%ds" (int .Values.server.drainTimeoutSeconds) | quote }}
- name: HEPHAESTUS_SANDBOX_KUBERNETES_NAMESPACE
  value: {{ include "hephaestus.sandboxNamespace" (dict "root" .) | quote }}
# A sandbox claim names its kind's warm pool (sandbox-templates.yaml) and the gateway Service, whose address the
# server resolves for the sandbox: a sandbox has no DNS.
{{- range $kind := list "review" "conversation" }}
- name: {{ printf "HEPHAESTUS_AGENT_SANDBOX_WARM_POOLS_%s" (upper $kind) }}
  value: {{ include "hephaestus.name" (dict "root" $ "component" (printf "sandbox-%s" $kind)) | quote }}
{{- end }}
- name: HEPHAESTUS_AGENT_SANDBOX_GATEWAY_URL
  value: {{ printf "http://%s.%s.svc:%v" (include "hephaestus.name" (dict "root" . "component" "sandbox-gateway")) .Release.Namespace $port | quote }}
{{- end -}}
