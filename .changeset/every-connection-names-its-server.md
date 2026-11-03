---
"hephaestus": patch
---

Every GitHub and GitLab connection now stores its server URL. Before, a GitLab connection without one meant your configured GitLab for API calls but gitlab.com for people's source data. The upgrade stores the configured URL on such connections. Before you upgrade, check that `config.gitlab.oauthBaseUrl` names the GitLab that those connections use.
