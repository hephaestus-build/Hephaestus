# Hephaestus — Art. 28 Processor Checklist

Records every entity that might qualify as a processor (Art. 28 GDPR) for the TUM-operated Hephaestus deployment and the status of the corresponding Auftragsverarbeitungsvertrag (AVV). Internal AET-operated components and the LRZ public-body counterpart are listed for completeness so the record is self-contained.

## Summary

The existing TUM record lists these recipients: GitHub (identity provider and source-system API), the per-workspace LLM provider (Microsoft Azure OpenAI by default on the TUM-operated deployment, or any OpenAI-API-compatible HTTPS endpoint configured per workspace), and Slack (per-workspace opt-in). The LRZ (gitlab.lrz.de) is **not** a processor; it is a separate controller under the EDPB 07/2020 framework. Outline has no fixed operator or origin, so it remains disabled until the operator records a per-instance role, hosting region, transfer basis, and AVV status. The webapp includes optional consent-controlled Sentry. The actual production endpoint and enabled state require deployment evidence; code alone cannot prove that it is off.

First-party product feedback and survey responses remain inside the instance PostgreSQL database.
Optional email alerts do not contain those responses, but the configured relay receives recipient
addresses, notification type and links. Before enabling SMTP, record the relay operator, hosting and
retention terms, and its role under the applicable institutional arrangement; external processing
requires the appropriate agreement. Do not infer the SMTP relationship from LRZ's separate role as
the gitlab.lrz.de operator.

## Detailed check

| Component | Role | AVV required? | Status |
|---|---|---|---|
| AET servers at TUM (container host) | Own infrastructure | No (Art. 4(7) GDPR — controller's own equipment) | — |
| PostgreSQL (in-house container) | Application data store | No (self-hosted) | — |
| Hephaestus app server (in-house container) | Identity and session management (native auth, ADR 0017) | No (self-hosted) | — |
| Spring Boot application server (in-house container) | Admission, context and persistence; no sandbox on a server-only role | No (self-hosted) | — |
| Connected worker and review/Heph sandboxes | Executes AI work and holds temporary context and mounted evidence | No when controller-owned; assess Art. 28 for externally operated workers | Record each worker operator, location, access and deletion acknowledgement; server-only operation has no Docker socket |
| Traefik v3 reverse proxy (in-house container) | TLS termination, routing | No (self-hosted) | — |
| Let's Encrypt ACME endpoint | Domain-validation certificates | No end-user project content is sent; record ACME account/contact data separately | — |
| **GitHub, Inc.** (USA) | Identity provider (OAuth) and source-system API for connected GitHub repositories | **Yes** | DPA in place at TUM/AET level; GitHub holds its own EU-US Data Privacy Framework certification (independent of Microsoft Corporation's, per Microsoft's published covered-entities list); SCCs Module 2 contracted as fall-back; re-verify DPF status annually |
| **Microsoft Corporation (Azure OpenAI Service)** (USA / EU) | Default LLM provider for the TUM-operated deployment; verify the actual deployment type and processing geography; an EU resource location alone is insufficient | **Yes** | DPA at TUM/AET level for the TUM-operated tenancy; at the workspace administrator's institution level when that institution supplies credentials (joint-controller model, privacy §10); enterprise API no-training terms; DPF-certified; SCCs Module 2 as fall-back |
| **OpenAI OpCo, LLC** (USA), with **OpenAI Ireland Ltd.** (Ireland) as the EEA contracting party — or any OpenAI-API-compatible endpoint chosen by a workspace administrator | Workspace-configured LLM provider | **Yes, when engaged** | DPA at TUM/AET level for AET-pool processors; at the administrator's institution level for non-pool endpoints; DPF / SCC framing applies recipient-by-recipient and DPF status is verified per recipient before engagement |
| **Salesforce, Inc. / Slack Technologies, LLC** (USA) | Slack app delivery, identity linking, App Home privacy controls, DM mentor messages, and monitored-channel event delivery when Slack is enabled by the workspace administrator | **Yes, when engaged** | DPA in place at TUM/AET level; Salesforce DPF-certified (Slack participates under the Salesforce certification); SCCs Module 2 as fall-back |
| **Connected Outline instance operator** | Selected-document source and optional OAuth identity linking | **Depends on the operator's role** | No generic approval. Before activation, classify the exact origin as controller-owned infrastructure, an Art. 28 processor, or a separate controller; record the operator, region, transfer basis, retention terms, and AVV where required. Workspace selection cannot supply this approval. |
| **Leibniz-Rechenzentrum (LRZ) der BAdW (gitlab.lrz.de)** | Source system and OIDC identity provider | **Not Art. 28** | Separate controller; inter-public-body transmission under Art. 5(1) Nr. 1 BayDSG; LRZ is an institute of the Bayerische Akademie der Wissenschaften and applies its own TOMs on its own infrastructure |
| GitHub / GHCR (CI, image hosting) | Stores Docker images and CI logs; does not receive end-user personal data of the Hephaestus service | No (controller-to-controller on AET-staff data; end-user Hephaestus data is not transferred) | Covered by TUM's general agreements with GitHub Enterprise |

## Evidence required before engagement

The statuses above carry forward the existing TUM record. They are not new contract verification.
The repository cannot prove that a DPA is executed, that a certification is current or that production
uses the stated endpoint. Before activation or renewal, the controller must fill this recipient record:

| Field | Required deployment evidence |
|---|---|
| Recipient and role | `[legal entity, service, endpoint, controller/processor/joint-controller analysis]`; distinguish source-platform purposes from processing on instructions. |
| Contract | `[signed DPA/AVV reference, instructions, subprocessors, renewal/review owner]`; an API key is not an agreement. |
| Scope | `[data categories, subjects, purposes, review/Heph inputs and any human provider access]`; include broader Slack-thread and person-history context. |
| Retention and training | `[prompt/response and abuse-monitoring retention, deletion path, training terms, any exception]`; no-training is not zero retention. |
| Location and transfer | `[storage, inference, support access, subprocessors, transfer mechanism and evidence date]`; verify deployment type, not just a resource's region. |
| Rights and exit | `[export/erasure assistance, incident notice, credential revocation, deletion confirmation]`; state what a completed request cannot retract. |

Art. 28 requires a processor agreement only where the recipient actually processes on the controller's
instructions. Software selection or an admin role cannot establish that relationship. External
workspace institutions may be joint controllers under an actual arrangement; individual authorised
admins within one controller are not separate controllers merely because they choose settings.
Self-hosters must assess their own roles rather than copy the TUM arrangement or LRZ classification.

For transfers, verify an active adequacy decision and certification for the exact recipient and
covered service, or the appropriate SCC module and transfer assessment with supplementary measures
where required. Module 2 is controller-to-processor; it is not automatically the right module for
separate controllers or a processor chain. EU hosting and DPF participation do not by themselves
prove every support or subprocessor flow stays in the EEA. Keep evidence and a review date.

### Optional Sentry and monitoring

Record `[Sentry operator, endpoint, hosting, retention, contract and transfer safeguard]` before
activation. Controller-owned self-hosting and SaaS have different roles. The browser requires a DSN
and error-monitoring consent, strips request, user and breadcrumb fields, and disables message/body
collection. Error text and stacks can still carry personal data; inspect synthetic reports and apply
server-side scrubbing. Withdrawal stops new client reports, not previously received reports.
The server also includes Sentry, initialized by a nonblank DSN outside the specs profile. Its consent is not controlled by the browser: it disables default PII and strips request, user and breadcrumb fields, while retaining operational trace correlation. Record a separate lawful basis and recipient scope for server diagnostics. Browser consent withdrawal does not turn off server monitoring. Sentry-side rights and retention need their own procedure. Optional trace export also needs an
approved collector and retention; it can contain workspace and run identifiers. Private metrics
must not be exposed through the public application route.

## Why the LRZ relationship is not Art. 28

An Art. 28 processor is engaged to process personal data on behalf of the controller, under the controller's documented instructions. EDPB Guidelines 07/2020 §§ 14–33 set out that the decisive criterion is who determines the essential means of the processing: the purposes, which data, which subjects, how long, what access.

LRZ is an institute of the Bayerische Akademie der Wissenschaften and operates gitlab.lrz.de for the Bavarian academic-computing community as the Bavarian academic computing centre. LRZ determines the purpose, onboarding, retention windows, backup regime, TOMs, and terms of use. TUM does not instruct LRZ on how to operate gitlab.lrz.de; TUM consumes the service as one eligible Bavarian public body among many, with the inter-public-body transmission anchored in Art. 5(1) Nr. 1 BayDSG. Two separate controllers, each processing on its own infrastructure for its own purpose, are incompatible with Art. 28 status under EDPB 07/2020.

Art. 26 GDPR (joint controllership) is equally absent: EDPB 07/2020 §§ 50–65 require a joint determination of purposes and means, which is not present. TUM and LRZ each pursue their own distinct purpose. The relationship is a public-body cooperation, consistent with BayLfD published guidance.

## Why the workspace administrator is not an Art. 28 processor

Workspace administrators are **joint controllers** with TUM/AET under Art. 26 GDPR for the workspace-configurable decisions enumerated in §10 of the privacy statement (which Git repositories are connected, the practice catalog, the LLM provider and credentials, whether practice reviews are auto-triggered on new pull/merge requests, Slack routing, and whether Outline and selected collections are enabled). The Art. 26(2) Satz 1 allocation of duties and the Art. 26(2) Satz 2 essence of the arrangement are made available to data subjects via the privacy statement; TUM/AET is the single point of contact for data-subject rights, with the workspace administrator additionally addressable for workspace-specific questions.

## Follow-up if the processing surface changes

Amend this file, the Art. 30 record, and the privacy statement before deploying any of the following:

- A new LLM provider added to the AET-pool (e.g., Anthropic).
- A new identity provider beyond GitHub and gitlab.lrz.de.
- Enabling Outline for a new origin or changing its operator, hosting region, or contractual role.
- Activating SMTP email delivery (the chosen SMTP host becomes a recipient of personal data; a TUM-internal relay falls under the TUM-internal framework, an external relay needs an Art. 28 DPA).
- Activating the bundled Sentry client. A self-hosted Sentry on TUM infrastructure is an in-house recipient; a SaaS Sentry tenant is an Art. 28 U.S. processor that needs a DPA, a privacy-statement entry, and a DPIA re-assessment.
- Any external storage (S3, CDN) or any third-party font, script, image, or embed served from the application: requires a recipient-role assessment, any applicable agreement and a privacy-statement entry.
- Heph internet access or any widening of the practice-review sandbox network posture beyond the governed worker gateway — triggers a re-audit under §5 of `dpia-prescreen.md`.
- Sending a new artifact-source privacy class to an existing processor, changing its region or provider retention,
  or using product evidence for operator/research evaluation. An existing DPA does not by itself authorize a new
  purpose or data category; complete the [artifact-source governance gate](./artifact-source-governance.md).

### Direct browser image requests

Avatar components pass provider image URLs to browser image elements; these are not proxied by
Hephaestus. The image host receives visitor network metadata and the requested URL, including from
people browsing public pages without signing in. Record `[hosts, roles, basis, transfer safeguard and
retention]`; an image-host request is not covered by the person's Sentry choice. Third-party hosting
does not automatically imply Art. 28 status, so assess its actual role.

## Primary references

- [EDPB Guidelines 07/2020, controller and processor](https://www.edpb.europa.eu/documents/guideline/guidelines-072020-on-the-concepts-of-controller-and-processor-in-the-gdpr_en): roles depend on actual purposes and means.
- [European Commission transfer safeguards and SCCs](https://commission.europa.eu/law/law-topic/data-protection/international-dimension-data-protection/standard-contractual-clauses-scc_en): choose the module for the actual roles.
- [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data): distinguish training, abuse monitoring and endpoint-specific application state; do not infer these terms for a compatible third-party API.
- [Microsoft Azure OpenAI data privacy](https://learn.microsoft.com/en-us/legal/cognitive-services/openai/data-privacy): verify deployment geography and any modified abuse-monitoring approval.
- [Sentry scrubbing](https://docs.sentry.io/security-legal-pii/scrubbing/): client minimisation needs recipient-side review too.
- [GitLab account deletion](https://docs.gitlab.com/user/profile/account/delete_account/): upstream deletion is separate from Hephaestus copies and attribution.

Recheck these terms against the contract and exact service version when a provider changes. Published
vendor documentation does not prove that the deployment has purchased or enabled a particular control.
