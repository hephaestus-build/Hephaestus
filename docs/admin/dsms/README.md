---
id: dsms
sidebar_position: 3
title: Data-Protection Documentation
description: Art. 30 / Art. 35 / Art. 28 records and source-governance controls for the TUM-operated deployment.
---

# Hephaestus — Data-Protection Documentation

This package describes the shipped processing and the TUM deployment record. Self-hosters must
complete their own record before collecting data. The TUM identity, public-task basis and institutional
agreements must not be assumed to apply to another operator. A setting, a source-use decision or a passing test cannot
establish a legal basis or prove that a deployment follows this record.

The TUM privacy notice still needs the legal owner's approval before release. This update does not
change that approval state; the release action remains in
[#1377](https://github.com/hephaestus-build/Hephaestus/issues/1377).

## Before collecting data

Complete these deployment decisions in the controller's governance system. Replace each placeholder;
do not publish unresolved placeholders as a valid privacy notice.

| Decision | Operator must record |
|---|---|
| Responsibility | `[controller name, address, representative, privacy contact, DPO where applicable]`; identify any joint controllers and their actual arrangement. An admin role alone does not establish Art. 26 status. |
| Purpose and lawful basis | `[basis and necessity per purpose and data-subject group]`, including people who never sign in, employees, students, incidental third parties, and research participants. Terms acceptance and the AI choice do not supply this basis. |
| Information duties | `[how and when people receive the Art. 13/14 notice]`, including source-only contributors and Slack participants; record any claimed Art. 14 exception and its safeguards. A footer link alone does not prove fulfilment. |
| Risk | `[full DPIA reference, owner, measures, residual risk and controller decision]`. The [screen](./dpia-prescreen.md) indicates a full DPIA for the current combined scope. |
| Recipients and transfers | `[exact providers, roles, regions, contracts, subprocessors, transfer safeguards, renewal dates]`, using the [processor checklist](./processor-checklist.md). |
| Retention | `[duration or review/deletion trigger for every category]`, including stores with no automatic expiry, active Slack threads, unavailable repositories, exports, suppression keys, logs and backups. |
| Security and recovery | `[access owners, key management, backup destination and expiry, restore test, incident response and breach procedure]`. Verify the configured stack; source code does not prove operational controls. |
| Rights | `[verified request intake, secure delivery, response owner, exceptions and completion evidence]`; use the shipped Person data procedure, not ad hoc SQL. |

Help a requester identify their records under Art. 12(2). Resolve stable provider IDs from verified
source-profile/work links if the person does not know them; never substitute a display-name match.
Ask for additional identity evidence only where reasonable doubts require it, and use a secure
transfer channel. Do not demand a Hephaestus account from someone whose work was collected without one.
Under Art. 12(3), respond within one month. A justified extension of up to two further months requires
notice within the first month. Record any refusal and explain the complaint and judicial-remedy rights.
Assess special-category and criminal-offence content under Arts. 9 and 10 separately; an Art. 6 basis
alone does not authorise it. Employee or student consent must be freely given, with a real refusal
path and no disadvantage; a workplace or course power imbalance needs explicit assessment.
Assess portability under Art. 20 separately: it applies to qualifying consent- or contract-based,
automated processing, and must respect other people's rights. A JSON download does not make all
public-task processing subject to Art. 20.

## Answering an access request

An Art. 15 reply includes confirmation of processing, access to the person's data and information
about the processing: purposes, categories, recipients, retention periods or criteria, rights,
complaint route, available source information, applicable automated-decision information and transfer
safeguards. Use verified deployment facts; downloading the JSON alone does not complete the reply.
See the [EDPB right-of-access guidelines](https://www.edpb.europa.eu/documents/guideline/guidelines-012022-on-data-subject-rights-right-of-access_en).

Before delivery, review shared-source and free-text content for other people's rights under
Art. 15(4). Export field allowlists omit credential fields and other people's profiles; they do not
redact every name, secret or third-party detail written into source text. Where disclosure would
adversely affect another person's rights or freedoms, document the concrete risk and use targeted
redaction of the delivery copy instead of refusing the whole request. Do not change the frozen
selection or canonical records to prepare that copy. Use a verified recipient, secure transfer and
an expiry for the delivery copy, and record any justified limits in the reply.

## Files

| File | Purpose |
|---|---|
| [`record-of-processing.md`](./record-of-processing.md) | Art. 30 record. TOMs (Art. 32) folded in under Art. 30(1)(g). Fenced blocks paste-ready into the TUM DSMS form. |
| [`dpia-prescreen.md`](./dpia-prescreen.md) | Art. 35 pre-screen. Indicates a full DPIA; records the pending controller/DPO determination, safeguards, and change freeze. |
| [`processor-checklist.md`](./processor-checklist.md) | Art. 28 checklist. Per-processor AVV status; LRZ-as-separate-controller analysis. |
| [`artifact-source-governance.md`](./artifact-source-governance.md) | Approval, minimization, processor-egress, retention, and erasure gate for every AI-readable source. |
| [`personal-data-map.md`](./personal-data-map.md) | Personal-data stores, export coverage, erasure paths, residual retention, and their verification. |

The live imprint and privacy pages are at https://hephaestus.build/imprint and https://hephaestus.build/privacy. Markdown source: [`webapp/public/legal/profiles/tumaet/`](https://github.com/hephaestus-build/Hephaestus/tree/main/webapp/public/legal/profiles/tumaet).

## Maintenance

Re-review annually and on any material change to the processing surface (new processor, source, source
combination, purpose, audience, data category, retention window, identity provider, or activation of an integration
not covered by the latest review). The DPIA pre-screen requires a recorded controller/DPO determination before material
source expansion. The amendment triggers are listed in `processor-checklist.md`,
`dpia-prescreen.md` §5–§6, and `artifact-source-governance.md`.

## Contacts

- TUM DPO: [beauftragter@datenschutz.tum.de](mailto:beauftragter@datenschutz.tum.de).
- Hephaestus operational contact: [ls1.admin@in.tum.de](mailto:ls1.admin@in.tum.de).
- TUM DSMS portal: https://dsms.datenschutz.tum.de/ (reachable from MWN / eduVPN with TUM login).
