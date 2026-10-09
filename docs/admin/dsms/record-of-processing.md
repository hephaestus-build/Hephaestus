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

Hephaestus analyzes the synced activity against practices that the workspace administrator configures. It produces observations about each contributor's activity. Some judgments require the analysis to read and understand natural-language text. Examples include the meaning of a code comment or the substance of a review reply. For those judgments, the analysis uses an eligible model configured for the workspace, selected by the reviewed developer's AI choice (see the recipient details below).

Automated practice review forwards the relevant pull/merge-request diff or issue content and the surrounding discussion to the provider. For a pull/merge request, it also forwards whatever the review reads from the captured repository and its history. It can post the resulting AI-generated feedback as comments on the reviewed artifact. Where explicitly connected and permitted, selected Outline documents may supply project context.

The conversational mentor is an in-app chat where contributors can ask follow-up questions. Hephaestus forwards their messages and the approved bounded context to an eligible model configured for Heph, selected by the AI choice of the person who asks (see the recipient details below). This context can come from repository activity, prior feedback, selected Outline documents, or participant-permitted monitored Slack channels. Sources are purpose- and audience-bound. An enabled integration does not by itself authorize all of its content for every request.

Contributors who sign in with their GitHub or LRZ-GitLab account get a personal dashboard that summarizes their observations and activity. They get access to the conversational mentor and their account preferences. Sign-in adds the federated user identifier, username, display name, email, and avatar URL to what Hephaestus holds about that contributor.

Everyone with a role in the workspace also sees Activity. Activity shows counts and lists of pull/merge requests, reviews, issues and comments that members did in the workspace's repositories. It also shows each member's open work (review requests, assigned issues), with each reviewer's review state. Members see this for themselves and for the workspace. Workspace activity can sort people by each count. The default sort is Contributions: pull/merge requests opened, pull/merge requests reviewed (each once, never one's own) and issues opened, with no weights. A sorted table shows a position number (ADR 0052). Activity never shows a score, practices or standings.

A workspace administrator can publish a public activity page when the instance allows it. Anyone can read it without sign-in. It shows the counts and links of the work in the workspace's public repositories, for members and outside contributors. It never shows practices, feedback, observations, Slack, Outline or AI review content. Each person can hide at once with Show me on public activity pages. A hidden person leaves every total. A person without an account can object through the privacy contact. The maintainer reassessed this audience on 2026-10-09. The TUM data-protection coordinator confirms it before the first TUM workspace goes public (`dpia-prescreen.md` § 6).

Members also see Practices across the workspace (ADR 0051). It counts how many developers are at each practice standing in each practice group and practice. It also shows the middle half of the reviewed work, the practices going well, the practices needing attention and the open feedback of the developers counted. It names nobody and has no smallest count. Thus, a small count can tell the members of a workspace the standing of one developer. Hidden members are not counted. The maintainer reassessed this audience on 2026-10-07 with a low residual risk. The full DPIA confirms it (`dpia-prescreen.md` § 6).

Team memberships sync from GitHub teams and gitlab.lrz.de subgroups. They let members narrow Workspace activity to one team. On GitHub, they show a member the review requests addressed to one of their teams. Activity does not check a viewer's own permissions in the source system. Workspace administrators can hide a member from the workspace view. They can also enable Slack integration for App Home privacy controls, mentor DMs, and explicitly activated monitored channels.

Signed-in contributors can send product feedback. They can answer or decline surveys that instance administrators author. These submissions stay in the instance database. Instance administrators can read them for product improvement. They are not reused for research. Research-purpose surveys are covered under *Legal basis* below.

Research use is optional and rests on consent. A participant who says yes allows TUM (AET) and the researchers who work for it to use their data for research. The area of research is how developers work and learn, and how AI systems can review and support that work. It includes building and running benchmarks and evaluation datasets for such AI systems. Only research in this area is covered.

The research uses the participant's work in connected repositories and tools, Hephaestus observations and practice feedback about that work, and the participant's responses to that feedback. It also uses how the participant uses Hephaestus, including conversations with Heph and research survey answers. Sign-in credentials and access tokens are never used.

The participant can refuse or withdraw at any time. Refusing or withdrawing has no disadvantage, and practice reviews continue. The consent is separate from the terms of use and is not a condition of service. The research is covered in more detail under *Legal basis* below.

TUM is the controller. Workspace administrators make these workspace-level choices under TUM's operating responsibility; an administrator role does not establish separate or joint controllership. The choices are listed in "Legal basis" below. Hephaestus focuses on the contributor's own development. Observations serve the contributor and let the workspace administrator deliver targeted feedback during the project.

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

For research participants only, the research uses these categories in pseudonymized form:

- The participant's work in connected repositories and tools: pull/merge requests, issues, reviews, comments and chat.
- Hephaestus observations and practice feedback about that work.
- The participant's responses to that feedback.
- How the participant uses Hephaestus, including conversations with Heph and research survey answers.

Sign-in credentials and access tokens are never used. A participant who stops Hephaestus from using their Slack messages has those messages erased from the instance database. The research team removes them from datasets that are not yet anonymized, and research does not use them again.
```

Hephaestus does not intentionally solicit or classify special-category data (Art. 9(1) GDPR) or criminal-offence data (Art. 10 GDPR). Because repository and chat fields contain free text, incidental content may include and therefore cause processing of them. The privacy statement instructs users not to enter third-party personal or sensitive data.

## Recipients (Art. 30(1)(d))

```text
TUM/AET engages external processors as controller, each under an agreement verified for that recipient (see processor-checklist.md). A configured model endpoint or key alone does not establish an agreement or a role for another institution. See the model recipient details below.

- GitHub, Inc. (USA) is the identity provider (OAuth) and source-system API for connected repositories on github.com.

- Each workspace configures its models from OpenAI-API-compatible HTTPS endpoints. A base URL, an API token, and a model name configure an endpoint. This configuration is under TUM's controller responsibility; choosing an endpoint or key does not make an administrator a joint controller. Each practice review uses the AI choice of the reviewed developer, and Heph uses the choice of the person who asks, to select among the compatible configured models. An in-house model, for example one served through Logos, stays with its in-house operator; a model that Logos forwards to an external provider makes that provider the recipient. The [processor checklist](./processor-checklist.md) holds the exact-provider evidence.

- When the workspace enables Slack, Salesforce, Inc. / Slack Technologies, LLC (USA) provides Slack app delivery and identity linking. It also provides App Home privacy controls, DM mentor messages, and monitored-channel event delivery.

- The operator of the exact Outline origin connected to a workspace supplies selected documents and optional OAuth identity. Hephaestus has no default Outline vendor or origin. The integration remains disabled until the deployment record classifies that operator. The classification must identify controller-owned infrastructure, an Art. 28 processor, or a separate controller. The record must include its region, transfer basis, retention terms, and AVV where required.

Research recipients (only for participants with a current research grant):

- The research team of the research organization, for this deployment TUM (AET), works with the research data. Only this team and its processors work with pseudonymized data.

- AI model providers act as processors when the research team runs benchmarks with AI models. They receive only pseudonymized data, under an Art. 28 agreement. The participant's AI choice (No AI, In-house or Cloud) applies to these runs.

- The public and other researchers receive only anonymized datasets. A dataset that the team cannot anonymize stays inside the team.

The public, only for a workspace that publishes its public activity page:

- Anyone can read the counts and links of the work in the workspace's public repositories, for each person who has not hidden. Search engines get noindex unless an administrator allows them.

Separate controller (not an Art. 28 processor):

- Leibniz-Rechenzentrum (LRZ) der BAdW operates gitlab.lrz.de. The platform receives the contributor's identity from gitlab.lrz.de OIDC and syncs connected gitlab.lrz.de repositories. Inter-public-body transmission falls under Art. 5(1) Nr. 1 BayDSG.
```

When workspace addresses are on, Cloudflare, Inc. (USA) proxies the workspace hosts, not `hephaestus.build` itself. It processes the IP address, the request metadata and the static web app of each workspace host. Sign-in, session cookies and API content do not pass it. It is not engaged yet. The [processor checklist](./processor-checklist.md) records it, and this section lists it, before the proxy carries traffic.

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

The EU-US Data Privacy Framework covers U.S. recipients where the recipient is on the active DPF list (Commission Implementing Decision (EU) 2023/1795). Standard Contractual Clauses Module 2 provide the fall-back (Commission Implementing Decision (EU) 2021/914). For an external model provider, verify the processing location and transfer basis of the exact recipient and deployment in the [processor checklist](./processor-checklist.md) before engagement. An Outline origin outside the EEA cannot be enabled until its transfer basis is recorded in this section.

A public release of an anonymized dataset is not a transfer of personal data, because anonymous data is outside the GDPR (Recital 26). By the consent wording, a dataset leaves the research team only if it is anonymized.

A separate choice and a new notice version are needed in three cases. The release contains data that is not anonymized. Or it serves a purpose outside the stated area. Or it goes to a recipient in a third country without adequate safeguards.

AI model providers that run benchmarks on pseudonymized data are processors. They follow the [processor checklist](./processor-checklist.md).

## Storage location and retention (Art. 30(1)(f))

**Where stored**

```text
AET hosts Hephaestus at https://hephaestus.build on AET-administered infrastructure at TUM. PostgreSQL holds application data and authentication state. Authentication state includes accounts, federated identity links, the cookie-session revocation list, and the auth-event log. PostgreSQL also holds the practice-review job queue. NATS JetStream holds webhook and integration-sync events.

When practice-review code execution is enabled, the host filesystem may store local working copies of monitored repositories. Container stdout goes to the Docker json-file driver. The compose files set explicit rotation caps for every service:

- 50 MiB per file × 5 files: webapp, application server, worker and PostgreSQL.
- 10 MiB × 3: webhook receiver, NATS, reverse proxy and maintenance page.

No layer of the stack writes an HTTP access log. The production profile explicitly disables Tomcat's access log. The Traefik reverse proxy starts without `--accesslog` (Traefik's default is off). Both nginx containers (static frontend and maintenance page) disable the access log at the server level. The shipped stack creates no general HTTP access-log copy. Authentication and security events still record connection metadata.

Application and authentication data reside on TUM infrastructure within the EU. AI-assisted features also forward code snippets and surrounding discussion to the configured model that the AI choice selects. Its location depends on the recipient: an in-house model or an external provider, as recorded in the processor checklist.
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

Public activity page

The page holds no copy. Each request computes it from the activity records above. HTTP caches can keep a response for up to 60 seconds. A person who hides leaves the page at once. Copies that third parties made before cannot be recalled.

Research data

The research team holds research copies outside the instance database, at `[location]`. They are pseudonymized copies of the data that the participant allowed. The key that links codes to people is stored apart from them.

The team keeps pseudonymized research data and the key while the research that they support continues. It then deletes or anonymizes them. For the TUM deployment, the public notice (§3) states the retention criterion; no fixed period applies. Self-hosters record `[retention period or criterion of the research organization]`.

Anonymized datasets are outside the GDPR. They can remain and can be published with no end date. They cannot be removed.

After withdrawal, the team removes the account's data from datasets that are not yet anonymized without undue delay, as a manual step. Consent ledger rows are append-only and unchanged by the research use. See "Legal basis" for the ledger.

Retired leaderboard values (league points, XP)

Installation of the release that retired the leaderboard deletes these values from the database. An operator may keep an exported copy outside the application. That copy follows the operator's own retention.

Active mirror removal

Removal on disconnect / purge applies storage limitation (Art. 5(1)(e) GDPR). The integration is the sole purpose for which Hephaestus holds the mirror. Once the integration is severed, there is no basis to retain that copy. This workspace-administrator action is **not** the fulfilment path for a data subject's erasure request under Art. 17.

That request uses the instance-admin Person data process under "Deletion responsibility" below. This process also covers account-bound rows that no single workspace owns.

LLM-provider-side prompts

The recipient's terms govern retention. For an external provider, the [processor checklist](./processor-checklist.md) records the prompt and response retention, training terms and human access of the exact deployment. For an in-house model, its operator's retention applies.

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

The controller must also record why each research retention period is necessary for the research that it supports (Art. 5(1)(e) and Art. 89 GDPR). The TUM period above is a proposal.
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

1. A signed-in contributor requests an export from the in-app settings (account "Danger zone").
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

Research copies

The research team holds copies outside the instance database. Account erasure and workspace purge in Hephaestus do not reach them. Before any export or analysis, the operator applies the latest `RESEARCH_PARTICIPATION` decision of each account.

After a withdrawal or a verified erasure request, the operator removes the account's data from research datasets that are not yet anonymized. Anonymized datasets cannot be reached and cannot be traced back to the person.

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

Research safeguards (Art. 89(1) GDPR)

- Before analysis, name, username and contact details are replaced by a code (pseudonymization).
- The key is stored apart from the data. Only the research team can reach it.
- Free text can still name people. Researchers screen for names and remove what they find.
- The research team does not look for special-category data (Art. 9) and removes any that it finds. Data minimization applies.
- Access to research data is limited to the research team.
- A dataset or benchmark leaves the research team only if it is anonymized.
- A re-identification test must pass before a dataset is called anonymous. See the DPIA pre-screen.
- Benchmark runs with AI models use only pseudonymized data, under an Art. 28 agreement. The participant's AI choice applies.

Organisational

Operators are TUM / AET employees or authorized contributors who act under TUM-internal security policies. Before workspace provisioning, workspace administrators receive a briefing on the workspace configuration and responsibilities (privacy §10).
```

## Legal basis (Art. 6 GDPR + national norms)

Do **not** select 6.1f. Bavarian public bodies cannot rely on legitimate interest for tasks that perform a statutory public duty (Art. 6(1) Unterabsatz 2 GDPR).

In DSMS:

1. Select Art. 6.1a GDPR (consent) for workspaces that collect explicit consent (e.g., the AET capstone course).
2. Select Art. 6.1a GDPR (consent) for optional research participation, a broad consent to an area of research (Recital 33 GDPR). Hephaestus asks for it at first login and offers it in User settings.
3. Select Art. 6.1b GDPR (contract / service request) for voluntary sign-in by non-TUM contributors.
4. Select Art. 6.1e GDPR (public task) for TUM/AET operation of the platform.
5. In the national multi-select, select `Art. 4.1 BayDSG (Bavarian data protection act)`.

```text
TUM/AET as platform operator: Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG (Allgemeine Aufgaben der Hochschule) and Art. 4(1) BayDSG.

Per-workspace processing: every workspace on the TUM deployment uses the TUM basis above. Workspace administrators make configuration choices under TUM's operating responsibility; their role does not make them a separate or joint controller. The operators of the source platforms process the original work under their own legal bases.

Voluntary sign-in by non-TUM contributors to use personal features: Art. 6(1)(b) GDPR.

Optional research participation: Art. 6(1)(a) GDPR. It is a broad consent to an area of research (Recital 33 GDPR). The area is how developers work and learn, and how AI systems can review and support that work. It includes building and running benchmarks and evaluation datasets for such AI systems. The research organization is the controller of this research. For this deployment, it is TUM (AET).

The consent is separate from the terms and from the public-task basis for platform operation. It is voluntary and is not a condition of service (Art. 7(4) GDPR). Refusing or withdrawing has no disadvantage. The participant gets the same access, features and feedback.

Research enrollment and analysis require the latest `RESEARCH_PARTICIPATION` decision to be a grant. The grant must be for the current notice version and for the research organization that the question named. After withdrawal, they do not fall back to a preference flag or another legal basis. A decision on an earlier notice version authorizes nothing.

An earlier yes to narrower wording does not carry over to the broader scope. A new notice version is a new question. Every account answers once more, so every account sees one more setup screen.

The append-only ledger records grants, refusals and withdrawals with a UTC timestamp, mechanism, notice version and research organization (`consent_decision.research_organization`). The notice version names the wording. It points at the signed release that published the words, which is immutable in git. The ledger stores the version only, with no copy or digest of the text.

The screen links to operator-specific detail in the privacy notice at `/privacy`. The deployment versions that notice, rather than this ledger.

Withdrawal (Art. 7(3) GDPR) is one switch in User settings. It is as easy as giving consent. Withdrawal immediately ends authorization for further research processing, and research survey invitations stop. Ordinary practice reviews continue.

On withdrawal, the research team removes the account's data from datasets that are not yet anonymized without undue delay, as a manual step. Anonymized data in a published result or dataset cannot be traced back and cannot be removed. Research done before withdrawal stays lawful.

Account erasure removes the ledger's account reference. The resulting non-account-linked event remains, with its notice version, as evidence of how the system managed consent.

Product feedback and product-purpose surveys improve the TUM-operated instance under Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG and Art. 4(1) BayDSG. Responses are not reused for research.

Public activity page: the publication is a transfer to the public under Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG and Art. 5(1) Satz 1 Nr. 1 BayDSG. It must be necessary for the task of an open-source project in teaching and research. Each person can object under Art. 21 GDPR with the switch Show me on public activity pages, which stops the publication at once, or through the privacy contact. Course workspaces stay private.

Research-purpose surveys: Art. 6(1)(a) GDPR under the research participation above. Such a survey reaches only accounts whose latest research decision is a grant for the current notice version and for the organization it names. The screen labels it as research.

Its answers are research data, rather than product feedback. They stay in the same database. Instance administrators who read them act on behalf of the research.

Withdrawal does not automatically delete stored survey answers in the instance database. The research team removes them from datasets that are not yet anonymized and applies withdrawal to further consent-based processing. It must review retention and erasure under its documented lawful basis. Continued storage is not permission to continue research use.

The Hephaestus session cookie (`__Host-HEPHAESTUS_AT`), the CSRF + OAuth-state cookies, and theme-preference localStorage use this basis:
§ 25 Abs. 2 Nr. 2 TDDDG (technisch unbedingt erforderlich) i.V.m. Art. 6(1)(e) GDPR.
```

## Research use under broad consent

The first-login screen and User settings ask the research question. `ConsentService.WORDING_VERSION` names the wording that an account saw. The wording source is `webapp/src/components/auth/consent-wording.tsx`. The research organization comes from `HEPHAESTUS_RESEARCH_ORGANIZATION`. The controller of the research is that organization. For the TUM deployment, it is TUM (AET).

The [DPIA pre-screen](./dpia-prescreen.md) records why sharing or publishing a dataset has no second consent switch. The [personal-data map](./personal-data-map.md) records where research copies live and how withdrawal reaches them.

Self-hosters use their own research organization. Replace each bracketed value with the facts of that organization:

- `[research organization name]`.
- `[retention period or criterion of the research organization]`.
- `[ethics approval reference, where a vote is required]`.
- `[research information page URL]`.
- `[privacy contact]`.
- `[removal procedure]`.

### Controls that the controller must evidence

A broad purpose needs compensating transparency and safeguards (EDPB Guidelines 05/2020 paras 161-162, EDPB Guidelines 1/2026 paras 48-49). The [research prerequisites](../legal-pages.mdx#what-operators-must-do) are the operator list. This section records the TUM facts. The controller must evidence each of these controls before research use starts:

1. Keep a research information page at `[research information page URL]`. It lists current research projects and released datasets. The TUM public notice currently gives the research group contact for this information.
2. Assess whether the research needs an ethics committee vote, and get it where the institution or the study requires it. TUM has not sought or obtained an ethics vote for this research.
3. Restrict access to research data to the research team.
4. Give `[privacy contact]` as the contact for questions about the research.

### Power imbalance

Students and employees can feel pressure to say yes. Consent is valid only with a real refusal path and no disadvantage (Recital 43 GDPR, EDPB Guidelines 05/2020 paras 16-24 and 46-48). The two answers on the screen have equal weight.

The research team must be separate from grading and line management. If doubt remains that consent is freely given, the controller must not rely on consent (EDPB Guidelines 1/2026 para 36).

### Approval state

The public notice states the research retention criterion and the manual removal step. Their publication does not record a DPO or institutional approval of this section.

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

For the public activity page, three more layers inform people:

1. A workspace onboarding step opens at a signed-in member's first visit to a workspace with a public activity page. It says that the page is public and what it shows. It offers Show me and Hide me with equal weight.
2. The public page says what it shows and how to hide.
3. For outside contributors with no contact data, the privacy notice makes the information public (Art. 14(5)(b) GDPR).

The [notice change list](./tum-privacy-notice-changes.md) gives the changes to the public notice for the legal review.

## Other Remarks (DSMS form vendor-pool comment)

```text
Bitte folgende Auftragsverarbeiter zum AET-Pool hinzufügen, soweit noch nicht vorhanden: GitHub Inc. (USA), Salesforce / Slack Technologies, LLC (USA) sowie jeden externen Modellanbieter, der tatsächlich angebunden wird, erst nach Prüfung des konkreten Empfängers in der Processor-Checklist. Beschreibungen unter "Recipient Categories"; Drittlandtransfers nur auf Grundlage des EU–US Data Privacy Framework oder von Standardvertragsklauseln, jeweils pro Empfänger vor Anbindung verifiziert.
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
The choice also applies to benchmark runs that use AI models on research data.
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

Other members see standings only in the counts of Practices across the workspace, which can identify
one developer when a count is small.

Practice profiles and their feedback text are private to the developer. Workspace admins can read
observations, delivery metadata and dispute explanations, but not practice-page or conversation
feedback text through ordinary administration. Ratings and other private response notes stay private.
Instance-admin **View as user** is a separate read-only, reasoned and audited access path. External
feedback follows the destination's audience.

Every workspace page needs sign-in. The opt-out public activity page described above is the only
public view of a workspace. It replaces an older public flag that a workspace admin can set only
through the API. With that flag, anonymous API reads see the workspace's members, teams, repositories
and practices, never Activity, practice profiles, feedback or conversations. ADR 0052 removes the flag
before the first public activity page goes live.

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
Research data has its own retention decision under "Research data" in the retention block above.

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
