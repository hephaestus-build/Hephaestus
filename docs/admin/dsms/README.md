---
id: dsms
sidebar_position: 3
title: Data-Protection Documentation
description: What an operator records under Arts. 28, 30 and 35 GDPR, and the source-governance and personal-data controls that Hephaestus ships.
---

# Hephaestus — Data-Protection Documentation

This package describes the processing that Hephaestus ships.
Each operator completes its own record of processing before it collects data.
Do not assume that another operator's identity, legal basis, or institutional agreements apply to your deployment.
A setting, a source-use decision, or a successful test cannot establish a legal basis.
None of these proves that a deployment follows its record.

## Before collecting data

Do not publish unresolved placeholders as a valid privacy notice.

1. Complete these deployment decisions in the controller's governance system.
2. Replace each placeholder.

| Decision | Operator must record |
|---|---|
| Responsibility | `[controller name, address, representative, privacy contact, DPO where applicable]`. Identify any joint controllers and their actual arrangement. An admin role alone does not establish Art. 26 status. |
| Purpose and lawful basis | `[basis and necessity per purpose and data-subject group]`, including people who never sign in, employees, students, incidental third parties, and research participants. Terms acceptance and the AI choice do not supply this basis. Research uses a broad consent to an area of research. Record `[research organization, research information page URL or contact, retention criterion, removal procedure, privacy contact]`. The [research prerequisites](../legal-pages.mdx#what-operators-must-do) decide whether an ethics vote is needed. |
| Information duties | `[how and when people receive the Art. 13/14 notice]`, including source-only contributors and Slack participants. Record any claimed Art. 14 exception and its safeguards. A footer link alone does not prove that you meet this duty. |
| Risk | `[full DPIA reference, owner, measures, residual risk and controller decision]`. The combined source scope indicates a full DPIA. |
| Recipients and transfers | `[exact providers, roles, regions, contracts, subprocessors, transfer safeguards, renewal dates]`, using the [processor checklist](./processor-checklist.md). |
| Retention | `[duration or review/deletion trigger for every category]`, including stores with no automatic expiry, active Slack threads, unavailable repositories, exports, suppression keys, logs and backups. |
| Security and recovery | `[access owners, key management, backup destination and expiry, restore test, incident response and breach procedure]`. Verify the configured stack. Source code does not prove operational controls. |
| Rights | `[verified request intake, secure delivery, response owner, exceptions and completion evidence]`. Use the shipped Person data procedure, not ad hoc SQL. |

1. Help a requester identify their records under Art. 12(2).
2. If the person does not know their stable provider IDs, resolve them from verified source-profile/work links.
   Never substitute a display-name match.
3. Ask for additional identity evidence only where reasonable doubts require it.
4. Use a secure transfer channel.
   Do not demand a Hephaestus account from someone whose work you collected without one.
5. Under Art. 12(3), respond within one month.
   A justified extension of up to two further months requires notice within the first month.
6. Record any refusal.
7. Explain the complaint and judicial-remedy rights.
8. Assess special-category and criminal-offense content under Arts. 9 and 10 separately.
   An Art. 6 basis alone does not authorize it.
9. Explicitly assess a workplace or course power imbalance.
   Employee or student consent must be freely given, with a real refusal path and no disadvantage.
10. Assess portability under Art. 20 separately.
    It applies to qualifying consent- or contract-based automated processing.
    It must respect other people's rights.
    A JSON download does not make all public-task processing subject to Art. 20.

## Answering an access request

An Art. 15 reply includes confirmation of processing and access to the person's data.
It also includes this information about the processing:

- Purposes.
- Categories.
- Recipients.
- Retention periods or criteria.
- Rights.
- The complaint route.
- Available source information.
- Applicable automated-decision information.
- Transfer safeguards.

Use verified deployment facts.
A JSON download alone does not complete the reply.
See the [EDPB right-of-access guidelines](https://www.edpb.europa.eu/documents/guideline/guidelines-012022-on-data-subject-rights-right-of-access_en).

Export field allowlists omit credential fields and other people's profiles.
They do not redact every name, secret, or third-party detail in source text.

1. Before delivery, review shared-source and free-text content for other people's rights under Art. 15(4).
2. If disclosure would adversely affect another person's rights or freedoms, document the concrete risk.
3. In that case, use targeted redaction of the delivery copy instead of refusal of the whole request.
   Do not change the frozen selection or canonical records to prepare that copy.
4. Use a verified recipient.
5. Use secure transfer.
6. Set an expiry for the delivery copy.
7. Record any justified limits in the reply.

## Files

| File | Purpose |
|---|---|
| [`record-of-processing.md`](./record-of-processing.md) | Art. 30 template: each element with the placeholders that the operator fills in, and the pages that describe what Hephaestus does. |
| [`processor-checklist.md`](./processor-checklist.md) | Art. 28 checklist: the recipients to assess and the evidence to record for each. |
| [`item-source-governance.md`](./item-source-governance.md) | Approval, minimization, processor-egress, retention, and erasure gate for every AI-readable source. |
| [`personal-data-map.md`](./personal-data-map.md) | Personal-data stores, export coverage, erasure paths, residual retention, and their verification. Includes how withdrawal reaches research copies. |

[Data retention](../data-retention.mdx) lists the retention period of each category that Hephaestus stores.

## Maintenance

The record requires review annually and on any material change to the processing surface.
This includes a new processor, source, or source combination.
It also includes changes to purpose, audience, data category, retention window, or identity provider.
Activation of an integration that the latest review does not cover also requires review.

Record a controller or DPO determination in your DPIA before a material source expansion.
The amendment triggers are in `processor-checklist.md` and `item-source-governance.md`.

A change to the research wording needs a new notice version.
Every account then answers once more, because an earlier yes to narrower wording does not carry over.
Update the records in this package in the same change.
