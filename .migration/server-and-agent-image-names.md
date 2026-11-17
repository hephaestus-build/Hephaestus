#### 🔴 Pull the server and agent images under their new names

- **When:** before the window
- **Action:** Where your values, registry mirror or allowlist name `application-server` or `agent-pi`, use `server` and `agent`.

The release publishes the server image as `ghcr.io/hephaestus-build/server` and the review sandbox image as `ghcr.io/hephaestus-build/agent`.
The signed lock, the chart's default values and the release evidence use the new names.
Earlier releases keep their images under the old names, so a rollback to one of them still pulls.
The old packages get no new tags. A reference to `application-server:latest` or `agent-pi:latest` stays on 0.95.1, the last stable release under the old names.

A deployment that takes the image digests from the signed chart needs no change.
The chart refuses the old keys, so rename an image override in your own values before the upgrade.
A host with reconciler tooling from 1.0.0-rc.5 or later needs no new tooling: it reads the image names from the signed lock or the target chart.

| Where | Before | After |
| --- | --- | --- |
| Chart value | `images.application-server` | `images.server` |
| Chart value | `images.agent-pi` | `images.agent` |
| Image | `ghcr.io/hephaestus-build/application-server` | `ghcr.io/hephaestus-build/server` |
| Image | `ghcr.io/hephaestus-build/agent-pi` | `ghcr.io/hephaestus-build/agent` |
| Server environment outside the chart | `HEPHAESTUS_IMAGE_APPLICATION_SERVER` | `HEPHAESTUS_IMAGE_SERVER` |
| Release evidence files | `application-server-linux-<arch>.*` | `server-linux-<arch>.*` |
