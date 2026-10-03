# Privacy statement not configured

This Hephaestus instance has been deployed without a legal profile.

Hephaestus does process personal data by design (sign-in identities, mirrored project work, conversations, observations, feedback and security events; error telemetry is optional). The operator of this deployment is the **controller** within the meaning of Art. 4(7) GDPR for that processing and is legally required to provide a transparent privacy statement under Art. 13 and Art. 14 GDPR before you use the service.

## What this means for you

- This page does not identify the operator. Do not infer TUM responsibility from the upstream project.
- No privacy statement has been configured for the deployment you are looking at.
- You should not assume that this deployment's processing follows the TUM/AET configuration described in the upstream documentation.
- Please request a privacy statement directly from the party that deployed this instance before continuing to use it.

## What the operator must do

See [Legal pages operator guide](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/admin/legal-pages.mdx) for configuration options:

1. Configure `LEGAL_PROFILE` only for a bundled profile that actually describes your controller and deployment, **or**
2. Mount a custom Markdown override directory at the documented path, **or**
3. Author deployment-specific privacy content and rebuild the image.

Before collecting data, publish a deployment-specific notice that states:

- `[controller identity, address, privacy contact and DPO where applicable]`;
- `[purposes and legal bases for each group]`, including people whose work is collected without sign-in;
- connected GitHub, GitLab, Slack and Outline sources, private conversations, and the wider permitted
  review context, including Slack threads and person history;
- `[recipients, providers, regions, contracts, international-transfer safeguards and training terms]`;
- `[retention periods or deletion/review triggers]` for active records, worker copies, broker events,
  logs, monitoring and backups, including categories with no automatic expiry;
- AI choices, Slack message-use controls, research withdrawal and feedback disputes, with their limits;
- the narrower account export/deletion and wider operator-assisted person export/erasure, including
  people without accounts, identity verification, residual copies and the rights contact; and
- rights, response times, applicable portability conditions and the supervisory complaint route.

The operator must complete the [data-protection package](https://docs.hephaestus.build/admin/dsms/)
and assess the full DPIA indicated by the current source combination. A source-use engineering
approval is not controller/DPO approval. Terms acceptance is not research consent or a legal basis
for all processing. Do not copy TUM legal bases or institutional agreements into another deployment.

---

*This page is the default fallback shipped with the Hephaestus source code. It is intentionally not a valid privacy statement for any deployment.*
