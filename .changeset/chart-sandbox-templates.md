---
"hephaestus": minor
---

With the worker enabled, the chart installs a sandbox template for each kind of agent sandbox: review and conversation. It also keeps the agent image on each sandbox node, so that a new sandbox does not wait for the image to download.

**Operators:** on a cluster other than the one-host k3s platform, apply the release's `k3s-agent-sandbox.yaml` before you upgrade the chart. The one-host installer does this for you.
