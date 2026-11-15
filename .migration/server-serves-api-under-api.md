#### 🔴 Send the API path to the server unchanged

The server now serves its API under `/api` itself. The chart's route sends `/api` to the server unchanged, so a chart install needs no action, and your Gateway controller no longer needs the `URLRewrite` filter. Every public address stays the same, including the sign-in callbacks at your login providers. This holds when `hephaestus.auth.api-base-path` was `/api`, as in the chart. With any other value, the sign-in, GitHub App and Slack callbacks and the unsubscribe links move to `/api` below your server address: register the new callbacks at each provider in the upgrade window.

Outside the chart, change your proxy in the upgrade window: send `/api` and every path below it to the server without removing `/api`. A previous server cannot answer these paths, and the new server answers no API request at the root.

Remove `hephaestus.auth.api-base-path` and `HEPHAESTUS_AUTH_API_BASE_PATH` from your server settings. The server no longer reads them.

In the `uri` tag of `http_server_requests_*` metrics, API routes now start with `/api`. Update your own queries, alerts and dashboards. The shipped alerts and dashboards already use the new values.
