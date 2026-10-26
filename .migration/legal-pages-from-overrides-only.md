#### 🔴 Legal pages come only from your mounted files

The webapp image no longer bundles a legal profile, and the webapp no longer reads `LEGAL_PROFILE`.
`/imprint` and `/privacy` show the Markdown files that you mount at `/usr/share/nginx/html/legal-overrides/`.
Without them, the webapp shows the built-in "not configured" placeholder with a red banner.

If you set `LEGAL_PROFILE`, do this before you upgrade:

1. Write `imprint.md` and `privacy.md` for your deployment.
2. Mount them read-only at `/usr/share/nginx/html/legal-overrides/` in the webapp container, as [Legal pages](https://docs.hephaestus.build/admin/legal-pages#deployment-specific-overrides) describes.
   On the Helm chart, put them in a ConfigMap and name it in `webapp.legalOverrides`.
3. Remove `LEGAL_PROFILE` from your environment.
4. After the upgrade, open `/imprint` and `/privacy` and confirm that they show your pages.
