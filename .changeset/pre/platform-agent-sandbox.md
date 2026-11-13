---
"hephaestus": patch
---

The one-host k3s platform now installs the agent-sandbox controller, pinned to v1.0.10, beside Traefik and cert-manager. The installer waits until the controller is ready. A later installer run upgrades it when the pin changes.
