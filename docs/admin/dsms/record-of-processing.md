# Hephaestus — Record of Processing Activities (Art. 30 GDPR)

This file is the Art. 30 record for the TUM-operated Hephaestus deployment at https://hephaestus.build. Each section maps to a single Art. 30 element. Each fenced code block is ready to paste into its corresponding TUM DSMS form field at https://dsms.datenschutz.tum.de/. Text outside the fences gives context.

The institutional statements below come from the existing TUM record. Code verifies product behavior, not signed agreements, live settings or operational measures. The controller must verify those statements and complete deployment placeholders before using this as a completed record.

## Identifier

- Title: `Hephaestus – Practice-Aware Feedback for Software Projects`
- Tags: `Webdienst`, `Lehre`, `Forschungsprojekt`
- Joint Controller: before selecting this field, confirm the actual parties and arrangement.
  An admin role alone does not establish Art. 26 status. See "Legal basis" below.
- Relevant for Subject Rights Request (SRR): tick.
- Responsible department: TUM School of Computation, Information and Technology.
- Associated TUM Org identifier: `TUS1322`.
- DPIA pre-screen: see [`dpia-prescreen.md`](./dpia-prescreen.md) — full DPIA indicated. Controller/DPO determination pending. Engineering approval does not lift the deployment change freeze.

## Controller and contact (Art. 30(1)(a))

```text
Technical University of Munich (TUM)
Arcisstraße 21, 80333 Munich, Germany
Represented by President Prof. Dr. Thomas F. Hofmann
Email: poststelle@tum.de
Telephone: +49 (0)89 289-01
DPO: beauftragter@datenschutz.tum.de

Operational responsibility: Prof. Dr. Stephan Krusche, Head of AET
Research Group for Applied Education Technologies
TUM School of Computation, Information and Technology
Department of Computer Science
Boltzmannstraße 3, 85748 Garching bei München, Germany

Operational technical contact: ls1.admin@in.tum.de
```

DSMS responsible person: Stephan Krusche (krusche@tum.de). Felix Dietrich (felixtj.dietrich@tum.de) is an additional responsible person with edit access.

## Purpose and description (Art. 30(1)(b))

```text
AET operates Hephaestus, a self-hosted web platform, on TUM infrastructure at https://hephaestus.build. It supports project-based software-engineering teaching at TUM and development work in AET research projects. It gives each contributor feedback on their collaborative engineering work. For example, feedback can address whether a pull request is small enough for an effective review. It can also address whether a review reply answers the question raised.

A workspace administrator connects one or more Git repositories from github.com or gitlab.lrz.de.

Hephaestus then syncs authored pull/merge requests, issues, code reviews, review comments, and commit metadata from those repositories. The platform processes activity authored in the connected repositories, whether or not the author has signed in to Hephaestus.

Hephaestus analyzes the synced activity against practices that the workspace administrator configures. It produces observations about each contributor's activity. Some judgments require the analysis to read and understand natural-language text. Examples include the meaning of a code comment or the substance of a review reply. For those judgments, the analysis uses an external LLM provider that the administrator chooses for the workspace.

Automated practice review forwards the relevant pull/merge-request diff or issue content and the surrounding discussion to the provider. For a pull/merge request, it also forwards whatever the review reads from the captured repository and its history. It can post the resulting AI-generated feedback as comments on the reviewed artifact. Where explicitly connected and permitted, selected Outline documents may supply project context.

The conversational mentor is an in-app chat where contributors can ask follow-up questions. Hephaestus forwards their messages and the approved bounded context to the same provider. This context can come from repository activity, prior feedback, selected Outline documents, or participant-permitted monitored Slack channels. Sources are purpose- and audience-bound. An enabled integration does not by itself authorize all of its content for every request.

Contributors who sign in with their GitHub or LRZ-GitLab account get a personal dashboard that summarizes their observations and activity. They get access to the conversational mentor and their account preferences. Sign-in adds the federated user identifier, username, display name, email, and avatar URL to what Hephaestus holds about that contributor.

Everyone with a role in the workspace also sees Activity. Activity shows counts and lists of pull/merge requests, reviews, issues and comments that members did in the workspace's repositories. It also shows each member's open work (review requests, assigned issues), with each reviewer's review state. Members see this for themselves and for the workspace. Members appear by name, with no scores or ranks.

Team memberships sync from GitHub teams and gitlab.lrz.de subgroups. They let members narrow Workspace activity to one team. On GitHub, they show a member the review requests addressed to one of their teams. Activity does not check a viewer's own permissions in the source system. Workspace administrators can hide a member from the workspace view. They can also enable Slack integration for App Home privacy controls, mentor DMs, and explicitly activated monitored channels.

Signed-in contributors can send product feedback. They can answer or decline surveys that instance administrators author. These submissions stay in the instance database. Instance administrators can read them for product improvement. They are not reused for research. Research-purpose surveys are covered under *Legal basis* below.

The existing TUM record describes an Art. 26 arrangement for these workspace-level choices. The legal owner must confirm its actual parties and arrangement reference. The choices are listed in "Legal basis" below. Hephaestus focuses on the contributor's own development. Observations serve the contributor and let the workspace administrator deliver targeted feedback during the project.

Observations are advisory and contestable. The platform makes no automated decisions within the meaning of Art. 22 GDPR. It feeds no grading, assessment, HR, or access-control pipeline. Signed-in contributors can stop new practice-feedback comments and related Slack reminders through the in-app **Comments and Slack reminders** setting. They can respond to individual pieces of feedback by recording whether the feedback was helpful and how they handled it.

This delivery setting does not stop review processing.

Objections to processing under Art. 21 GDPR use the contact process in privacy §7.
```

### Optional browser extension

The desktop Chrome extension is another client for the same review-context purpose.
On exact sites the user allows, it sends canonical GitHub/GitLab work addresses to the selected Hephaestus instance.
It sends the address of each supported page the user opens, automatically when the page opens and while it stays open.
This lets it show the existing practice review in the page.

On supported repository issue and pull/merge-request lists, a row's Hephaestus preview sends only that work's canonical address when selected.
The extension does not look up every row.
It does not send the list's search terms and filters.
The extension's own window confirms review requests and run changes.
These actions use the same server endpoints and authorization as the web app.
It does not collect page bodies or browsing history.

The extension handles account identity, installed-client credentials and the local instance preference.
It also handles a per-tab session-memory record of the reader's report choices and returned review records.
It handles only the reader's own records, including for administrators.
These include metadata about comments recorded as posted for the reader on that work:

- Practice names, comment locations and links.
- Recorded delivery times.

When the report opens, it also handles the reader's own observations on that work:

- Practice, outcome and severity.
- A one-sentence summary.

It reads and shows no feedback text and no other developer's records.
Those remain in the web app under its own access rules.
The default destination is `hephaestus.build`.
Custom instances have their own operator.
The published notice's **Chrome extension** section and [extension privacy guide](/user/browser-extension-privacy) own the disclosure and controls.
The [personal-data map](./personal-data-map.md) owns installed-client session cleanup.

Canonical work-URL lookups are not a research dataset.
Platform research consent does not authorize unrelated reuse of extension browsing activity.
There is no extension analytics or error-reporting recipient.

## Data subjects (Art. 30(1)(c))

In DSMS:

1. Select `Students (TUM)`.
2. Select `Students (extern)`.
3. Select `Employees (TUM)`.
4. Select `Employees (extern)`.
5. Select `Other Website Visitors`.

## Categories of personal data

Do **not** select `Examination and academic performance`. Practice observations are advisory, not graded.

In DSMS:

1. Select `Name(s)`.
2. Select `Contact details: email`.
3. Select `Image data`.
4. Select `Indicators of Behaviour`.
5. Select `IP address`.
6. Select `Social network data`.
7. Select `User IDs and Passwords`.

```text
The personal-data categories include repository-activity artifacts that the contributor authors in the connected Git repositories:

- Pull/merge requests, issues, code reviews, review comments and commit metadata.
- Where the deployment enables repository checkout, the repository at the reviewed commit and the Git history reachable from it.

Other categories include:

- Review requests addressed to the contributor and each reviewer's review state.
- Team memberships synced from GitHub teams and gitlab.lrz.de subgroups.
- AI guidance-assistant conversations.
- Product-feedback text, the optionally shared page path and browser user agent, and the receiving release version.
- Survey invitations, answers and declines.
- Email subscription choices and times, and opaque unsubscribe capabilities.
- Email request/expiry/cancellation and relay-acceptance facts, and pending notification payloads.
- Selected Outline project documents.
- Slack integration data when enabled: Slack IDs, Slack identity links and App Home privacy choices.
- Hephaestus DM mentor messages and new messages in administrator-activated monitored Slack channels, when Slack is enabled.
```

Hephaestus does not intentionally solicit or classify special-category data (Art. 9(1) GDPR) or criminal-offence data (Art. 10 GDPR). Because repository and chat fields contain free text, incidental content may include and therefore cause processing of them. The privacy statement instructs users not to enter third-party personal or sensitive data.

## Recipients (Art. 30(1)(d))

```text
TUM/AET engages external processors as controller. AVVs are in place at TUM/AET level for the AET-pool processors. Where a workspace administrator configures a different LLM endpoint, the AVV is at that administrator's institution. See the LLM provider details below.

- GitHub, Inc. (USA) is the identity provider (OAuth) and source-system API for connected repositories on github.com.

- The workspace administrator chooses an external LLM provider per workspace from any OpenAI-API-compatible HTTPS endpoint. A base URL, an API token, and a model name configure the endpoint. The choice is a joint-controller decision under Art. 26 GDPR. By default, the TUM-operated deployment uses Microsoft Azure OpenAI Service in an EU region under enterprise no-training terms. A workspace administrator may configure a different endpoint instead. Examples include OpenAI OpCo, LLC (with OpenAI Ireland Ltd. as the EEA contracting party). An institution-level enterprise gateway or a self-hosted model server is another option.

- When the workspace enables Slack, Salesforce, Inc. / Slack Technologies, LLC (USA) provides Slack app delivery and identity linking. It also provides App Home privacy controls, DM mentor messages, and monitored-channel event delivery.

- The operator of the exact Outline origin connected to a workspace supplies selected documents and optional OAuth identity. Hephaestus has no default Outline vendor or origin. The integration remains disabled until the deployment record classifies that operator. The classification must identify controller-owned infrastructure, an Art. 28 processor, or a separate controller. The record must include its region, transfer basis, retention terms, and AVV where required.

Separate controller (not an Art. 28 processor):

- Leibniz-Rechenzentrum (LRZ) der BAdW operates gitlab.lrz.de. The platform receives the contributor's identity from gitlab.lrz.de OIDC and syncs connected gitlab.lrz.de repositories. Inter-public-body transmission falls under Art. 5(1) Nr. 1 BayDSG.
```

When SMTP is enabled, the configured mail relay receives recipient addresses, notification types and
links for account-security/deletion email and opted-in notifications. Product-feedback bodies and
survey answers are not sent. The deployment record must identify the relay operator and the applicable
TUM-internal arrangement or external processing agreement before activation. The SMTP configuration
alone is not evidence that this review is complete. Email is optional and may remain disabled.

Per-processor AVV detail and the EDPB 07/2020 reasoning for the LRZ relationship are in `processor-checklist.md`.

Product feedback and surveys are first-party processing. Submissions stay in the instance database. Instance administrators can read them. Email alerts do not include them. They are not reused for research. Research-purpose surveys are covered under *Legal basis* below.

Browser avatar requests go directly to the source image host, including for unsigned visitors on
public pages. Record `[actual image hosts, roles, lawful basis, transfer safeguards and host retention]`.
They receive visitor IP/network metadata and the requested image URL. Optional browser/backend Sentry
and trace collectors also need recipient entries when configured. See the processor checklist.

## Third-country transfers (Art. 30(1)(e))

The EU-US Data Privacy Framework covers U.S. recipients where the recipient is on the active DPF list (Commission Implementing Decision (EU) 2023/1795). Standard Contractual Clauses Module 2 provide the fall-back (Commission Implementing Decision (EU) 2021/914). The TUM-operated deployment defaults to Microsoft Azure OpenAI in an EU region. Verify processing geography against the actual deployment type and contract. An Outline origin outside the EEA cannot be enabled until its transfer basis is recorded in this section.

## Storage location and retention (Art. 30(1)(f))

**Where stored**

```text
AET hosts Hephaestus at https://hephaestus.build on AET-administered infrastructure at TUM. PostgreSQL holds application data and authentication state. Authentication state includes accounts, federated identity links, the cookie-session revocation list, and the auth-event log. PostgreSQL also holds the practice-review job queue. NATS JetStream holds webhook and integration-sync events.

When practice-review code execution is enabled, the host filesystem may store local working copies of monitored repositories. Container stdout goes to the Docker json-file driver. The compose files set explicit rotation caps for every service:

- 50 MiB per file × 5 files: webapp, application server, worker and PostgreSQL.
- 10 MiB × 3: webhook receiver, NATS, reverse proxy and maintenance page.

No layer of the stack writes an HTTP access log. The production profile explicitly disables Tomcat's access log. The Traefik reverse proxy starts without `--accesslog` (Traefik's default is off). Both nginx containers (static frontend and maintenance page) disable the access log at the server level. The shipped stack creates no general HTTP access-log copy. Authentication and security events still record connection metadata.

Application and authentication data reside on TUM infrastructure within the EU. AI-assisted features also forward code snippets and surrounding discussion to the workspace-configured LLM provider. The TUM-operated deployment defaults to Microsoft Azure OpenAI in an EU region.
```

**Retention**

```text
Mixed retention by category:

Sign-in account data

The in-app account-deletion control revokes access immediately. After a 48-hour cooldown, a scheduled sweeper removes federated identity links and the other authentication rows listed under "Deletion guarantee". It tombstones the account. Product-feedback submissions and survey invitations, responses or declines have no independent time-based expiry. Account erasure removes them. Workspace purge removes workspace-scoped submissions.

Email subscriptions and unsubscribe capabilities

Hephaestus retains these until account erasure. Opt-out preserves the opt-out and disable-only link. Survey email request and relay-acceptance records remain with the survey, with no independent age-based expiry. Account erasure, survey deletion and applicable workspace purge remove them.

The system deletes pending notification publications on completion. After their seven-day delivery deadline, the next processing attempt expires and removes them. An offline instance cannot run that cleanup. Delivered mailbox copies and relay logs follow the recipient or relay's retention, not this application's deletion schedule.

Self-service account exports

Download eligibility expires 48 hours after preparation. The export sweeper removes expired archives. Person-data previews expire after 48 hours when abandoned. Wider rights-request delivery copies need an operator-defined secure transfer and expiry.

Authentication-event log

This log includes sign-in / sign-out, token issue / refresh, user views by an instance administrator, and historical impersonation events. It includes the source IP address and user agent. The system retains events for 12 months in monthly partitions and automatically drops the oldest partition. This is a security measure under the documented security-processing basis and Art. 32 GDPR.

A user-view event records the instance administrator, the workspace, the viewed user, the stated reason and what was read. Account erasure clears the IP address, user agent, and free-text details from events where the erased account was the instance administrator or viewed user. Account references still point to the tombstoned account. The event row remains for the rest of its window.

Contributor profile and account preferences

Profiles include login, name, email and avatar. Hephaestus retains these as instance-global identity records independently of workspace repository monitoring. Repository orphan cleanup and workspace purge do not remove them. On receipt of a verified request, the instance-admin Person data procedure exports them and removes or anonymizes them. Matching uses exact provider instance and native user keys, not names, logins or email addresses.

Authored repository artifacts and derived records

These include issues, pull/merge requests, comments and reviews synced from GitHub / gitlab.lrz.de. They also include their practice observations, delivery-policy traces, and pending delivery packages. The active PostgreSQL mirror remains while at least one workspace monitors the source repository. Removal occurs when the last workspace stops monitoring it, the last relevant connection disconnects, the workspace is purged, or verified erasure completes.

After projection of a package into the practice-feedback ledger, a terminal delivery keeps only its idempotency and provider-placement record. Failed packages remain available for an administrator to retry. Diagnostic, worker-input, and broker copies follow the bounded windows below. They do not all support immediate selective erasure.

Workspace memberships, AI conversations, and activity records

Hephaestus retains these with the relevant workspace records. Removal occurs when those records or the workspace are purged, or through the instance-admin Person data job on verified erasure. Disconnect or workspace purge removes the active PostgreSQL mirror of Slack and Outline content. Materialized diagnostic output, worker-input copies, and broker messages expire under the bounded windows below.

Retired leaderboard values (league points, XP)

Installation of the release that retired the leaderboard deletes these values from the database. An operator may keep an exported copy outside the application. That copy follows the operator's own retention.

Active mirror removal

Removal on disconnect / purge applies storage limitation (Art. 5(1)(e) GDPR). The integration is the sole purpose for which Hephaestus holds the mirror. Once the integration is severed, there is no basis to retain that copy. This workspace-administrator action is **not** the fulfilment path for a data subject's erasure request under Art. 17.

That request uses the instance-admin Person data process under "Deletion responsibility" below. This process also covers account-bound rows that no single workspace owns.

LLM-provider-side prompts

The chosen provider's terms govern retention. The TUM-operated default is Microsoft Azure OpenAI in an EU region. Its retention follows the enterprise abuse-monitoring window in Microsoft's Azure OpenAI data-privacy documentation. Eligible customers may apply for Microsoft's modified abuse monitoring (Limited Access program) to suppress prompt storage and human review.

Settings-change audit log (`config_audit_event`)

The log records who changed which workspace or AI setting, when, and the before/after values. It records the acting account. For historical changes under the retired impersonation feature, it records the impersonating administrator. The system retains it for 365 days, then deletes it automatically.

A database trigger makes the table append-only, with an exception to clear actor references on account erasure. The change record remains for the rest of its window. The log never stores credentials. A rotation records only that a secret changed.

LLM usage ledger (`llm_usage_event`)

The ledger holds per-run token counts and cost, attributed to a workspace and to the run that incurred them. Rows become eligible for deletion once older than the configured window (`HEPHAESTUS_LLM_USAGE_RETENTION`, default `P400D`). Operators can change this window. They can increase it where a commercial or tax retention obligation runs longer.

A daily sweep deletes in batches within a bounded pass. Removal starts at the first sweep for which a row is eligible. Later sweeps clear any remaining backlog. This supports annual accounting comparisons without indefinite retention.

The ledger holds no message content and no free text. Its opaque source identifier may outlive the source row. It is not a foreign key. It cannot resolve a source row after deletion of that row.

Practice-review job records (`agent_job`)

These records include the queued job, the sandbox's captured stdout, and the observations it produced. They can quote the contributor's code and discussion. The retention service strips the diagnostic payload (`container_logs`, `output`) to NULL 14 days after the job reaches a terminal state (`AGENT_PAYLOAD_RETENTION`, default `P14D`). The unreferenced row becomes eligible for deletion after 90 days (`AGENT_ROW_RETENTION`, default `P90D`). Operators can change both windows.

Pending delivery blocks both passes. Observations or feedback that reference the job block row deletion. The scheduled service runs only with the server role and AGENT_ENABLED=true. If that service is disabled, retained jobs do not expire.

1. Monitor the backlog.
2. For individual requests, use verified person erasure.

Artifact-source evidence retention

Retention separates durable verification results from temporary worker inputs. The [artifact-source governance decision](./artifact-source-governance.md) owns the retention and erasure boundaries. A digest is an integrity identifier, not authorization. Extended evaluation retention requires a separate approved purpose and tenant-safe authorization.

Webhook and integration-sync event transport buffer (NATS JetStream)

The `slack` and `outline` streams carry real message and document content and expire after at most 72 hours. The GitHub and GitLab streams carry delivered webhook payloads: pull-request, issue and review-comment bodies. These streams expire after at most 180 days.

Each stream also has a disk ceiling (`HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES`). When ingest volume reaches it, the broker discards the oldest messages first. Actual retention is then shorter than the time limit. The system reports retention per stream as `webhook.stream.oldest.message.age`. The time limit is the maximum, not the guaranteed duration.

PostgreSQL is the system of record, and all consent/erasure controls apply there. Erasure cannot reach inside the broker. These horizons, not an erasure request, remove the buffered copy.

Container stdout

Container stdout includes startup and error output, with no per-request records. The container runtime rotates it by size, under the per-service caps described there. It has no time-based expiry. A line survives until the rotation window displaces it.
```

**Reasoning**

```text
Hephaestus is contributor-facing. Account-bound data gives the data subject continuity of feedback while they participate. Hephaestus removes it when they leave or on a verified erasure request (Art. 5(1)(e) GDPR storage limitation). Authentication and settings-change history have time-based windows. Container stdout has a size limit, not an age limit.

The controller must assess their necessity and actual retention under Art. 32(1) GDPR and Art. 5(1)(c) and (e). These bounds alone do not prove proportionality.
```

**Deletion responsibility**

```text
The runtime automatically handles routine retention-driven deletion of logs and container stdout. The operations contact is the AET operations team, ls1.admin@in.tum.de.

Prof. Dr. Stephan Krusche, head of AET and responsible for this PA, is responsible for subject-rights deletion. An instance administrator executes it through Person data after receipt of a verified request through the TUM DPO at beauftragter@datenschutz.tum.de.

Privacy statement §7 describes the identity-verification procedure and Art. 12(3) GDPR response time limit. The time limit is one month. Complex or many requests can extend it by two further months.
```

**Access and portability fulfilment (Art. 15, Art. 20)**

```text
Hephaestus provides a self-service account data export:

1. A signed-in contributor requests an export from the in-app settings (account "Danger Zone").
2. The platform compiles a JSON archive of the personal data it holds about that contributor.
3. The contributor downloads it from the app.

The archive includes the Hephaestus account, federated identity links, workspace memberships, account preferences, and the contributor's own authentication-event history. It deliberately excludes credentials and session/signing-key material.

For a wider verified Art. 15 request, an instance administrator uses Person data:

1. Resolve an account or exact provider identities.
2. Preview the per-store scope.
3. Download one machine-readable JSON file.

The file includes conversations, observations (also invalidated), feedback and its delivery state (also never delivered), activity, memberships, profiles, preferences and collected Slack content. It excludes credentials and other people's profile data. The access and erasure contributors use the same frozen selections. A changed preview needs a replacement.

Original source-platform content and copies outside active application storage remain subject to the platform or operator's separate procedure. No layer of the stack writes an HTTP access log, so there are no HTTP access-log entries to disclose. Hephaestus holds IP addresses on authentication events, which the self-service export already covers.

The wider export includes selected locally mirrored source content. Original source-platform exports require separate requests to those platforms. Identity verification, response timeframe, and contact path are the same as for erasure.
```

**Person-request execution and evidence**

The [operations runbook](../production-operations-runbook#access-and-erasure-for-one-person) defines
identity verification, preview, JSON transfer, external-feedback removal, erasure and resume. The
job records the acting administrator and per-store counts. Each store step commits independently.
Retry skips completed steps. Completed receipts hold no erased content or subject keys.

Failed jobs
retain the keys needed to resume. Abandoned previews expire after 48 hours. Minimal exact provider
keys are held separately as processing-suppression controls, not as an audit record. They prevent
processing again even if provider sync mirrors an upstream record. Account erasure uses the same
account purger as self-service deletion. Self-service cooldown behavior is unchanged.

**Deletion guarantee**

```text
Hephaestus sign-in account and federated identity links

The in-app account-deletion control covers these. Deletion immediately revokes all sessions and marks the account for deletion with a 48-hour cooldown. After the window, a scheduled sweeper hard-deletes the account-bound rows:

- Identity links and feature flags.
- Session/revocation list and export artifacts.
- Product-feedback submissions and survey invitations, responses or declines.

It tombstones the account's contact PII and severs the link to the git-provider activity mirror. Workspace purge also removes workspace-scoped product-feedback submissions and survey responses.

Contributor profile and dependent records

Instance administration → Person data covers exact source-only identities as well as account holders. Each personal store has an implemented export and erasure contributor. The preview freezes row keys. Changed scope requires a new preview.

Erasure cancels affected attempts and waits for mounted evidence-store acknowledgement. It removes selected conversations and derived records. It anonymizes shared profiles and attribution. It clears affected hidden Heph runtime journals. An offline worker or failed step leaves a resumable request, not a completed erasure.

Other developers' observations and feedback stay when the person appeared only in their evidence. Erasure removes the captured input copies. Permanent exact-native-identity suppression prevents new product processing. Sync may still mirror upstream records. Git history, broker payloads, backups, delivered email and provider-side copies follow the separate boundaries in the personal-data map. These controls do not authorize extended evaluation retention.

Mirrored third-party content

Disconnection of the corresponding integration and workspace purge erase the same rows by hard deletion, not by a deleted-flag. The erased content is:

- GitHub / gitlab.lrz.de: the mirrored repository and everything that cascades from it. This includes issues, pull/merge requests, reviews, review threads and comments, discussions, labels, milestones, and collaborators. It also includes the workspace's repository monitors and any local git clone. The org-level mirror includes teams, team memberships and organization memberships. Removal also covers the activity-event log and SCM-derived practice observations and feedback, whose evidence quotes mirrored content verbatim. Repository rows are instance-global and shared. A repository that another workspace still monitors remains for that workspace. Only the disconnecting workspace's access path is removed. The org-level mirror is removed only when no other workspace is bound to the same organization.
- Slack: messages, threads, monitored-channel records, participant-consent records, mentor threads, and conversation-derived observations and feedback.
- Outline: documents, collections, and the document event log.

Retention after disconnection / purge

For all four integrations, operational sync history (`sync_job`, `connection_activity`, `connection_audit`) remains. It includes status, timestamps, operator attribution, progress and diagnostic details, so the disconnection itself remains auditable. These are personal-data stores, not anonymous history.

1. Verify the applicable caps and review triggers.
2. Include these stores in person requests.

The same transition clears connection credentials atomically. Disconnection does not touch cross-tenant identity rows (accounts, organizations, identity providers). The account-deletion and erasure-request paths above cover them.

Artifact-source copies

Source, workspace, and person erasure must traverse artifact-source manifests, repository snapshots, citation verdicts, derived assessments, exports, and governed evaluation cases. Expiry/erasure may leave only a non-content typed tombstone and makes replay unavailable. Audit reproducibility never overrides erasure.

Source-side content

Deletion in Hephaestus does not modify content on GitHub or gitlab.lrz.de.

Container stdout

The container runtime rotates startup and error output by size, under the per-service caps in "Where stored". It cannot selectively prune this output. An erasure request cannot reach inside the rotation window, which displaces the lines on its own.

Backups

The optional encrypted PostgreSQL backup overlay retains full chains by count, not by a legal age limit. Deployment activation, destination retention, VM snapshots and restore handling are operator decisions.

1. Record their expiry.
2. Prevent restored data from returning to service until completed erasures and suppression controls have been reapplied.
```

## Technical and organisational measures (Art. 30(1)(g) + Art. 32)

```text
Pseudonymisation and encryption (Art. 32(1)(a))

Traefik provides TLS termination with Let's Encrypt. HTTP redirects to HTTPS with HSTS. Internal service-to-service traffic stays within the Docker network. Outbound calls to GitHub, gitlab.lrz.de, the LLM provider, and Slack are HTTPS-only.

An operator can set a second display currency (`HEPHAESTUS_LLM_DISPLAY_CURRENCY`, unset by default). If set, the application server also makes one unauthenticated HTTPS GET each weekday for the European Central Bank's public daily reference-rate file. It sends no personal data and no request parameters. The ECB is therefore not a recipient of personal data under Art. 30(1)(d) and needs no separate legal basis. This record lists the call only to complete the outbound-call inventory.

Federated identity links to GitHub user ID / gitlab.lrz.de `sub` minimize collected identifiers. The application uses surrogate primary keys internally.

PostgreSQL data at rest relies on the host's filesystem and access-control protections. The general store has no application-level at-rest encryption. Two platform-level keys seal secrets at rest with AES-256-GCM:

- `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` protects workspace LLM API keys, login-provider OAuth client secrets, Outline webhook signing secrets, sandbox job tokens and JWT signing keys.
- `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY` protects integration connection credentials. These include GitHub and GitLab tokens, Slack bot tokens, Outline API keys, and their OAuth access and refresh tokens. This key is versioned and supports online rotation.

Compromise of the first key exposes workspace secrets and session-token signing. Compromise of the second exposes every connected provider's credential. Key injection is deployment-specific and requires verification against the active role.

In the shipped Compose stack, neither key is confined to the application-server container. Both enter every container that reads an encrypted column as container environment variables:

- `application-server`.
- `application-worker`: the practice-review worker decrypts the workspace's LLM-provider credential to serve its in-process LLM proxy.
- `webhook-server`: verifies inbound Slack and Outline deliveries against the per-connection secret on the encrypted credential row.

All three containers are in scope for key-compromise assessment. The application persists neither key to disk and writes neither to any log.

Confidentiality (Art. 32(1)(b))

Host access uses SSH keys only. Password authentication is disabled. End-user access uses Hephaestus-native auth that federates to GitHub OAuth + gitlab.lrz.de OIDC. Short-lived ES256 cookie-session JWTs have server-side revocation (ADR 0017).

The application layer enforces workspace-scoped membership and role checks through `@PreAuthorize` and dedicated workspace-membership filters. Per-workspace GitHub App installation or a scoped access token provides least-privilege source-system access. The reverse proxy exposes only required routes. Everything else returns 404.

The practice-review sandbox runs as non-root inside isolated Docker containers on per-job `--internal` networks, with no DNS and no general egress. Its reachable service is a per-attempt, token-authenticated worker gateway for LLM calls, declared workspace transfers and result upload. Sandbox execution is always workspace opt-in. A container runs only for a workspace with an enabled model binding for the purpose concerned. The two purposes have different gates. Neither is on for a workspace with no bindings.

Queued practice review also requires the operator to set `AGENT_ENABLED=true`. The deployment's compose files pass that variable to both the application server and worker, with a `false` default. No runtime profile overrides it. A worker started outside those compose files, on the `worker` Spring profile, is also off until the variable is set. It gates the job queue. With it unset, the system submits, claims and executes no practice-review jobs.

Interactive mentor conversations do **not** use that queue, so `AGENT_ENABLED` does not gate them. Web and Slack turns place their sandbox on a connected worker with spare mentor capacity. The application server keeps admission, budget checks, context and thread persistence. It needs no Docker client when its worker role is disabled. A worker drain or restart ends its live sessions. The next turn restores the thread from PostgreSQL.

Without spare capacity, the system returns an explicit, retryable busy response, rather than executing locally. Heph has a separate allow-internet binding. Practice reviews enforce an internal network regardless of the stored binding. Heph can have broader connectivity.

Before enabling that network posture:

1. Approve it.
2. Document it.

The system produces audit records for high-value writes: workspace creation, role assignment, LLM-provider credential changes and account deletion.

Integrity (Art. 32(1)(b))

Git is the authoritative source of all application code, with signed commits and PR review. The release workflow builds every production image. It signs each with cosign (Sigstore keyless) and attests provenance through `actions/attest-build-provenance`. Any deployed image can therefore be verified against this repository's release run.

Every image that the compose files run has a **sha256 digest** reference from a cosign-verified release lock rendered at deploy time. This includes the platform images: application server, worker, webhook receiver, webapp, PostgreSQL and `agent-pi` sandbox image. It also includes third-party infrastructure images: Traefik, NATS and nginx. A compose project started without the lock refuses to start.

Only the `agent-pi` image executes contributor repository content. The application also checks it at startup. `AgentImagePinGuard` refuses to boot unless its reference is digest-pinned. [Release image lock](../release-image-lock.md) gives the verification recipe.

Availability and resilience (Art. 32(1)(b))

- Containers restart on failure (`restart: unless-stopped`). Each service has health checks.
- Each container has resource limits. Each job has sandbox concurrency / CPU / memory ceilings.
- LLM-call timeouts are bounded. The system posts no feedback when an LLM provider is unreachable.
- Unauthenticated endpoints have ingress rate limits.
- Let's Encrypt ACME automatically renews TLS certificates.

Recovery (Art. 32(1)(c))

An optional encrypted pgBackRest off-host backup overlay ships in docker/self-host/compose.backup.yaml. Its existence does not prove that the TUM deployment enables it. Before relying on recovery, record the actual destination, chain-count policy, effective age limit, access permissions, restore-test result and erased-data handling.

The PostgreSQL container uses a named Docker volume on the host. GitHub and gitlab.lrz.de hold the authoritative copies of pull/merge-request content. Loss of the host-local PostgreSQL volume would lose Hephaestus-specific state: workspace state, observations and practice configurations. This file does not record acceptance of that risk.

Testing and evaluation (Art. 32(1)(d))

CI runs repository-specific Semgrep checks, Trivy (filesystem and container image), TruffleHog secret detection, and Renovate dependency updates. CI selects unit, integration and browser suites under the repository verification contract. The personal-data map links focused export, erasure and tenancy tests.

Organisational

Operators are TUM / AET employees or authorized contributors who act under TUM-internal security policies. Before workspace provisioning, workspace administrators receive a briefing on the joint-controller / shared-responsibility model (privacy §10).
```

## Legal basis (Art. 6 GDPR + national norms)

Do **not** select 6.1f. Bavarian public bodies cannot rely on legitimate interest for tasks that perform a statutory public duty (Art. 6(1) Unterabsatz 2 GDPR).

In DSMS:

1. Select Art. 6.1a GDPR (consent) for workspaces that collect explicit consent (e.g., the AET capstone course).
2. Select Art. 6.1a GDPR (consent) for optional academic-research participation requested at first login.
3. Select Art. 6.1b GDPR (contract / service request) for voluntary sign-in by non-TUM contributors.
4. Select Art. 6.1e GDPR (public task) for TUM/AET operation of the platform.
5. In the national multi-select, select `Art. 4.1 BayDSG (Bavarian data protection act)`.

```text
TUM/AET as platform operator: Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG (Allgemeine Aufgaben der Hochschule) and Art. 4(1) BayDSG.

Per-workspace lawful basis:

1. Confirm the actual parties for the existing TUM Art. 26 arrangement.
2. Confirm its reference.

An authorized administrator within one controller is not a separate controller because of their role. The administrator invokes the basis that applies to their workspace's contributors. Typically, this is Art. 6(1)(a) GDPR (consent, e.g. the AET capstone course's application phase).

Alternatively, it is Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG for public-task activity by a TUM unit. Examples include regular courses or public open-source repositories such as ls1intum/Artemis.

Administrators outside TUM cannot invoke Art. 6(1)(e) BayHIG. They invoke a basis available to them. Typically, this is Art. 6(1)(a) consent, or Art. 6(1)(f) for private bodies under their own LIA.

Voluntary sign-in by non-TUM contributors to use personal features: Art. 6(1)(b) GDPR.

Optional academic-research participation: Art. 6(1)(a) GDPR. It is separate from the terms and from the public-task basis for platform operation. Research enrollment and analysis require the latest `RESEARCH_PARTICIPATION` decision to be a grant for the current notice version. After withdrawal, they do not fall back to a preference flag or another legal basis.

The append-only ledger records grants, refusals and withdrawals with a UTC timestamp, mechanism and notice version. The version identifies the first-layer wording: the first-login screen as published in that signed release, immutable in git. The screen links to operator-specific detail in the privacy notice at `/privacy`. The deployment versions that notice, rather than this ledger. Withdrawal immediately ends authorization for further research processing.

Account erasure removes the ledger's account reference. The resulting non-account-linked event remains, with its notice version, as evidence of how the system managed consent.

Product feedback and product-purpose surveys improve the TUM-operated instance under Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG and Art. 4(1) BayDSG. Responses are not reused for research.

Research-purpose surveys: Art. 6(1)(a) GDPR under the research participation above. Such a survey reaches only accounts whose latest research decision is a grant for the organization it names. The screen labels it as research.

Its answers are that study's data, rather than product feedback. They stay in the same database. Instance administrators who read them act on behalf of the study.

Withdrawal does not automatically delete stored answers. The study must apply withdrawal to further consent-based processing. It must review retention and erasure under its documented lawful basis. Continued storage is not permission to continue research use.

The Hephaestus session cookie (`__Host-HEPHAESTUS_AT`), the CSRF + OAuth-state cookies, and theme-preference localStorage use this basis:
§ 25 Abs. 2 Nr. 2 TDDDG (technisch unbedingt erforderlich) i.V.m. Art. 6(1)(e) GDPR.
```

## Source of data

In the DSMS multi-select:

1. Select `Data received from third parties`.
2. Select `Directly from the data subject`.

```text
- GitHub and gitlab.lrz.de supply identity at sign-in through GitHub OAuth and gitlab.lrz.de OIDC. The application server federates directly to them. They supply repository activity through the GitHub App installation or workspace-configured access token. They deliver webhook events to the platform's /webhooks endpoint.
- A connected Slack workspace supplies linked identity, App Home choices and mentor direct messages. It also supplies new messages from explicitly activated monitored channels after the visible announcement.
- A connected Outline workspace supplies documents and author attribution from collections that the workspace administrator explicitly selects. When a contributor connects Outline, it can also supply linked identity.
- Data subjects directly supply account preferences, AI-assistant messages, product feedback, survey answers, and rights requests through the contact process.
- The shipped production stack has no general access log for the HTTP connection. Authentication and security processing still use connection metadata. Only authentication events collect the source IP address and user agent as such. These events retain them under the auth-event log's own 12-month window described above.
```

## Information duty (Arts. 13 and 14)

- https://hephaestus.build/privacy
- https://hephaestus.build/imprint

Markdown source under `webapp/public/legal/profiles/tumaet/`.

## Other Remarks (DSMS form vendor-pool comment)

```text
Bitte folgende Auftragsverarbeiter zum AET-Pool hinzufügen, soweit noch nicht vorhanden: GitHub Inc. (USA), Microsoft Corp. (Azure OpenAI Service, USA/EU), OpenAI OpCo, LLC (USA) ggf. mit OpenAI Ireland Ltd. (Irland) als EWR-Vertragspartner, Salesforce / Slack Technologies, LLC (USA). Beschreibungen unter "Recipient Categories"; Drittlandtransfers durch das EU–US Data Privacy Framework und Standardvertragsklauseln Modul 2 (jeweils im Rahmen des einschlägigen Enterprise-AVV) abgedeckt; DPF-Status pro Empfänger vor Anbindung verifizieren.
```

### Git history during person erasure

Raw Git objects in repository mirrors and short-lived job folders are an upstream cache controlled
by the workspace's organization. Hephaestus erases what it derives and records about the person and
cancels affected in-flight attempts before their evidence folders are removed. It does not rewrite
Git history. The repository owner must remove Git authorship at the source. A later fetch can mirror
any records that remain there. Person exports list repositories whose history may contain the
person's commits, selected through exact native identity references, never names or email addresses.

## Current source scope and controls

Automated practice review can read all permitted workspace areas and repositories in its job folder. This includes Slack threads and person-scoped observation and feedback history. There are no record-count or history-window caps. The source-use registry version 1.3.0 records maintainer engineering approval,
not controller/DPO approval. The [source-governance record](./artifact-source-governance.md) owns the
scope and approval boundary. These categories belong in the full DPIA and the pending notice review.

An account's **No AI**, **In-house** or **Cloud** choice applies across the instance's workspaces to
practice review and Heph, including Slack. Model eligibility also depends on workspace policy and
the declared operator. No AI stops new reviews about that developer, Heph requests and feedback delivery. It does not stop source sync or requests already sent. Person-scoped history is checked against the person's choice, but shared repository content can remain in other developers' review context.

This product choice is separate from research consent and is not the legal basis for underlying processing.
Slack App Home reflects mentor eligibility and keeps channel-message controls available when Heph
is refused. Linking Slack alone grants neither research participation nor unrestricted AI use.

Generated-path patterns mark retained changed-file evidence. They do not redact it. Bot-authored
work and bot reviewers receive separate review refusals, not person erasure. An unavailable GitHub
or GitLab repository retains its monitor and stored work. Two unavailable responses cause daily
rechecks, and **Sync now** checks immediately.

Permission loss or a 404 is not proof of deletion.
Stopping the last monitor or verified erasure provides the relevant removal path.

GitLab note reconciliation removes a missing note from live context only after a complete parent
listing and direct absence confirmation. Partial listings and failed confirmation keep the mirror.
Historical captured review inputs are unchanged by reconciliation. Person erasure and retention
handle those copies.

Hephaestus checks GitLab tokens daily. The tokens can rotate or be replaced. Rotation revokes the old token. Source permission and credential recovery are not rights-request fulfilment.

Practice profiles and their feedback text are private to the developer. Workspace admins can read
observations, delivery metadata and dispute explanations, but not practice-page or conversation
feedback text through ordinary administration. Ratings and other private response notes stay private.
Instance-admin **View as user** is a separate read-only, reasoned and audited access path. External
feedback follows the destination's audience. A public workspace exposes its directory and practices,
not Activity, practice profiles, feedback or conversations.

Disputes hold back the same point in later reviews while they stand. Withdrawal of the dispute
allows it again, with the committed-secret exception. Admin withdrawal preserves feedback and its
history, removes it from future review/Heph context, and is reversible. Already sent conversations
and inline provider comments are not recalled. This is correction and delivery control, not erasure.

## Retention decisions that need deployment evidence

Slack channel retention defaults to 30 days, configurable per connection with a 180-day ceiling.
Eligibility is measured from a thread's last message, so an active thread can contain much older
messages. This is not a maximum age for each message and does not apply to mentor DMs. Daily cleanup
and failures can delay removal. Contributor profiles, conversations, observations, feedback, consent records, and several operational stores have no independent time-based expiry.

1. Choose a necessity review and deletion trigger.
2. Document them.

Do not call indefinite storage a retention period.

Worker attempt folders are removed after evidence admission. Ended attempts have a one-hour grace,
and restart cleanup removes abandoned folders. Removal failures need a later successful cleanup.

There is no shipped 30-day replay/blob retention guarantee. Repository mirrors cache upstream history
while monitored. They are not rewritten for person erasure. Offline mounted stores must acknowledge
erasure before the person job completes. See the personal-data map for executable evidence.

The person-data upgrade clears unresolvable legacy administrator attribution, connection-audit
actor/detail fields, membership subject attribution and hidden Heph journals once. Visible chats,
settings and lifecycle times remain. Pending integration authorizations must be restarted. A legacy
Outline mirror lacking an exact provider instance stops the upgrade. The shipped migration fragment
owns the operator backup and recovery procedure. This clearing is not a general retention schedule.

## Deployment security evidence

Sandboxes run on the worker role only. Web and Slack Heph use connected workers. The server owns
admission, context and persistence without a Docker socket. Worker memory and mounted copies are
part of the privacy boundary. Management metrics are on a separate private listener, not the public
API.

Metrics retention and scrape access are operator decisions. Tracing export is off by default.

If enabled, spans can carry workspace/run identifiers. Before enabling it, approve its recipient and retention.

Browser Sentry requires a configured endpoint and the person's error-monitoring consent. Server Sentry initializes when its DSN is nonblank (except the specs profile) and does not consult browser consent. It disables default PII, strips user/request/breadcrumb fields and can retain a trace-correlation tag. The operator must establish a separate lawful basis for server diagnostics. The operator must disclose that basis.

The client removes request, user and breadcrumb fields and disables content collection. Error text and
stack traces can still contain incidental personal data. Record the actual Sentry operator, region,
retention and contract before enabling it. Consent withdrawal stops new reports, not stored reports.

1. Regularly review access control, encryption-key custody and rotation, restore tests, cleanup failures and incident handling.
2. Establish the Art. 33 authority-notification procedure where required (within 72 hours of awareness).
3. Establish Art. 34 communication for high-risk breaches.

These are operator procedures. A CI security scan does not prove they exist.
