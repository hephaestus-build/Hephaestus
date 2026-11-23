---
"hephaestus": patch
---

The web app's page now runs only the scripts that carry the nonce of that page load, under a strict content security policy. If a proxy or CDN sits in front of Hephaestus, make sure it does not cache the web app's page or add scripts to it: a script that it adds no longer runs.
