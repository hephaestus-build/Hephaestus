#### 🔴 Remove the retired sync broker values before upgrading

Sync-status hints now use PostgreSQL notifications. The release runs no NATS broker and opens no broker connection.

Remove `nats` from every values file, including all profile and environment overrides. This removes these retired keys:

- Remove `nats`.
- Remove `nats.bundled`.
- Remove `nats.maxPayloadBytes`.
- Remove `nats.jetstream`.
- Remove `nats.jetstream.maxMemory`.
- Remove `nats.jetstream.maxFileBytes`.
- Remove `nats.persistence`.
- Remove `nats.persistence.storageClassName`.
- Remove `nats.resources`.
- Remove `nats.external`.
- Remove `nats.external.url`.
- Remove `nats.external.cidr`.
- Remove `nats.external.port`.
- Remove `nats.resources.requests.cpu`.
- Remove `nats.resources.requests.memory`.
- Remove `nats.resources.requests.ephemeral-storage`.
- Remove `nats.resources.limits.cpu`.
- Remove `nats.resources.limits.memory`.
- Remove `nats.resources.limits.ephemeral-storage`.
- Remove `nats.resources.requests`.
- Remove `nats.resources.limits`.

Remove `images.nats` from every image values file. The chart refuses these retired keys; it does not ignore them.
Regenerate image values from the new release lock instead of reusing the previous image map.

Remove every `hephaestus.sync.nats.*` property and the `NATS_SERVER`, `NATS_USERNAME`, `NATS_PASSWORD`, `NATS_DURABLE_CONSUMER_NAME`, `NATS_ENABLED` and `NATS_PORT` environment settings.
Stop passing `--external-nats` to the Secret helper and remove its unused broker keys from your release Secret.
After the server upgrade, confirm sync SSE updates and the REST polling fallback. Then stop any external broker used only by this release.
A Helm upgrade removes the bundled workload. Its old PersistentVolumeClaim can remain; identify that release's exact claim and remove it only after confirming that no other application uses its data.

The chart budgets five dedicated PostgreSQL listeners per server, including old servers during rolling overlap.
Use the chart's derived pool and `max_connections`; keep the existing profile budgets and sandbox template requests.
