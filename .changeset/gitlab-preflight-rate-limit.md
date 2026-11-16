---
"hephaestus": minor
---

Checking a GitLab token and listing its groups and projects while creating a workspace now share a rate limit per account: 30 calls per 10 minutes by default. Two optional settings change it: `HEPHAESTUS_RATE_LIMIT_GITLAB_PREFLIGHT_CAPACITY` and `HEPHAESTUS_RATE_LIMIT_GITLAB_PREFLIGHT_PERIOD`. When a token check fails, the workspace wizard now shows the server's reason, for example how long to wait.
