---
"hephaestus": minor
---

The server now serves its API under `/api` itself, so the chart's route no longer rewrites a path. Your Gateway controller no longer needs the `URLRewrite` filter. Every public address stays the same, including the sign-in callbacks registered at your login providers. If you set `hephaestus.auth.api-base-path` to anything other than `/api`, the sign-in, GitHub App and Slack callbacks and the unsubscribe links move to `/api` below your server address: register the new callbacks at each provider. The setting `hephaestus.auth.api-base-path` is removed, because the API path is fixed.

**Operators:** With the chart, do nothing. With your own proxy, stop removing `/api` before requests reach the server, in the upgrade window. Remove `hephaestus.auth.api-base-path` and `HEPHAESTUS_AUTH_API_BASE_PATH` from your server settings. In the `uri` tag of `http_server_requests_*` metrics, API routes now start with `/api`: update your own queries and dashboards. The shipped alerts and dashboards already use it.
