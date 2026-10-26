---
"hephaestus": minor
---

Set the new optional `SECURITY_CONTACT` (chart value `webapp.securityContact`) to publish `/.well-known/security.txt` with your own security contact and your own host as its canonical address. Without it, the webapp serves no `security.txt`; it no longer ships one that names the project's former host and a personal address. The file renews its expiry date at each start.
