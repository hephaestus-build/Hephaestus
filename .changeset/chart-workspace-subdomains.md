---
"hephaestus": minor
---

The Helm chart can now serve workspace subdomains with `workspaceSubdomains.enabled`. The base domain is the installation's `host`. Sign-in and the API stay on the apex. Workspace links redirect to the workspace host, with their path and query intact.

Prepare wildcard DNS and a DNS-01 certificate before you enable the setting. On one host, `k3s-install.sh --workspace-subdomains --dns01-issuer <issuer>` selects your DNS-01 issuer. Set the chart value back to `false` to return to workspace paths on the apex.
