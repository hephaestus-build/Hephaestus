---
"hephaestus": patch
---

GitLab merge requests are reviewed when a separate worker runs practice reviews. If you run the
worker outside the reference Compose files, give it the application server's `GITLAB_ENABLED` and
`GITLAB_DEFAULT_SERVER_URL`.
