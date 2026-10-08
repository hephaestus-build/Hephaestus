---
"hephaestus": patch
---

Deployments no longer walk every file in the repository caches after a successful ownership migration. New or unmigrated caches still receive the required ownership before application services start.
