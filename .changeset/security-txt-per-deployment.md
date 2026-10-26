---
"hephaestus": minor
---

Each deployment now serves its own `/.well-known/security.txt`: its canonical address is the deployment's own host, and it no longer names the project's former host or a personal address. Set the new optional `SECURITY_CONTACT` (chart value `webapp.securityContact`) to list your own security contact first. The file renews its expiry date at each start.
