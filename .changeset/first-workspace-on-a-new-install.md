---
"hephaestus": patch
---

The first admin on a new install can now create a GitLab or token-based GitHub workspace. Until now the request failed with "Connect exactly one GitLab account" or "Connect your GitHub account", because Hephaestus looked for a synced provider account, and nothing syncs before the first workspace exists.
