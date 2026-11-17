#### 🔴 Move the web app's values to the server

- **When:** before the window
- **Action:** In your own values, move `webapp.securityContact` and `webapp.legalOverrides` under `server`, and remove the other `webapp` and `images.webapp` keys.

The server serves the web app ([ADR 0100](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0100-the-server-serves-the-web-app.md)).
The release has no `webapp` image, no `webapp` Deployment and no `webapp` values.
The chart refuses a values file that still names one of them, so change your values before the upgrade.

The route sends every path to the server, and the server sends the security headers, HSTS included.
The public addresses stay the same: the sign-in callbacks, `/api/**`, `/.well-known/hephaestus.json`, `/.well-known/security.txt` and the legal pages.

The route moves every path to the server when the upgrade starts, but a server of the previous release does not serve the web app.
Until the rollout replaces the last old server, some page loads fail; the API is not affected.

| Where | Before | After |
| --- | --- | --- |
| Chart value | `webapp.securityContact` | `server.securityContact` |
| Chart value | `webapp.legalOverrides` | `server.legalOverrides` |
| Chart value | `webapp.replicas` | Removed: the server replicas serve the web app |
| Chart value | `webapp.resources` | Removed: size `server.resources` |
| Chart value | `images.webapp` | Removed |
| Image | `ghcr.io/hephaestus-build/webapp` | Removed: the server image serves the web app |

The web app's old configuration script, `env-config.<hash>.js`, is gone. A monitor that read the running version from it can read `version` from `/.well-known/hephaestus.json`.
