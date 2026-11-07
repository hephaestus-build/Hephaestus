---
"hephaestus": patch
---

When GitLab refuses a workspace's credential, the first full sync of that workspace now stops. Before, it continued and asked GitLab for each repository. Each refused request wrote an error with a stack trace to the server log. The scheduled sync already stopped this way.
