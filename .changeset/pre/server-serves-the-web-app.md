---
"hephaestus": minor
---

The server serves the web app itself, so a release runs one image fewer: the `webapp` image and its Deployment are gone. Every page and file carries the server's one header policy, and the web app reads its settings from the page, without a separate configuration script. Assets are precompressed and cached for a year, and a workspace host still serves only the web app.

**Operators:** Before you upgrade, move `webapp.securityContact` to `server.securityContact` and `webapp.legalOverrides` to `server.legalOverrides` in your own values, and remove `webapp.replicas`, `webapp.resources` and `images.webapp`. The chart refuses the old keys. Your Gateway sends every path to the server; it needs no route change from you.
