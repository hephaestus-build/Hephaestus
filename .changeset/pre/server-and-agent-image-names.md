---
"hephaestus": minor
---

The server image is now `ghcr.io/hephaestus-build/server`, and the review sandbox image is now `ghcr.io/hephaestus-build/agent`. The chart values `images.application-server` and `images.agent-pi` are now `images.server` and `images.agent`. Earlier releases keep their published images under the old names, so a rollback to them still works. The old packages get no new tags: `application-server:latest` and `agent-pi:latest` stay on 0.95.1, the last stable release under the old names.

**Operators:** Before you upgrade, rename `images.application-server` and `images.agent-pi` in your own values. Also update every registry mirror, pull-through cache or allowlist that names `application-server` or `agent-pi`. A server outside the chart reads its own image from `HEPHAESTUS_IMAGE_SERVER`, not `HEPHAESTUS_IMAGE_APPLICATION_SERVER`.
