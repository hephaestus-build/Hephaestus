# Hephaestus — Art. 28 Processor Checklist

This record lists every entity that might qualify as a processor (Art. 28 GDPR) for the TUM-operated Hephaestus deployment. It records each corresponding Auftragsverarbeitungsvertrag (AVV). Internal AET-operated components and the LRZ public-body counterpart are listed for completeness so the record is self-contained.

## Summary

The existing TUM record lists these recipients:

- GitHub: identity provider and source-system API.
- The per-workspace model service: in-house models, for example served through Logos, or external providers that a workspace configures.
  Each recipient needs its own evidence (see below).
- Slack: per-workspace opt-in.
- AI model providers for research benchmark runs: pseudonymized data only, after recipient-specific verification.
 The LRZ (gitlab.lrz.de) is **not** a processor. It is a separate controller under the EDPB 07/2020 framework. Outline has no fixed operator or origin. It remains disabled until the operator records a per-instance role, hosting region, transfer basis, and AVV status. The webapp includes optional consent-controlled Sentry. The actual production endpoint and enabled state require deployment evidence. Code alone cannot prove that it is off.

First-party product feedback and survey responses remain inside the instance PostgreSQL database.
Optional email alerts do not contain those responses, but the configured relay receives recipient
addresses, notification type and links. Before enabling SMTP, record the relay operator, hosting and retention terms, and its role under the applicable institutional arrangement. External processing requires the appropriate agreement. Do not infer the SMTP relationship from LRZ's separate role as
the gitlab.lrz.de operator.

## Detailed check

| Component | Role | AVV required? | Status |
|---|---|---|---|
| AET servers at TUM (container host) | Own infrastructure | No (Art. 4(7) GDPR — controller's own equipment) | — |
| PostgreSQL (in-house container) | Application data store | No (self-hosted) | — |
| Hephaestus app server (in-house container) | Identity and session management (native auth, ADR 0017) | No (self-hosted) | — |
| Spring Boot application server (in-house container) | Admission, context and persistence. No sandbox on a server-only role. | No (self-hosted) | — |
| Connected worker and review/Heph sandboxes | Executes AI work and holds temporary context and mounted evidence | No when controller-owned. Assess Art. 28 for externally operated workers. | Record each worker operator, location, access and deletion acknowledgement. Server-only operation has no Docker socket. |
| Traefik v3 reverse proxy (in-house container) | TLS termination, routing | No (self-hosted) | — |
| **Cloudflare, Inc.** (USA) | Proxy for the workspace hosts when workspace addresses are on ([ADR 0053](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0053-workspace-addresses-are-a-presentation-origin.md)). It ends TLS for these hosts. Thus, it processes the IP address, the request metadata and the static web app of each host. The apex with sign-in, cookies and the API stays direct | **Yes, when engaged** | Not engaged. Before the proxy carries traffic, record the accepted [Cloudflare DPA](https://www.cloudflare.com/cloudflare-customer-dpa/) and its DPF certification on the active list. Record SCCs Module 2 as the fall-back, and the log retention. Complete the recipient record below. |
| `Let's Encrypt` ACME endpoint | Domain-validation certificates | The endpoint receives no end-user project content. Record ACME account/contact data separately. | — |
| **GitHub, Inc.** (USA) | Identity provider (OAuth) and source-system API for connected GitHub repositories | **Yes** | A DPA is in place at TUM/AET level. GitHub holds its own EU-US Data Privacy Framework certification. Microsoft's published covered-entities list confirms that this certification is independent of Microsoft Corporation's. SCCs Module 2 provide the contracted fall-back. Re-verify DPF status annually. |
| **In-house model service** (for example a model served through Logos) | Workspace-configured model for practice review and Heph, declared In-house | **Depends on the operator** | Record who operates the model and the Logos service. A model on controller-owned infrastructure needs no AVV. An in-house operator outside the controller needs an Art. 28 assessment. A model that Logos forwards to an external provider is an external recipient (next row), not an in-house one. |
| **External model provider configured for a workspace** (any OpenAI-API-compatible provider, directly or forwarded through Logos) | Workspace-configured model for practice review and Heph, used only where the AI choice allows Cloud | **Yes, when engaged** | No generic approval. Before engagement, complete the recipient record below for the exact provider, service and deployment. Record the signed DPA/AVV, retention and training terms, processing location and transfer basis. Published provider terms or a resource region do not prove the terms of this deployment. |
| **AI model providers for research benchmark runs** | Run AI models on pseudonymized research data for the research organization | **Yes, when engaged** | Verify each recipient separately with the recipient record below. Approval for practice review does not carry over. See "Research benchmark runs" below. |
| **Salesforce, Inc. / Slack Technologies, LLC** (USA) | Slack app delivery, identity linking, App Home privacy controls, DM mentor messages, and monitored-channel event delivery when Slack is enabled by the workspace administrator | **Yes, when engaged** | A DPA is in place at TUM/AET level. Salesforce is DPF-certified (Slack participates under the Salesforce certification). SCCs Module 2 provide the fall-back. |
| **Connected Outline instance operator** | Selected-document source and optional OAuth identity linking | **Depends on the operator's role** | No generic approval. Before activation, classify the exact origin as controller-owned infrastructure, an Art. 28 processor, or a separate controller. Record the operator, region, transfer basis, retention terms, and AVV where required. Workspace selection cannot supply this approval. |
| **Leibniz-Rechenzentrum (LRZ) der BAdW (gitlab.lrz.de)** | Source system and OIDC identity provider | **Not Art. 28** | LRZ is a separate controller. Inter-public-body transmission falls under Art. 5(1) Nr. 1 BayDSG. LRZ is an institute of the Bayerische Akademie der Wissenschaften. It applies its own TOMs on its own infrastructure. |
| GitHub / GHCR (CI, image hosting) | Stores Docker images and CI logs. Does not receive end-user personal data of the Hephaestus service. | No (controller-to-controller on AET-staff data). End-user Hephaestus data is not transferred. | Covered by TUM's general agreements with GitHub Enterprise |

## Evidence required before engagement

The statuses above carry forward the existing TUM record. They are not new contract verification.
The repository cannot prove that a DPA is executed, that a certification is current or that production
uses the stated endpoint. Before activation or renewal, the controller must fill this recipient record:

| Field | Required deployment evidence |
|---|---|
| Recipient and role | `[legal entity, service, endpoint, controller/processor/joint-controller analysis]`. Distinguish source-platform purposes from processing on instructions. |
| Contract | `[signed DPA/AVV reference, instructions, subprocessors, renewal/review owner]`. An API key is not an agreement. |
| Scope | `[data categories, subjects, purposes, review/Heph inputs and any human provider access]`. Include broader Slack-thread and person-history context. |
| Retention and training | `[prompt/response and abuse-monitoring retention, deletion path, training terms, any exception]`. No-training is not zero retention. |
| Location and transfer | `[storage, inference, support access, subprocessors, transfer mechanism and evidence date]`. Verify deployment type, not just a resource's region. |
| Rights and exit | `[export/erasure assistance, incident notice, credential revocation, deletion confirmation]`. State what a completed request cannot retract. |

Art. 28 requires a processor agreement only where the recipient actually processes on the controller's
instructions. Software selection or an admin role cannot establish that relationship. External workspace institutions may be joint controllers under an actual arrangement. Individual authorized admins within one controller are not separate controllers merely because they choose settings.
Self-hosters must assess their own roles rather than copy the TUM arrangement or LRZ classification.

For transfers, verify the appropriate basis for the exact recipient and covered service:

1. Verify an active adequacy decision and certification.
2. Alternatively, verify the appropriate SCC module and transfer assessment with supplementary measures where required. Module 2 is controller-to-processor. It is not automatically the right module for separate controllers or a processor chain. EU hosting and DPF participation do not by themselves
prove every support or subprocessor flow stays in the EEA. Keep evidence and a review date.

### Optional Sentry and monitoring

Record `[Sentry operator, endpoint, hosting, retention, contract and transfer safeguard]` before
activation. Controller-owned self-hosting and SaaS have different roles. The browser requires a DSN
and error-monitoring consent, strips request, user and breadcrumb fields, and disables message/body
collection. Error text and stacks can still carry personal data.

1. Inspect synthetic reports.
2. Apply server-side scrubbing.

Withdrawal stops new client reports, not previously received reports.

The backend also includes Sentry. A nonblank DSN initializes it outside the specs profile. This configuration is not limited to the server role. Inspect the DSN supplied to each server, worker and webhook process.

Browser consent does not control it. It disables default PII and strips request, user and breadcrumb fields. It retains operational trace correlation. Record a separate lawful basis and recipient scope for server diagnostics. Browser consent withdrawal does not turn off server monitoring.

Sentry-side rights and retention need their own procedure.

Optional trace export also needs an
approved collector and retention. It can contain workspace and run identifiers. Private metrics
must not be exposed through the public application route.

### Research benchmark runs

Building and running benchmarks for research can mean running AI models on research data.
The research organization is the controller of this research. For the TUM deployment, it is TUM (AET).
A provider that runs these models for it is a processor.

Apply these rules to every benchmark run:

1. Complete the recipient record above for each provider before its first run. Do not assume that the agreement, region or training terms of practice review apply.
2. Send only pseudonymized data. Pseudonymized data is still personal data (EDPB Guidelines 01/2025).
3. Send data only for participants with a current research grant.
4. Honor the participant's AI choice (**No AI**, **In-house** or **Cloud**) in the run.

Recipients of anonymized datasets are not processors. Anonymous data is outside the GDPR (Recital 26).
A release of a dataset that is not anonymized needs a separate choice and a new notice version.

## Why the LRZ relationship is not Art. 28

An Art. 28 processor processes personal data on behalf of the controller, under the controller's documented instructions. EDPB Guidelines 07/2020 §§ 14–33 identify the decisive criterion: who determines the essential means of the processing. These means include purposes, data, subjects, duration and access.

LRZ is an institute of the Bayerische Akademie der Wissenschaften. As the Bavarian academic computing centre, it operates gitlab.lrz.de for the Bavarian academic-computing community. LRZ determines the purpose, onboarding, retention windows, backup regime, TOMs, and terms of use.

TUM does not instruct LRZ on how to operate gitlab.lrz.de. TUM uses the service as one eligible Bavarian public body among many. Art. 5(1) Nr. 1 BayDSG provides the basis for the inter-public-body transmission.

Two separate controllers, each processing on its own infrastructure for its own purpose, are incompatible with Art. 28 status under EDPB 07/2020.

Art. 26 GDPR (joint controllership) is equally absent: EDPB 07/2020 §§ 50–65 require a joint determination of purposes and means, which is not present. TUM and LRZ each pursue their own distinct purpose. The relationship is a public-body cooperation, consistent with BayLfD published guidance.

## Why the workspace administrator is not an Art. 28 processor

TUM is the controller. The public TUM notice (§10) states that workspace administrators make workspace-configurable decisions under TUM's operating responsibility. An administrator acting within TUM is not a separate controller because of that role.

The listed decisions cover repository selection, practices, models, automatic review triggers, Slack routing and selected Outline collections. The privacy statement sets out the responsibilities of TUM/AET and of workspace administrators.

TUM/AET is the single point of contact for data-subject rights. The workspace administrator is also a contact for workspace-specific questions.

## Follow-up if the processing surface changes

Amend this file, the Art. 30 record, and the privacy statement before deploying any of the following:

- A new LLM provider added to the AET-pool (e.g., Anthropic).
- A new identity provider beyond GitHub and gitlab.lrz.de.
- Enabling Outline for a new origin or changing its operator, hosting region, or contractual role.
- SMTP email delivery becomes active. The chosen SMTP host becomes a recipient of personal data. A TUM-internal relay falls under the TUM-internal framework. An external relay needs an Art. 28 DPA.
- Activating browser or server Sentry. Record the exact operator, role, region, retention and transfer path. Do not assume all SaaS endpoints have the same legal entity or geography. Amend the notice and reassess the DPIA. An external processor needs an Art. 28 agreement.
- The application adds external storage (S3, CDN) or serves a third-party font, script, image, or embed. This requires a recipient-role assessment, any applicable agreement and a privacy-statement entry.
- Heph gains internet access or the practice-review sandbox network posture expands beyond the governed worker gateway. This triggers a re-audit under §5 of `dpia-prescreen.md`.
- An existing processor receives a new artifact-source privacy class. Its region or provider retention changes. Product evidence gains an operator/research evaluation use. An existing DPA does not by itself authorize a new purpose or data category. Complete the [artifact-source governance gate](./artifact-source-governance.md).
- Research benchmark runs start without the agreement that "Research benchmark runs" requires.
- Research benchmark runs use data that is not pseudonymized.

### Direct browser image requests

Avatar components pass provider image URLs to browser image elements. Hephaestus does not proxy these requests. The image host receives visitor network metadata and the requested URL, including from
people browsing public pages without signing in. Record `[hosts, roles, basis, transfer safeguard and
retention]`. The person's Sentry choice does not cover an image-host request.

Third-party hosting does not automatically imply Art. 28 status. Assess its actual role.

## Primary references

- [EDPB Guidelines 07/2020, controller and processor](https://www.edpb.europa.eu/documents/guideline/guidelines-072020-on-the-concepts-of-controller-and-processor-in-the-gdpr_en): roles depend on actual purposes and means.
- [European Commission transfer safeguards and SCCs](https://commission.europa.eu/law/law-topic/data-protection/international-dimension-data-protection/standard-contractual-clauses-scc_en): choose the module for the actual roles.
- [OpenAI API data controls](https://developers.openai.com/api/docs/guides/your-data): distinguish training, abuse monitoring and endpoint-specific application state. Do not infer these terms for a compatible third-party API.
- [Microsoft Azure OpenAI data privacy](https://learn.microsoft.com/en-us/legal/cognitive-services/openai/data-privacy): verify deployment geography and any modified abuse-monitoring approval.
- [Sentry scrubbing](https://docs.sentry.io/security-legal-pii/scrubbing/): client minimization needs recipient-side review too.
- [GitLab account deletion](https://docs.gitlab.com/user/profile/account/delete_account/): upstream deletion is separate from Hephaestus copies and attribution.

Recheck these terms against the contract and exact service version when a provider changes. Published
vendor documentation does not prove that the deployment has purchased or enabled a particular control.
