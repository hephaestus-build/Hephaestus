---
"hephaestus": minor
---

The webapp image no longer bundles a legal profile, so `/imprint` and `/privacy` show your own pages from `/legal-overrides/`, or the built-in "not configured" placeholder. On the Helm chart, the new value `webapp.legalOverrides` mounts your pages from a ConfigMap.

**Operators:** `LEGAL_PROFILE` is no longer read. If you set it, mount your imprint and privacy Markdown files at `/usr/share/nginx/html/legal-overrides/` before you upgrade, as the legal pages guide describes.
