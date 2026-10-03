# Hephaestus — Record of Processing Activities (Art. 30 GDPR)

This file is the Art. 30 record for the TUM-operated Hephaestus deployment at https://hephaestus.build. Each section maps to a single Art. 30 element. Fenced code blocks are paste-ready into the corresponding TUM DSMS form field at https://dsms.datenschutz.tum.de/; everything outside the fences is contextual.

## Identifier

- Title: `Hephaestus – Practice-Aware Feedback for Software Projects`
- Tags: `Webdienst`, `Lehre`, `Forschungsprojekt`
- Joint Controller: tick (workspace administrators are joint controllers under Art. 26 GDPR — see "Legal basis" below).
- Relevant for Subject Rights Request (SRR): tick.
- Responsible department: TUM School of Computation, Information and Technology.
- Associated TUM Org identifier: `TUS1322`.
- DPIA pre-screen: see [`dpia-prescreen.md`](./dpia-prescreen.md) — full DPIA indicated; controller/DPO determination pending. Engineering approval does not lift the deployment change freeze.

## Controller and contact (Art. 30(1)(a))

```text
Technical University of Munich (TUM)
Arcisstraße 21, 80333 Munich, Germany
Represented by President Prof. Dr. Thomas F. Hofmann
Email: poststelle@tum.de; telephone: +49 (0)89 289-01
DPO: beauftragter@datenschutz.tum.de

Operational responsibility: Prof. Dr. Stephan Krusche, Head of AET
Research Group for Applied Education Technologies
TUM School of Computation, Information and Technology
Department of Computer Science
Boltzmannstraße 3, 85748 Garching bei München, Germany

Operational technical contact: ls1.admin@in.tum.de
```

DSMS responsible person: Stephan Krusche (krusche@tum.de). Felix Dietrich (felixtj.dietrich@tum.de) added as additional responsible person for edit access.

## Purpose and description (Art. 30(1)(b))

```text
Hephaestus is a self-hosted web platform operated by AET on TUM infrastructure at https://hephaestus.build. Its purpose is to support project-based software-engineering teaching at TUM and the development work of AET research projects by giving each contributor feedback on their collaborative engineering work: for example, whether a pull request is small enough to review well, or whether a review reply addresses the question raised.

A workspace administrator connects one or more Git repositories from github.com or gitlab.lrz.de. Hephaestus then synchronises the pull/merge requests, issues, code reviews, review comments, and commit metadata authored in those repositories. The platform processes activity authored in the connected repositories, whether or not the author has signed in to Hephaestus.

The synchronised activity is analysed against a set of practices configured by the workspace administrator to produce observations about each contributor's activity. Some of these judgements require reading and understanding natural-language text, such as the meaning of a code comment or the substance of a review reply. For those, the analysis uses an external LLM provider chosen by the administrator for the workspace. Automated practice review forwards the relevant pull/merge-request diff or issue content, the surrounding discussion and, for a pull/merge request, whatever the review reads from the captured repository and its history to the provider, and can post the resulting AI-generated feedback as comments on the reviewed artefact. Where explicitly connected and permitted, selected Outline documents may supply project context. The conversational mentor is an in-app chat where contributors can ask follow-up questions; their messages and the approved bounded context from repository activity, prior feedback, selected Outline documents, or participant-permitted monitored Slack channels are forwarded to the same provider. Sources are purpose- and audience-bound; an enabled integration is not by itself authorization to include all of its content in every request.

Contributors who sign in with their GitHub or LRZ-GitLab account get a personal dashboard summarising their observations and activity, access to the conversational mentor, and their account preferences. Sign-in adds the federated user identifier, username, display name, email, and avatar URL to what Hephaestus holds about that contributor. Everyone with a role in the workspace also sees Activity: counts and lists of the pull/merge requests, reviews, issues and comments that members did in the workspace's repositories, and of each member's open work (review requests, assigned issues) with each reviewer's review state, for themselves and for the workspace. Team memberships synchronised from GitHub teams and gitlab.lrz.de subgroups let members narrow Workspace activity to one team, and on GitHub show a member the review requests addressed to one of their teams. Members are listed by name and never scored or ranked. Activity does not check a viewer's own permissions in the source system, and workspace administrators can hide a member from the workspace view. Workspace administrators can additionally enable Slack integration for App Home privacy controls, mentor DMs, and explicitly activated monitored channels.

Signed-in contributors can send product feedback and answer or decline surveys authored by instance administrators. These submissions stay in the instance database and are available to instance administrators for product improvement; they are not reused for research. Research-purpose surveys are covered under *Legal basis* below.

These workspace-level configuration choices are made by the workspace administrator and TUM/AET as joint controllers under Art. 26 GDPR (the choices are enumerated in "Legal basis" below). Hephaestus is built around the contributor's own development: observations serve the contributor and give the workspace administrator a way to deliver targeted feedback during the project. Observations are advisory and contestable; the platform makes no automated decisions within the meaning of Art. 22 GDPR and feeds no grading, assessment, HR, or access-control pipeline. Signed-in contributors can stop new practice-feedback comments and related Slack reminders through the in-app **Comments and Slack reminders** setting and respond to individual pieces of feedback by recording whether they were helpful and how they were handled. This delivery setting does not stop review processing; objections to processing under Art. 21 GDPR use the contact process in privacy §7.
```

### Optional browser extension

The desktop Chrome extension is another client for the same review-context purpose. On exact sites
the user allows, it sends the canonical GitHub/GitLab work address of each supported page the user
opens to the selected Hephaestus instance, automatically when the page opens and while it stays open,
to show the existing practice review in the page. On supported repository issue and pull/merge-request
lists, choosing a row's Hephaestus preview sends only that work's canonical address; the extension
does not look up every row or send the list's search terms and filters. Review requests and run changes are confirmed in
the extension's own window and use the same server endpoints and authorisation as the web app. It
does not collect page bodies or browsing history. It handles account identity, installed-client
credentials, the local instance preference, a per-tab session-memory record of the reader's report
choices, and returned review records, only the reader's own, administrators included: metadata about
comments recorded as posted for the reader on that work (practice names, comment locations, links and
recorded delivery times), and the reader's own observations on it (practice, outcome, severity and a
one-sentence summary) when the report is opened. It reads and shows no feedback text and no other
developer's records; those remain in the web app under its own access rules. The default destination is `hephaestus.build`;
custom instances have their own operator. The published notice's **Chrome extension** section and
[extension privacy guide](/user/browser-extension-privacy) own the disclosure and controls.
The [personal-data map](./personal-data-map.md) owns installed-client session cleanup. Canonical
work-URL lookups are not a research dataset; platform research consent does not authorise unrelated
reuse of extension browsing activity. There is no extension analytics or error-reporting recipient.

## Data subjects (Art. 30(1)(c))

Tick in DSMS:

- Students (TUM)
- Students (extern)
- Employees (TUM)
- Employees (extern)
- Other Website Visitors

## Categories of personal data

Tick in DSMS: Name(s), Contact details: email, Image data, Indicators of Behaviour, IP address, Social network data, User IDs and Passwords. Do **not** tick "Examination and academic performance" — practice observations are advisory, not graded.

```text
Repository-activity artefacts authored by the contributor in the connected Git repositories (pull/merge requests, issues, code reviews, review comments, commit metadata and, where the deployment enables repository checkout, the repository at the reviewed commit together with the Git history reachable from it), review requests addressed to the contributor and each reviewer's review state, team memberships synchronised from GitHub teams and gitlab.lrz.de subgroups, AI guidance-assistant conversations, product-feedback text, the optionally shared page path and browser user agent, and the receiving release version, survey invitations, answers and declines, email subscription choices and times, opaque unsubscribe capabilities, email request/expiry/cancellation and relay-acceptance facts, pending notification payloads, selected Outline project documents, and Slack integration data when enabled (Slack IDs, Slack identity links, App Home privacy choices, Hephaestus DM mentor messages, and new messages in administrator-activated monitored Slack channels).
```

Hephaestus does not intentionally solicit or classify special-category data (Art. 9(1) GDPR) or criminal-offence data (Art. 10 GDPR). Because repository and chat fields contain free text, incidental content may include and therefore cause processing of them. The privacy statement instructs users not to enter third-party personal or sensitive data.

## Recipients (Art. 30(1)(d))

```text
External processors engaged by TUM/AET as controller. AVVs are in place at TUM/AET level for the AET-pool processors. Where a workspace administrator configures a different LLM endpoint (see below), the AVV is at that administrator's institution.

- GitHub, Inc. (USA) — identity provider (OAuth) and source-system API for connected repositories on github.com.

- An external LLM provider, chosen per workspace by the workspace administrator from any OpenAI-API-compatible HTTPS endpoint (configured by a base URL, an API token, and a model name). The choice is a joint-controller decision under Art. 26 GDPR. The TUM-operated deployment uses the Microsoft Azure OpenAI Service in an EU region under enterprise no-training terms by default. A workspace administrator may configure a different endpoint instead, such as OpenAI OpCo, LLC (with OpenAI Ireland Ltd. as the EEA contracting party), an institution-level enterprise gateway, or a self-hosted model server.

- Salesforce, Inc. / Slack Technologies, LLC (USA) — Slack app delivery, identity linking, App Home privacy controls, DM mentor messages, and monitored-channel event delivery when Slack has been enabled for the workspace.

- The operator of the exact Outline origin connected to a workspace — selected-document source and optional OAuth identity provider. Hephaestus has no default Outline vendor or origin. The integration remains disabled until the deployment record classifies that operator as controller-owned infrastructure, an Art. 28 processor, or a separate controller and records its region, transfer basis, retention terms, and AVV where required.

Separate controller (not an Art. 28 processor):

- Leibniz-Rechenzentrum (LRZ) der BAdW — operator of gitlab.lrz.de. The platform receives the contributor's identity from gitlab.lrz.de OIDC and synchronises connected gitlab.lrz.de repositories. Inter-public-body transmission under Art. 5(1) Nr. 1 BayDSG.
```

When SMTP is enabled, the configured mail relay receives recipient addresses, notification types and
links for account-security/deletion email and opted-in notifications. Product-feedback bodies and
survey answers are not sent. The deployment record must identify the relay operator and the applicable
TUM-internal arrangement or external processing agreement before activation; the SMTP configuration
alone is not evidence that this review is complete. Email is optional and may remain disabled.

Per-processor AVV detail and the EDPB 07/2020 reasoning for the LRZ relationship are in `processor-checklist.md`.

Product feedback and surveys are first-party processing: submissions stay in the instance database, are visible to instance administrators, are not included in email alerts, and are not reused for research. Research-purpose surveys are covered under *Legal basis* below.

Browser avatar requests go directly to the source image host, including for unsigned visitors on
public pages. Record `[actual image hosts, roles, lawful basis, transfer safeguards and host retention]`;
they receive visitor IP/network metadata and the requested image URL. Optional browser/server Sentry
and trace collectors also need recipient entries when configured; see the processor checklist.

## Third-country transfers (Art. 30(1)(e))

U.S. recipients are covered by the EU-US Data Privacy Framework (Commission Implementing Decision (EU) 2023/1795) where the recipient is on the active DPF list, with Standard Contractual Clauses Module 2 (Commission Implementing Decision (EU) 2021/914) as fall-back. The TUM-operated deployment uses Microsoft Azure OpenAI in an EU region by default, with processing geography to be verified against the actual deployment type and contract. An Outline origin outside the EEA cannot be enabled until its transfer basis is recorded in this section.

## Storage location and retention (Art. 30(1)(f))

**Where stored**

```text
Self-hosted by AET at https://hephaestus.build on AET-administered infrastructure at TUM. Application data — including authentication state (accounts, federated identity links, the cookie-session revocation list, and the auth-event log) — in PostgreSQL, which also holds the practice-review job queue; webhook and integration-sync events in NATS JetStream. Local working copies of monitored repositories may be stored on the host filesystem when practice-review code execution is enabled. Container stdout goes to the Docker json-file driver. Every service in the stack sets an explicit rotation cap in the compose files: 50 MiB per file × 5 files for the webapp, the application server, the worker and PostgreSQL; 10 MiB × 3 for the webhook receiver, NATS, the reverse proxy and the maintenance page. No layer of the stack writes an HTTP access log — Tomcat's is explicitly disabled in the production profile, the Traefik reverse proxy is not started with `--accesslog` (Traefik's default is off), and both nginx containers (the static frontend and the maintenance page) disable it at the server level. The shipped stack creates no general HTTP access-log copy; authentication and security events still record connection metadata.

Application and authentication data reside on TUM infrastructure within the EU. AI-assisted features additionally forward code snippets and surrounding discussion to the workspace-configured LLM provider (default for the TUM-operated deployment: Microsoft Azure OpenAI in an EU region).
```

**Retention**

```text
Mixed retention by category:

- Sign-in account data: the in-app account-deletion control revokes access immediately; after a 48-hour cooldown, a scheduled sweeper removes federated identity links and the other authentication rows listed under "Deletion guarantee" and tombstones the account. Product-feedback submissions and survey invitations, responses or declines have no independent time-based expiry; they are removed with account erasure or, for workspace-scoped submissions, workspace purge.
- Email subscriptions and unsubscribe capabilities: retained until account erasure; opting out preserves the opt-out and disable-only link. Survey email request and relay-acceptance records are retained with the survey, with no independent age-based expiry; account erasure, survey deletion and applicable workspace purge remove them. Pending notification publications are deleted on completion; after their seven-day delivery deadline the next processing attempt expires and removes them. An offline instance cannot run that cleanup. Delivered mailbox copies and relay logs follow the recipient or relay's retention, not this application's deletion schedule.
- Self-service account exports: download eligibility expires 48 hours after preparation; the export sweeper removes expired archives. Person-data previews expire after 48 hours when abandoned. Wider rights-request delivery copies need an operator-defined secure transfer and expiry.
- Settings-change history: 365-day automatic window; account erasure detaches actor references while retaining change facts.
- Authentication-event log (sign-in / sign-out, token issue / refresh, user views by an instance administrator, and historical impersonation events; includes the source IP address and user agent): retained for 12 months in monthly partitions, the oldest dropped automatically, as a security measure (the documented security-processing basis and Art. 32 GDPR). A user-view event records the instance administrator, the workspace, the viewed user, the stated reason and what was read. On account erasure the IP address, user agent, and free-text details are cleared from every event in which the erased account is the instance administrator or the viewed user; account references still point to the tombstoned account, and the event row remains for the rest of its window.
- Contributor profile (login, name, email, avatar) and account preferences: retained as instance-global identity records independently of workspace repository monitoring. Repository orphan cleanup and workspace purge do not remove them. They are exported and removed or anonymised through the instance-admin Person data procedure on receipt of a verified request. Matching uses exact provider instance and native user keys, not names, logins or email addresses.
- Authored repository artefacts (issues, pull/merge requests, comments, reviews) synchronised from GitHub / gitlab.lrz.de and their practice observations, delivery-policy traces, and pending delivery packages: the active PostgreSQL mirror is retained while at least one workspace monitors the source repository and is removed when the last workspace stops monitoring it, the last relevant connection is disconnected, the workspace is purged, or verified erasure is completed. A terminal delivery keeps only its idempotency and provider-placement record after the package is projected into the practice-feedback ledger; failed packages remain available for an administrator to retry. Diagnostic, worker-input, and broker copies follow the bounded windows below and do not all support immediate selective erasure.
- Workspace memberships, AI conversations, and activity records: retained with the relevant workspace records. Removed when those records or the workspace are purged, or through the instance-admin Person data job on verified erasure. Disconnect or workspace purge removes the active PostgreSQL mirror of Slack and Outline content; materialized diagnostic output, worker-input copies, and broker messages expire under the bounded windows below.
- Retired leaderboard values (league points, XP): deleted from the database when the release that retired the leaderboard is installed; an operator may keep an exported copy outside the application, which is then under the operator's own retention.
- Removal of the active mirror on disconnect / purge is an application of storage limitation (Art. 5(1)(e) GDPR): the integration is the sole purpose for which the mirror is held, so once it is severed there is no basis to retain that copy. It is a workspace-administrator action and is **not** the fulfilment path for a data subject's erasure request under Art. 17 — that uses the instance-admin Person data process described under "Deletion responsibility" below, which also covers account-bound rows that no single workspace owns.
- LLM-provider-side prompts: according to the chosen provider's terms. For the TUM-operated default (Microsoft Azure OpenAI in an EU region), within the enterprise abuse-monitoring window published in Microsoft's Azure OpenAI data-privacy documentation; eligible customers may apply for Microsoft's modified abuse monitoring (Limited Access program) to suppress prompt storage and human review.
- Settings-change audit log (`config_audit_event`: who changed which workspace or AI setting, when, and the before/after values; records the acting account and, for historical changes made under the retired impersonation feature, the impersonating administrator): retained 365 days, then deleted automatically. The table is append-only by database trigger, with an exception for clearing actor references on account erasure. The change record is retained for the remainder of its window. Credentials are never stored in it — a rotation records only that a secret changed.
- LLM usage ledger (`llm_usage_event`: per-run token counts and cost, attributed to a workspace and to the run that incurred them): eligible for deletion once older than the configured window (`HEPHAESTUS_LLM_USAGE_RETENTION`, default `P400D`; operator-tunable, and raisable where a commercial or tax retention obligation runs longer). A daily sweep deletes in batches within a bounded pass, so removal begins at the first sweep for which a row is eligible and any remaining backlog is cleared by later sweeps. This supports annual accounting comparisons without indefinite retention. It holds no message content and no free text; its opaque source identifier may outlive the source row; it is not a foreign key and cannot resolve a source row after that row is deleted.
- Practice-review job records (`agent_job`: the queued job, the sandbox's captured stdout, and the observations it produced — these can quote the contributor's code and discussion): the diagnostic payload (`container_logs`, `output`) is stripped to NULL 14 days after the job reaches a terminal state (`AGENT_PAYLOAD_RETENTION`, default `P14D`) and the unreferenced row becomes eligible for deletion after 90 days (`AGENT_ROW_RETENTION`, default `P90D`). Both are operator-tunable. Pending delivery blocks both passes, and observations or feedback referencing the job block row deletion. The scheduled service runs only with the server role and AGENT_ENABLED=true; disabling that service does not expire retained jobs. Monitor the backlog and use verified person erasure for individual requests.
- Artifact-source evidence retention separates durable verification results from temporary worker inputs; the [artifact-source governance decision](./artifact-source-governance.md) owns the retention and erasure boundaries. A digest is an integrity identifier, not authorization. Extended evaluation retention requires a separate approved purpose and tenant-safe authorization.
- Webhook and integration-sync event transport buffer (NATS JetStream): the `slack` and `outline` streams carry real message and document content and expire after at most 72 hours; the GitHub and GitLab streams carry delivered webhook payloads (pull-request, issue and review-comment bodies) and expire after at most 180 days. Each stream also carries a disk ceiling (`HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES`), and where ingest volume reaches it the broker discards the oldest messages first, so the actual retention is shorter than the time limit and is reported per stream as `webhook.stream.oldest.message.age`. The time limit is the maximum, not the guaranteed duration. PostgreSQL is the system of record and all consent/erasure controls apply there; erasure cannot reach inside the broker, so these horizons, not an erasure request, are what remove the buffered copy.
- Container stdout (startup and error output; no per-request records): rotated by size by the container runtime, per the per-service caps described there. There is no time-based expiry; a line survives until the rotation window displaces it.
```

**Reasoning**

```text
Hephaestus is contributor-facing. Account-bound data exists to give the data subject continuity of feedback while they participate, and is removed when they leave or on a verified erasure request (Art. 5(1)(e) GDPR storage limitation). Server-side logs and container stdout are bounded to a window short enough to limit exposure and long enough to investigate security incidents under Art. 32(1) GDPR (Art. 5(1)(c) data minimisation).
```

**Deletion responsibility**

```text
Routine retention-driven deletion (logs, container stdout): handled automatically by the runtime; ops contact AET operations team, ls1.admin@in.tum.de. Subject-rights deletion: Prof. Dr. Stephan Krusche (head of AET, responsible for this PA), with technical execution by an instance administrator through Person data on receipt of a verified request through the TUM DPO at beauftragter@datenschutz.tum.de. Identity-verification procedure and Art. 12(3) GDPR response timeframe (one month, extendable by two further months for complex or numerous requests) are described in §7 of the privacy statement.
```

**Access and portability fulfilment (Art. 15, Art. 20)**

```text
Hephaestus provides a self-service account data export: a signed-in contributor requests an export from the in-app settings (account "Danger Zone"), the platform compiles a JSON archive of the personal data it holds about that contributor — the Hephaestus account, federated identity links, workspace memberships, account preferences, and the contributor's own authentication-event history — and the contributor downloads it from the app. The archive deliberately excludes credentials and session/signing-key material. For a wider verified Art. 15 request, an instance administrator uses Person data to resolve an account or exact provider identities, preview per-store scope and download one machine-readable JSON file. It includes conversations, observations (also invalidated), feedback and its delivery state (also never delivered), activity, memberships, profiles, preferences and collected Slack content. The export excludes credentials and other people's profile data. The access and erasure contributors use the same frozen selections; a changed preview must be replaced. Original source-platform content and copies outside active application storage remain subject to the platform or operator's separate procedure. There are no HTTP access-log entries to disclose: no layer of the stack writes one. The IP addresses Hephaestus does hold are the ones on authentication events, which the self-service export already covers. The wider export includes selected locally mirrored source content; original source-platform exports must be requested from those platforms separately. Identity verification, response timeframe, and contact path are the same as for erasure.
```

**Person-request execution and evidence**

The [operations runbook](../production-operations-runbook#access-and-erasure-for-one-person) defines
identity verification, preview, JSON transfer, external-feedback removal, erasure and resume. The
job records the acting administrator and per-store counts. Each store step commits independently;
retry skips completed steps. Completed receipts hold no erased content or subject keys. Failed jobs
retain the keys needed to resume; abandoned previews expire after 48 hours. Minimal exact provider
keys are held separately as processing-suppression controls, not as an audit record. They prevent
processing again even if provider sync mirrors an upstream record. Account erasure uses the same
account purger as self-service deletion; self-service cooldown behaviour is unchanged.

**Deletion guarantee**

```text
- The Hephaestus sign-in account and federated identity links: covered by the in-app account-deletion control. Deletion immediately revokes all sessions and marks the account for deletion with a 48-hour cooldown; after the window a scheduled sweeper hard-deletes the account-bound rows (identity links, feature flags, the session/revocation list, export artefacts, product-feedback submissions, and survey invitations, responses or declines), tombstones the account's contact PII, and severs the link to the git-provider activity mirror.
- Workspace purge also removes workspace-scoped product-feedback submissions and survey responses.
- Contributor profile and dependent records: Instance administration → Person data covers exact source-only identities as well as account holders. Each personal store has an implemented export and erasure contributor. The preview freezes row keys; changed scope requires a new preview. Erasure cancels affected attempts, waits for mounted evidence-store acknowledgement, removes selected conversations and derived records, anonymises shared profiles and attribution, and clears affected hidden Heph runtime journals. An offline worker or failed step leaves a resumable request, not a completed erasure. Other developers' observations and feedback stay when the person appeared only in their evidence; the captured input copies are erased. Permanent exact-native-identity suppression prevents new product processing; sync may still mirror upstream records. Git history, broker payloads, backups, delivered email and provider-side copies follow the separate boundaries in the personal-data map. Extended evaluation retention is not authorised by these controls.
- Mirrored third-party content, on disconnection of the corresponding integration or on workspace purge (both triggers erase the same rows, by hard deletion, not by a deleted-flag):
    - GitHub / gitlab.lrz.de: the mirrored repository and everything cascading from it — issues, pull/merge requests, reviews, review threads and comments, discussions, labels, milestones, collaborators — plus the workspace's repository monitors, any local git clone, the org-level mirror (teams, team memberships, organisation memberships), the activity-event log, and the SCM-derived practice observations and feedback (whose evidence quotes mirrored content verbatim). Repository rows are instance-global and shared: a repository another workspace still monitors is retained for that workspace, and only the disconnecting workspace's access path is removed. The org-level mirror is removed only when no other workspace is bound to the same organisation.
    - Slack: messages, threads, monitored-channel records, participant-consent records, mentor threads, and the conversation-derived observations and feedback.
    - Outline: documents, collections, and the document event log.
- Retained after disconnection / purge, for all four integrations: the operational sync history (`sync_job`, `connection_activity`, `connection_audit`) — job kind, type, status and timestamps only, capped per connection, carrying no third-party content — so that the disconnection itself remains auditable. Connection credentials are cleared atomically as part of the same transition. Cross-tenant identity rows (accounts, organisations, identity providers) are not touched by a disconnection; they are covered by the account-deletion and erasure-request paths above.
- Artifact-source manifests, repository snapshots, citation verdicts, derived assessments, exports, and governed evaluation cases must be traversed by source, workspace, and person erasure. Expiry/erasure may leave only a non-content typed tombstone and makes replay unavailable; audit reproducibility never overrides erasure.
- Source-side content on GitHub or gitlab.lrz.de: not modified by deletion in Hephaestus.
- Container stdout (startup and error output): rotated by size by the container runtime (per-service caps under "Where stored") and not selectively prunable — an erasure request cannot reach inside the rotation window, which displaces the lines on its own.
- Backups: the optional encrypted PostgreSQL backup overlay retains full chains by count, not by a legal age limit. Deployment activation, destination retention, VM snapshots and restore handling are operator decisions. Record their expiry and prevent restored data from returning to service until completed erasures and suppression controls have been reapplied.
```

## Technical and organisational measures (Art. 30(1)(g) + Art. 32)

```text
Pseudonymisation and encryption (Art. 32(1)(a))
- TLS-terminated at Traefik with Let's Encrypt; HTTP redirects to HTTPS with HSTS.
- Internal service-to-service traffic stays within the Docker network.
- Outbound calls to GitHub, gitlab.lrz.de, the LLM provider, and Slack are HTTPS-only. Where an operator sets a second display currency (`HEPHAESTUS_LLM_DISPLAY_CURRENCY`, unset by default), the application server additionally makes one unauthenticated HTTPS GET each weekday for the European Central Bank's public daily reference-rate file. It sends no personal data and no request parameters, so the ECB is not a recipient of personal data under Art. 30(1)(d) and needs no separate legal basis; it is listed here only to keep this inventory of outbound calls complete.
- Federated identity links to GitHub user ID / gitlab.lrz.de `sub` minimise collected identifiers; surrogate primary keys are used internally.
- PostgreSQL data at rest relies on the host's filesystem and access-control protections; application-level at-rest encryption of the general store is not enabled. Secrets are sealed at rest with AES-256-GCM under two platform-level keys: `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` protects workspace LLM API keys, login-provider OAuth client secrets, Outline webhook signing secrets, sandbox job tokens and the JWT signing keys; `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY` protects the integration connection credentials (GitHub and GitLab tokens, Slack bot tokens, Outline API keys, and the OAuth access and refresh tokens held with them), is versioned, and can be rotated online. Compromise of the first key is the blast radius for workspace secrets and session-token signing; compromise of the second exposes every connected provider's credential. Key injection is deployment-specific and must be verified against the active role. In the shipped Compose stack neither key is confined to the application-server container: both are injected into every container that reads an encrypted column — `application-server`, `application-worker` (the practice-review worker decrypts the workspace's LLM-provider credential to serve its in-process LLM proxy), and `webhook-server` (it verifies inbound Slack and Outline deliveries against the per-connection secret stored on the encrypted credential row) — as container environment variables, so all three are in scope for key-compromise assessment. The keys are not persisted to disk by the application and are not written to any log.

Confidentiality (Art. 32(1)(b))
- SSH key-only host access; password authentication disabled.
- End-user access via Hephaestus-native auth federating to GitHub OAuth + gitlab.lrz.de OIDC; short-lived ES256 cookie-session JWTs with server-side revocation (ADR 0017).
- Workspace-scoped membership and role checks enforced at the application layer (`@PreAuthorize` and dedicated workspace-membership filters).
- Least-privilege source-system access via per-workspace GitHub App installation or scoped access token.
- Reverse proxy exposes only required routes; everything else returns 404.
- Practice-review sandbox runs as non-root inside isolated Docker containers on per-job `--internal` networks with no DNS and no general egress; the reachable service is a per-attempt, token-authenticated worker gateway for LLM calls, declared workspace transfers and result upload. Sandbox execution is workspace opt-in in every case: a container runs only for a workspace that has an enabled model binding for the purpose concerned. The two purposes are gated differently, and neither is on for a workspace that has bound nothing.

  - *Queued practice review* additionally requires the operator to set `AGENT_ENABLED=true`. The deployment's compose files pass that variable to both the application server and the worker, defaulting it to `false`, and no runtime profile overrides it — so a worker started outside those compose files, on the `worker` Spring profile, is equally off until the variable is set. It gates the job queue: with it unset, no practice-review job is submitted, claimed or executed.
  - *Interactive mentor conversations* do **not** go through that queue, so `AGENT_ENABLED` does not gate them. Web and Slack turns place their sandbox on a connected worker with spare mentor capacity. The application server keeps admission, budget checks, context and thread persistence, and needs no Docker client when its worker role is disabled. A worker drain or restart ends its live sessions; the next turn restores the thread from PostgreSQL. No spare capacity produces an explicit, retryable busy response rather than local execution.
- Heph has a separate allow-internet binding. Practice reviews enforce an internal network regardless of the stored binding; Heph can be configured for broader connectivity. Approve and document that network posture before enabling it.
- Audit records produced for high-value writes (workspace creation, role assignment, LLM-provider credential changes, account deletion).

Integrity (Art. 32(1)(b))
- Git is the authoritative source of all application code; signed commits and PR review.
- Every production image is built by the release workflow, cosign-signed (Sigstore keyless) and provenance-attested via `actions/attest-build-provenance`, so any deployed image can be verified back to this repository's release run.
- Every image the compose files run — the platform's own (application server, worker, webhook receiver, webapp, PostgreSQL, the `agent-pi` sandbox image) and the third-party infrastructure images (Traefik, NATS, nginx) — is referenced by **sha256 digest** from a cosign-verified release lock rendered at deploy time; a compose project started without the lock refuses to start. The `agent-pi` image, the only one that executes contributor repository content, is additionally checked at application start: `AgentImagePinGuard` refuses to boot unless its reference is digest-pinned. Verification recipe in [Release image lock](../release-image-lock.md).

Availability and resilience (Art. 32(1)(b))
- Containers restart on failure (`restart: unless-stopped`); per-service health checks.
- Resource limits per container; per-job sandbox concurrency / CPU / memory ceilings.
- Bounded LLM-call timeouts; no feedback is posted when an LLM provider is unreachable.
- Ingress rate limits on unauthenticated endpoints.
- TLS-certificate renewal via Let's Encrypt ACME automated.

Recovery (Art. 32(1)(c))
- An optional encrypted pgBackRest off-host backup overlay ships in docker/self-host/compose.backup.yaml. Its existence does not prove that the TUM deployment enables it. Record the actual destination, chain-count policy, effective age limit, access permissions, restore-test result and erased-data handling before relying on recovery. The PostgreSQL container uses a named Docker volume on the host. The authoritative copies of pull/merge-request content live on GitHub and gitlab.lrz.de. Loss of the host-local PostgreSQL volume would lose Hephaestus-specific state (workspace state, observations, practice configurations); this file does not record acceptance of that risk.

Testing and evaluation (Art. 32(1)(d))
- CI runs repository-specific Semgrep checks, Trivy (filesystem and container image), TruffleHog secret detection, and Renovate dependency updates.
- CI selects unit, integration and browser suites under the repository verification contract. The personal-data map links focused export, erasure and tenancy tests.

Organisational
- Operators are TUM / AET employees or authorised contributors acting under TUM-internal security policies.
- Workspace administrators are briefed on the joint-controller / shared-responsibility model (privacy §10) before workspace provisioning.
```

## Legal basis (Art. 6 GDPR + national norms)

Tick in DSMS:

- Art. 6.1a GDPR (consent) — for workspaces collecting explicit consent (e.g., the AET capstone course).
- Art. 6.1a GDPR (consent) — for optional academic-research participation requested at first login.
- Art. 6.1b GDPR (contract / service request) — for voluntary sign-in by non-TUM contributors.
- Art. 6.1e GDPR (public task) — for TUM/AET operation of the platform.

Do **not** tick 6.1f. Bavarian public bodies cannot rely on legitimate interest for tasks carried out in the performance of a statutory public duty (Art. 6(1) Unterabsatz 2 GDPR).

National multi-select: tick `Art. 4.1 BayDSG (Bavarian data protection act)`.

```text
TUM/AET as platform operator: Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG (Allgemeine Aufgaben der Hochschule) and Art. 4(1) BayDSG.

Per-workspace lawful basis: workspace administrator and TUM/AET are joint controllers under Art. 26 GDPR for the workspace's processing. The administrator invokes the basis applicable to their workspace's contributors — typically Art. 6(1)(a) GDPR (consent, e.g. the AET capstone course's application phase) or Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG (public-task activity by a TUM unit, e.g. regular courses or public open-source repositories such as ls1intum/Artemis). Administrators outside TUM cannot invoke Art. 6(1)(e) BayHIG and invoke a basis available to them (typically Art. 6(1)(a) consent, or Art. 6(1)(f) for private bodies under their own LIA).

Voluntary sign-in by non-TUM contributors to use personal features: Art. 6(1)(b) GDPR.

Optional academic-research participation: Art. 6(1)(a) GDPR. It is separate from the terms and from the public-task basis for platform operation. Research enrollment and analysis require the latest `RESEARCH_PARTICIPATION` decision to be a grant for the current notice version; they do not fall back to a preference flag or another legal basis after withdrawal. The append-only ledger records grants, refusals and withdrawals with a UTC timestamp, mechanism and notice version. The version identifies the first-layer wording, which is the first-login screen as published in that signed release and immutable in git; the operator-specific detail that screen links to is the privacy notice served at `/privacy`, which is versioned by the deployment rather than by this ledger. Withdrawal ends the authorization for further research processing immediately. Account erasure removes the ledger's account reference; the resulting non-account-linked event remains, with its notice version, as evidence of how consent was managed.

Product feedback and product-purpose surveys for improving the TUM-operated instance: Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG and Art. 4(1) BayDSG. Responses are not reused for research. Research-purpose surveys: Art. 6(1)(a) GDPR under the research participation above. Such a survey is offered only to accounts whose latest research decision is a grant for the organisation it names, is labelled as research on screen, and its answers are that study's data rather than product feedback; they stay in the same database, the instance administrators who read them do so on behalf of the study, and withdrawal does not automatically delete stored answers. The study must apply withdrawal to further
consent-based processing and review retention and erasure under its documented lawful basis; continued
storage is not permission to continue research use.

The Hephaestus session cookie (`__Host-HEPHAESTUS_AT`), the CSRF + OAuth-state cookies, and theme-preference localStorage: § 25 Abs. 2 Nr. 2 TDDDG (technisch unbedingt erforderlich) i.V.m. Art. 6(1)(e) GDPR.
```

## Source of data

DSMS multi-select: tick `Data received from third parties` and `Directly from the data subject`.

```text
- From GitHub and gitlab.lrz.de: identity at sign-in (GitHub OAuth, gitlab.lrz.de OIDC, federated directly by the application server); repository activity via the GitHub App installation or workspace-configured access token; webhook events delivered to the platform's /webhooks endpoint.
- From a connected Slack workspace: linked identity and App Home choices, mentor direct messages, and new messages
  from explicitly activated monitored channels after the visible announcement.
- From a connected Outline workspace: documents and author attribution from collections explicitly selected by
  the workspace administrator; optional linked identity when a contributor connects Outline.
- Directly from the data subject: account preferences, AI-assistant messages, product feedback, survey answers, and rights requests submitted through the contact process.
- From the HTTP connection: no general access log in the shipped production stack. Authentication and security processing still use connection metadata. The source IP address and user agent are collected as such only for authentication events, and are retained under the auth-event log's own 12-month window described above.
```

## Information duty (Art. 13)

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
Git history. The repository owner must remove Git authorship at the source; a later fetch can mirror
any records that remain there. Person exports list repositories whose history may contain the
person's commits, selected through exact native identity references, never names or email addresses.

## Current source scope and controls

Automated practice review can read all permitted workspace areas and repositories in its job folder,
including Slack threads and person-scoped observation and feedback history, without record-count or
history-window caps. The source-use registry version 1.3.0 records maintainer engineering approval,
not controller/DPO approval. The [source-governance record](./artifact-source-governance.md) owns the
scope and approval boundary. These categories belong in the full DPIA and the pending notice review.

An account's **No AI**, **In-house** or **Cloud** choice applies across the instance's workspaces to
practice review and Heph, including Slack. Model eligibility also depends on workspace policy and
the declared operator. No AI stops new requests, not source sync or requests already sent. This
product choice is separate from research consent and is not the legal basis for underlying processing.
Slack App Home reflects mentor eligibility and keeps channel-message controls available when Heph
is refused. Linking Slack alone grants neither research participation nor unrestricted AI use.

Generated-path patterns mark retained changed-file evidence; they do not redact it. Bot-authored
work and bot reviewers receive separate review refusals, not person erasure. An unavailable GitHub
or GitLab repository retains its monitor and stored work; two unavailable responses cause daily
rechecks, and **Sync now** checks immediately. Permission loss or a 404 is not proof of deletion.
Stopping the last monitor or verified erasure provides the relevant removal path.

GitLab note reconciliation removes a missing note from live context only after a complete parent
listing and direct absence confirmation. Partial listings and failed confirmation keep the mirror.
Historical captured review inputs are unchanged by reconciliation; person erasure and retention
handle those copies. GitLab tokens are checked daily and can rotate or be replaced; rotation revokes
the old token. Source permission and credential recovery are not rights-request fulfilment.

Practice profiles and their feedback text are private to the developer. Workspace admins can read
observations, delivery metadata and dispute explanations, but not practice-page or conversation
feedback text through ordinary administration. Ratings and other private response notes stay private.
Instance-admin **View as user** is a separate read-only, reasoned and audited access path. External
feedback follows the destination's audience. A public workspace exposes its directory and practices,
not Activity, practice profiles, feedback or conversations.

Disputes hold back the same point in later reviews while they stand; withdrawal of the dispute
allows it again, with the committed-secret exception. Admin withdrawal preserves feedback and its
history, removes it from future review/Heph context, and is reversible. Already sent conversations
and inline provider comments are not recalled. This is correction and delivery control, not erasure.

## Retention decisions that need deployment evidence

Slack channel retention defaults to 30 days, configurable per connection with a 180-day ceiling.
Eligibility is measured from a thread's last message, so an active thread can contain much older
messages. This is not a maximum age for each message and does not apply to mentor DMs. Daily cleanup
and failures can delay removal. Contributor profiles, conversations, observations, feedback, consent
records, and several operational stores have no independent time-based expiry: choose and document
a necessity review and deletion trigger, rather than calling indefinite storage a retention period.

Worker attempt folders are removed after evidence admission; ended attempts have a one-hour grace,
and restart cleanup removes abandoned folders. Removal failures need a later successful cleanup.
There is no shipped 30-day replay/blob retention guarantee. Repository mirrors cache upstream history
while monitored; they are not rewritten for person erasure. Offline mounted stores must acknowledge
erasure before the person job completes. See the personal-data map for executable evidence.

The person-data upgrade clears unresolvable legacy administrator attribution, connection-audit
actor/detail fields, membership subject attribution and hidden Heph journals once. Visible chats,
settings and lifecycle times remain. Pending integration authorisations must be restarted; a legacy
Outline mirror lacking an exact provider instance stops the upgrade. The shipped migration fragment
owns the operator backup and recovery procedure; this clearing is not a general retention schedule.

## Deployment security evidence

Sandboxes run on the worker role only. Web and Slack Heph use connected workers; the server owns
admission, context and persistence without a Docker socket. Worker memory and mounted copies are
part of the privacy boundary. Management metrics are on a separate private listener, not the public
API; metrics retention and scrape access are operator decisions. Tracing export is off by default;
if enabled, spans can carry workspace/run identifiers, so approve its recipient and retention.
Browser Sentry requires a configured endpoint and the person's error-monitoring consent. Server Sentry initializes when its DSN is nonblank (except the specs profile) and does not consult browser consent. It disables default PII, strips user/request/breadcrumb fields and can retain a trace-correlation tag. The operator must establish and disclose a separate lawful basis for server diagnostics. The
client removes request, user and breadcrumb fields and disables content collection; error text and
stack traces can still contain incidental personal data. Record the actual Sentry operator, region,
retention and contract before enabling it. Consent withdrawal stops new reports, not stored reports.

Review access control, encryption-key custody and rotation, restore tests, cleanup failures and
incident handling regularly. Establish the Art. 33 authority-notification procedure (where required,
within 72 hours of awareness) and Art. 34 communication for high-risk breaches. These are operator
procedures; a CI security scan does not prove they exist.
