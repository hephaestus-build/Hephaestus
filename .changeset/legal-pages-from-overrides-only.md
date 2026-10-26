---
"hephaestus": minor
---

The webapp image no longer bundles a legal profile, so `/imprint` and `/privacy` show your own pages from `/legal-overrides/`, or the built-in "not configured" placeholder. On the Helm chart, the new value `webapp.legalOverrides` mounts your pages from a ConfigMap.

**Operators:** `LEGAL_PROFILE` is no longer read, and the chart refuses `webapp.legalProfile`. Before you upgrade, remove `webapp.legalProfile` from your Helm values. If you used a legal profile, put your imprint and privacy Markdown files in a ConfigMap and name it in `webapp.legalOverrides`, as the legal pages guide describes.
