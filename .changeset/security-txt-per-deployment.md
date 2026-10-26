---
"hephaestus": minor
---

Each deployment serves its own `/.well-known/security.txt`, with its own host as the canonical address and the project's contacts. Set the new optional `SECURITY_CONTACT` (chart value `webapp.securityContact`) to list your own security contact first. The file renews its expiry date at each start.
