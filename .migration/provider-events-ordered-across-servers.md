#### 🔴 External NATS broker: NATS Server 2.11 or later

A webhook replay pauses the workspace's broker consumer, and consumer pause needs NATS Server 2.11 or later.
Before you upgrade, check the version of the broker in `nats.external.url`: `nats server info`, or the `version` field of the broker's `/varz` monitoring endpoint.
Upgrade the broker first if it runs an earlier version. An earlier broker refuses every replay with HTTP 503.

The bundled NATS broker (`nats.bundled=true`) already runs a supported version.
