#### 🔴 Legal pages come only from your mounted files

The webapp image no longer bundles a legal profile, and the webapp no longer reads `LEGAL_PROFILE`.
`/imprint` and `/privacy` show the Markdown files that you mount at `/usr/share/nginx/html/legal-overrides/`.
Without them, the webapp shows the built-in "not configured" placeholder with a red banner.

The chart refuses `webapp.legalProfile`. Before you upgrade, remove `webapp.legalProfile` from your Helm values, even if it is empty.

If you used a legal profile, also do this before you upgrade:

1. Write `imprint.md` and `privacy.md` for your deployment.
2. On the Helm chart, put them in a ConfigMap in the release namespace and name it in `webapp.legalOverrides`, as [Legal pages](https://docs.hephaestus.build/admin/legal-pages#deployment-specific-overrides) describes.
   Outside the chart, mount them read-only at `/usr/share/nginx/html/legal-overrides/` in the webapp container, and remove `LEGAL_PROFILE` from its environment.
3. After the upgrade, open `/imprint` and `/privacy` and confirm that they show your pages.
