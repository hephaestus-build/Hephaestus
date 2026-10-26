---
"hephaestus": minor
---

Set the new optional `SECURITY_CONTACT` (chart value `webapp.securityContact`) to publish `/.well-known/security.txt` with your own security contact. When `APPLICATION_CLIENT_URL` is a single `https://` origin and workspace addresses are off, the file also names that address as its canonical location. Without `SECURITY_CONTACT`, the webapp serves no `security.txt`; it no longer ships one that names the project's former host and a personal address. The file renews its expiry date at each start.
