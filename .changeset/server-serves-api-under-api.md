---
"hephaestus": minor
---

The server now serves its API under `/api` itself, so the chart's route no longer rewrites a path. Your Gateway controller no longer needs the `URLRewrite` filter. Every public address stays the same, including the sign-in callbacks registered at your login providers. The setting `hephaestus.auth.api-base-path` is gone, because the API path is fixed.
