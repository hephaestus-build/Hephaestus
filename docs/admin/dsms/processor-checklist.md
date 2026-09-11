# Hephaestus — Art. 28 Processor Checklist

Records every entity that might qualify as a processor (Art. 28 GDPR) for the TUM-operated Hephaestus deployment and the status of the corresponding Auftragsverarbeitungsvertrag (AVV). Internal AET-operated components and the LRZ public-body counterpart are listed for completeness so the record is self-contained.

## Summary

Hephaestus engages a processor chain of: GitHub (identity provider and source-system API), the per-workspace LLM provider (Microsoft Azure OpenAI by default on the TUM-operated deployment, or any OpenAI-API-compatible HTTPS endpoint configured per workspace), and Slack (per-workspace opt-in). The LRZ (gitlab.lrz.de) is **not** a processor; it is a separate controller under the EDPB 07/2020 framework. Outline has no fixed operator or origin, so it remains disabled until the operator records a per-instance role, hosting region, transfer basis, and AVV status. The webapp ships a disabled Sentry client; it is off in the current production deployment.

First-party product feedback and survey responses remain inside the instance PostgreSQL database and add no processor.

## Detailed check

| Component | Role | AVV required? | Status |
|---|---|---|---|
| AET servers at TUM (container host) | Own infrastructure | No (Art. 4(7) GDPR — controller's own equipment) | — |
| PostgreSQL (in-house container) | Application data store | No (self-hosted) | — |
| Hephaestus app server (in-house container) | Identity and session management (native auth, ADR 0017) | No (self-hosted) | — |
| Spring Boot application server (in-house container) | Application logic, in-process Pi mentor agent | No (self-hosted) | — |
| Traefik v3 reverse proxy (in-house container) | TLS termination, routing | No (self-hosted) | — |
| Let's Encrypt ACME endpoint | Domain-validation certificates | No — Let's Encrypt receives no personal data; it only checks control over the domain | — |
| **GitHub, Inc.** (USA) | Identity provider (OAuth) and source-system API for connected GitHub repositories | **Yes** | DPA in place at TUM/AET level; GitHub holds its own EU-US Data Privacy Framework certification (independent of Microsoft Corporation's, per Microsoft's published covered-entities list); SCCs Module 2 contracted as fall-back; re-verify DPF status annually |
| **Microsoft Corporation (Azure OpenAI Service)** (USA / EU) | Default LLM provider for the TUM-operated deployment; EU-region tenancy keeps processing within the EU | **Yes** | DPA at TUM/AET level for the TUM-operated tenancy; at the workspace administrator's institution level when that institution supplies credentials (joint-controller model, privacy §10); enterprise API no-training terms; DPF-certified; SCCs Module 2 as fall-back |
| **OpenAI OpCo, LLC** (USA), with **OpenAI Ireland Ltd.** (Ireland) as the EEA contracting party — or any OpenAI-API-compatible endpoint chosen by a workspace administrator | Workspace-configured LLM provider | **Yes, when engaged** | DPA at TUM/AET level for AET-pool processors; at the administrator's institution level for non-pool endpoints; DPF / SCC framing applies recipient-by-recipient and DPF status is verified per recipient before engagement |
| **Salesforce, Inc. / Slack Technologies, LLC** (USA) | Slack app delivery, identity linking, App Home privacy controls, optional digests, DM mentor messages, and monitored-channel event delivery when Slack is enabled by the workspace administrator | **Yes, when engaged** | DPA in place at TUM/AET level; Salesforce DPF-certified (Slack participates under the Salesforce certification); SCCs Module 2 as fall-back |
| **Connected Outline instance operator** | Selected-document source and optional OAuth identity linking | **Depends on the operator's role** | No generic approval. Before activation, classify the exact origin as controller-owned infrastructure, an Art. 28 processor, or a separate controller; record the operator, region, transfer basis, retention terms, and AVV where required. Workspace selection cannot supply this approval. |
| **Leibniz-Rechenzentrum (LRZ) der BAdW (gitlab.lrz.de)** | Source system and OIDC identity provider | **Not Art. 28** | Separate controller; inter-public-body transmission under Art. 5(1) Nr. 1 BayDSG; LRZ is an institute of the Bayerische Akademie der Wissenschaften and applies its own TOMs on its own infrastructure |
| GitHub / GHCR (CI, image hosting) | Stores Docker images and CI logs; does not receive end-user personal data of the Hephaestus service | No (controller-to-controller on AET-staff data; end-user Hephaestus data is not transferred) | Covered by TUM's general agreements with GitHub Enterprise |

## Why the LRZ relationship is not Art. 28

An Art. 28 processor is engaged to process personal data on behalf of the controller, under the controller's documented instructions. EDPB Guidelines 07/2020 §§ 14–33 set out that the decisive criterion is who determines the essential means of the processing: the purposes, which data, which subjects, how long, what access.

LRZ is an institute of the Bayerische Akademie der Wissenschaften and operates gitlab.lrz.de for the Bavarian academic-computing community as the Bavarian academic computing centre. LRZ determines the purpose, onboarding, retention windows, backup regime, TOMs, and terms of use. TUM does not instruct LRZ on how to operate gitlab.lrz.de; TUM consumes the service as one eligible Bavarian public body among many, with the inter-public-body transmission anchored in Art. 5(1) Nr. 1 BayDSG. Two separate controllers, each processing on its own infrastructure for its own purpose, are incompatible with Art. 28 status under EDPB 07/2020.

Art. 26 GDPR (joint controllership) is equally absent: EDPB 07/2020 §§ 50–65 require a joint determination of purposes and means, which is not present. TUM and LRZ each pursue their own distinct purpose. The relationship is a public-body cooperation, consistent with BayLfD published guidance.

## Why the workspace administrator is not an Art. 28 processor

Workspace administrators are **joint controllers** with TUM/AET under Art. 26 GDPR for the workspace-configurable decisions enumerated in §10 of the privacy statement (which Git repositories are connected, the practice catalog, the LLM provider and credentials, whether practice reviews are auto-triggered on new pull/merge requests, leaderboard / leagues, Slack routing, and whether Outline and selected collections are enabled). The Art. 26(2) Satz 1 allocation of duties and the Art. 26(2) Satz 2 essence of the arrangement are made available to data subjects via the privacy statement; TUM/AET is the single point of contact for data-subject rights, with the workspace administrator additionally addressable for workspace-specific questions.

## Follow-up if the processing surface changes

Amend this file, the Art. 30 record, and the privacy statement before deploying any of the following:

- A new LLM provider added to the AET-pool (e.g., Anthropic).
- A new identity provider beyond GitHub and gitlab.lrz.de.
- Enabling Outline for a new origin or changing its operator, hosting region, or contractual role.
- Activating any future transactional-email integration (the chosen SMTP host becomes a recipient of personal data; a TUM-internal relay falls under the TUM-internal framework, an external relay needs an Art. 28 DPA).
- Activating the bundled Sentry client. A self-hosted Sentry on TUM infrastructure is an in-house recipient; a SaaS Sentry tenant is an Art. 28 U.S. processor that needs a DPA, a privacy-statement entry, and a DPIA re-assessment.
- Any external storage (S3, CDN) or any third-party font, script, image, or embed served from the application: requires an AVV and a privacy-statement entry.
- Any widening of the practice-review sandbox network posture beyond the per-job LLM proxy — triggers a re-audit under §5 of `dpia-prescreen.md`.
- Sending a new artifact-source privacy class to an existing processor, changing its region or provider retention,
  or using product evidence for operator/research evaluation. An existing DPA does not by itself authorize a new
  purpose or data category; complete the [artifact-source governance gate](./artifact-source-governance.md).
