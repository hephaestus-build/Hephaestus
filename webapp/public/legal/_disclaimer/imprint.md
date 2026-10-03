# Imprint not configured

This Hephaestus instance has been deployed without a legal profile.

Where § 5 DDG (Digitale-Dienste-Gesetz) applies, the operator must provide imprint information that identifies the responsible party. This page cannot satisfy that obligation; the operator must check all disclosure duties that apply to the service.

## What this means for you

- This page does not identify the operator. Do not infer TUM responsibility from the upstream project.
- No imprint has been configured for the deployment you are looking at.
- Please request imprint information directly from the party that deployed this instance.

## What the operator must do

See [Legal pages operator guide](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/admin/legal-pages.mdx) for configuration options:

1. Configure `LEGAL_PROFILE` only for a bundled profile that identifies your actual operator, **or**
2. Mount a custom Markdown override directory at the documented path, **or**
3. Author deployment-specific imprint content and rebuild the image.

Fill `[operator/publisher name, legal form, address, representative and direct contact]`, plus
`[register, supervisory authority, VAT identification and responsible content contact where applicable]`.
Check the obligations that apply to your organisation and service. Selecting the TUM profile does
not make TUM responsible for a self-hosted instance. Keep legal contact details separate from public
bug reports; do not ask people to put personal rights requests in GitHub issues.

---

*This page is the default fallback shipped with the Hephaestus source code. It is intentionally not a valid imprint for any deployment.*
