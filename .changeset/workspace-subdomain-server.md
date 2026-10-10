---
"hephaestus": minor
---

Workspace subdomains can use central sign-in and the apex API. The optional `HEPHAESTUS_WORKSPACE_SUBDOMAINS_ENABLED` switch is off by default. Set `HEPHAESTUS_WORKSPACE_SUBDOMAINS_BASE_DOMAIN` before you turn it on. Edge and SPA support must be deployed first.

Workspace names keep their 3–51 character bounds and now follow DNS label rules. Used names remain reserved after a rename or deletion. Old lowercase workspace paths keep their redirects. Tenant-host apps must fetch a CSRF token before other credentialed requests at startup and after sign-in or sign-out.

If a login provider's registered callback differs from the configured issuer origin and API prefix, update it to the exact callback shown in instance administration. Existing matching apex callbacks need no change. Before enabling workspace subdomains, turn off GitHub callback wildcard matching. Invalid or reserved workspace names change automatically to a safe address during upgrade. See the [workspace subdomains guide](https://docs.hephaestus.build/admin/workspace-subdomains).
