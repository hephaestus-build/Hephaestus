---
"hephaestus": patch
---

Three conditions now have log events that you can query by `event.name`:

- When GitLab already refused a connection's credential, a server start logs one `integration.credential.refused` warning. Before, it logged an error with a stack trace for a project discovery that could not succeed.
- A deletion sweep that a repository's disabled issues or pull requests stopped logs one `integration.deletion_sweep.skipped` record at INFO. Before, it logged two warnings. Other reasons for a skipped sweep stay warnings.
- The sandbox quota logs `sandbox.quota.unreadable` when reviews must wait because the quota is unreadable, and `sandbox.quota.readable` with the wait time when they can start again.
