# Hephaestus — Record of Processing Activities (Art. 30 GDPR)

This file is the Art. 30 record for the TUM-operated Hephaestus deployment at https://hephaestus.build. Each section maps to a single Art. 30 element. Fenced code blocks are paste-ready into the corresponding TUM DSMS form field at https://dsms.datenschutz.tum.de/; everything outside the fences is contextual.

## Identifier

- Title: `Hephaestus – Practice-Aware Feedback for Software Projects`
- Tags: `Webdienst`, `Lehre`, `Forschungsprojekt`
- Joint Controller: tick (workspace administrators are joint controllers under Art. 26 GDPR — see "Legal basis" below).
- Relevant for Subject Rights Request (SRR): tick.
- Responsible department: TUM School of Computation, Information and Technology.
- Associated TUM Org identifier: `TUS1322`.
- DPIA pre-screen: see [`dpia-prescreen.md`](./dpia-prescreen.md) — controller/DPO determination pending; material source expansion is frozen until the outcome is recorded.

## Controller and contact (Art. 30(1)(a))

```text
Prof. Dr. Stephan Krusche, Head of AET
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

The synchronised activity is analysed against a set of practices configured by the workspace administrator to produce observations about each contributor's activity. Some of these judgements require reading and understanding natural-language text, such as the meaning of a code comment or the substance of a review reply. For those, the analysis uses an external LLM provider chosen by the administrator for the workspace. Automated practice review forwards the relevant pull/merge-request diff or issue content and surrounding discussion to the provider and can post the resulting AI-generated feedback as comments on the reviewed artefact. Where explicitly connected and permitted, selected Outline documents may supply project context. The conversational mentor is an in-app chat where contributors can ask follow-up questions; their messages and the approved bounded context from repository activity, prior feedback, selected Outline documents, or participant-permitted monitored Slack channels are forwarded to the same provider. Sources are purpose- and audience-bound; an enabled integration is not by itself authorization to include all of its content in every request.

Contributors who sign in with their GitHub or LRZ-GitLab account get a personal dashboard summarising their observations and activity, access to the conversational mentor, and their account preferences. Sign-in adds the federated user identifier, username, display name, email, and avatar URL to what Hephaestus holds about that contributor. Workspace administrators can additionally enable a leaderboard, leagues, and achievements based on workspace activity (all off by default), plus Slack integration for App Home privacy controls, mentor DMs, optional digests, and explicitly activated monitored channels.

Signed-in contributors can send product feedback and answer or dismiss surveys authored by instance administrators. These submissions stay in the instance database and are available to instance administrators for product improvement; they are not reused for research without the separate research opt-in.

These workspace-level configuration choices are made by the workspace administrator and TUM/AET as joint controllers under Art. 26 GDPR (the choices are enumerated in "Legal basis" below). Hephaestus is built around the contributor's own development: observations serve the contributor and give the workspace administrator a way to deliver targeted feedback during the project. Observations are advisory and contestable; the platform makes no automated decisions within the meaning of Art. 22 GDPR and feeds no grading, assessment, HR, or access-control pipeline. Signed-in contributors can stop new practice-feedback comments and related Slack reminders through the in-app **Comments and Slack reminders** setting and respond to individual pieces of feedback by recording whether they were helpful and how they were handled. This delivery setting does not stop review processing; objections to processing under Art. 21 GDPR use the contact process in privacy §7.
```

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
Repository-activity artefacts authored by the contributor in the connected Git repositories (pull/merge requests, issues, code reviews, review comments, commit metadata and, when an enabled practice declares it, a bounded repository tree), AI guidance-assistant conversations, product-feedback messages and page paths, survey answers and dismissals, selected Outline project documents, and Slack integration data when enabled (Slack IDs, Slack identity links, App Home privacy choices, Hephaestus DM mentor messages, and new messages in administrator-activated monitored Slack channels).
```

Hephaestus does not intentionally solicit or classify special-category data (Art. 9(1) GDPR) or criminal-offence data (Art. 10 GDPR). Because repository and chat fields contain free text, incidental content may include and therefore cause processing of them. The privacy statement instructs users not to enter third-party personal or sensitive data.

## Recipients (Art. 30(1)(d))

```text
External processors engaged by TUM/AET as controller. AVVs are in place at TUM/AET level for the AET-pool processors. Where a workspace administrator configures a different LLM endpoint (see below), the AVV is at that administrator's institution.

- GitHub, Inc. (USA) — identity provider (OAuth) and source-system API for connected repositories on github.com.

- An external LLM provider, chosen per workspace by the workspace administrator from any OpenAI-API-compatible HTTPS endpoint (configured by a base URL, an API token, and a model name). The choice is a joint-controller decision under Art. 26 GDPR. The TUM-operated deployment uses the Microsoft Azure OpenAI Service in an EU region under enterprise no-training terms by default. A workspace administrator may configure a different endpoint instead, such as OpenAI OpCo, LLC (with OpenAI Ireland Ltd. as the EEA contracting party), an institution-level enterprise gateway, or a self-hosted model server.

- Salesforce, Inc. / Slack Technologies, LLC (USA) — Slack app delivery, identity linking, App Home privacy controls, optional digests, DM mentor messages, and monitored-channel event delivery when Slack has been enabled for the workspace.

- The operator of the exact Outline origin connected to a workspace — selected-document source and optional OAuth identity provider. Hephaestus has no default Outline vendor or origin. The integration remains disabled until the deployment record classifies that operator as controller-owned infrastructure, an Art. 28 processor, or a separate controller and records its region, transfer basis, retention terms, and AVV where required.

Separate controller (not an Art. 28 processor):

- Leibniz-Rechenzentrum (LRZ) der BAdW — operator of gitlab.lrz.de. The platform receives the contributor's identity from gitlab.lrz.de OIDC and synchronises connected gitlab.lrz.de repositories. Inter-public-body transmission under Art. 5(1) Nr. 1 BayDSG.
```

Per-processor AVV detail and the EDPB 07/2020 reasoning for the LRZ relationship are in `processor-checklist.md`.

Product feedback and surveys are first-party processing: submissions stay in the instance database, are visible to instance administrators, create no new recipient, and are not reused for research without the separate opt-in.

## Third-country transfers (Art. 30(1)(e))

U.S. recipients are covered by the EU-US Data Privacy Framework (Commission Implementing Decision (EU) 2023/1795) where the recipient is on the active DPF list, with Standard Contractual Clauses Module 2 (Commission Implementing Decision (EU) 2021/914) as fall-back. The TUM-operated deployment uses Microsoft Azure OpenAI in an EU region by default, keeping that processing inside the EU. An Outline origin outside the EEA cannot be enabled until its transfer basis is recorded in this section.

## Storage location and retention (Art. 30(1)(f))

**Where stored**

```text
Self-hosted by AET at https://hephaestus.build on AET-administered infrastructure at TUM. Application data — including authentication state (accounts, federated identity links, the cookie-session revocation list, and the auth-event log) — in PostgreSQL, which also holds the practice-review job queue; webhook and integration-sync events in NATS JetStream. Local working copies of monitored repositories may be stored on the host filesystem when practice-review code execution is enabled. Container stdout goes to the Docker json-file driver. Every service in the stack sets an explicit rotation cap in the compose files: 50 MiB per file × 5 files for the webapp, the application server, the worker and PostgreSQL; 10 MiB × 3 for the webhook receiver, NATS, the reverse proxy and the maintenance page. No layer of the stack writes an HTTP access log — Tomcat's is explicitly disabled in the production profile, the Traefik reverse proxy is not started with `--accesslog` (Traefik's default is off), and both nginx containers (the static frontend and the maintenance page) disable it at the server level. No per-request IP/URL record is created anywhere.

Application and authentication data reside on TUM infrastructure within the EU. AI-assisted features additionally forward code snippets and surrounding discussion to the workspace-configured LLM provider (default for the TUM-operated deployment: Microsoft Azure OpenAI in an EU region).
```

**Retention**

```text
Mixed retention by category:

- Sign-in account data: the in-app account-deletion control revokes access immediately; after a 48-hour cooldown, a scheduled sweeper removes federated identity links and the other authentication rows listed under "Deletion guarantee" and tombstones the account. Product-feedback submissions and survey responses or dismissals have no independent time-based expiry; they are removed with account erasure or, for workspace-scoped submissions, workspace purge.
- Authentication-event log (sign-in / sign-out, token issue / refresh, impersonation begin / end; includes the source IP address and user agent): retained for 12 months in monthly partitions, the oldest dropped automatically, as a security measure (Art. 6(1)(f) i.V.m. Art. 32 GDPR). On account erasure the IP address, user agent, and free-text details are cleared; account references still point to the tombstoned account, and the event row remains for the rest of its window.
- Contributor profile (login, name, email, avatar) and account preferences: retained as instance-global identity records independently of workspace repository monitoring. Repository orphan cleanup and workspace purge do not remove them. They are removed or anonymised through operator-executed profile removal on receipt of a verified erasure request.
- Authored repository artefacts (issues, pull/merge requests, comments, reviews) synchronised from GitHub / gitlab.lrz.de and their practice observations, delivery-policy traces, and pending delivery packages: the active PostgreSQL mirror is retained while at least one workspace monitors the source repository and is removed when the last workspace stops monitoring it, the last relevant connection is disconnected, the workspace is purged, or verified erasure is completed. A terminal delivery keeps only its idempotency and provider-placement record after the package is projected into the practice-feedback ledger; failed packages remain available for an administrator to retry. Diagnostic, replay/CAS, and broker copies follow the bounded windows below and do not all support immediate selective erasure.
- Workspace memberships, AI conversations, and recognition signals: retained with the relevant workspace records. Removed when those records or the workspace are purged, or through operator-executed deletion or anonymisation on verified erasure. Disconnect or workspace purge removes the active PostgreSQL mirror of Slack and Outline content; materialized diagnostic output, replay/CAS artifacts, and broker messages expire under the bounded windows below.
- Removal of the active mirror on disconnect / purge is an application of storage limitation (Art. 5(1)(e) GDPR): the integration is the sole purpose for which the mirror is held, so once it is severed there is no basis to retain that copy. It is a workspace-administrator action and is **not** the fulfilment path for a data subject's erasure request under Art. 17 — that remains the operator-executed process described under "Deletion responsibility" below, which also covers account-bound rows that no single workspace owns.
- LLM-provider-side prompts: according to the chosen provider's terms. For the TUM-operated default (Microsoft Azure OpenAI in an EU region), within the enterprise abuse-monitoring window published in Microsoft's Azure OpenAI data-privacy documentation; eligible customers may apply for Microsoft's modified abuse monitoring (Limited Access program) to suppress prompt storage and human review.
- Settings-change audit log (`config_audit_event`: who changed which workspace or AI setting, when, and the before/after values; records the acting account and, where a change was made while impersonating, the impersonator): retained 365 days, then deleted automatically. The table is append-only by database trigger, with an exception for clearing actor references on account erasure. The change record is retained for the remainder of its window. Credentials are never stored in it — a rotation records only that a secret changed.
- LLM usage ledger (`llm_usage_event`: per-run token counts and cost, attributed to a workspace and to the run that incurred them): eligible for deletion once older than the configured window (`HEPHAESTUS_LLM_USAGE_RETENTION`, default `P400D`; operator-tunable, and raisable where a commercial or tax retention obligation runs longer). A daily sweep deletes in batches within a bounded pass, so removal begins at the first sweep for which a row is eligible and any remaining backlog is cleared by later sweeps. This supports annual accounting comparisons without indefinite retention. It holds no message content and no free text; its opaque source identifier may outlive the source row; it is not a foreign key and cannot resolve a source row after that row is deleted.
- Practice-review job records (`agent_job`: the queued job, the sandbox's captured stdout, and the observations it produced — these can quote the contributor's code and discussion): the diagnostic payload (`container_logs`, `output`) is stripped to NULL 14 days after the job reaches a terminal state (`AGENT_PAYLOAD_RETENTION`, default `P14D`) and the row itself is deleted after 90 days (`AGENT_ROW_RETENTION`, default `P90D`). Both are operator-tunable.
- Artifact-source evidence uses layered retention: diagnostic job output is stripped after 14 days by default, durable job provenance (the full manifest and per-automated-review readiness report) is deleted with the job row after 90 days, and replay directories plus unreferenced CAS blobs become eligible for collection after 30 days (`hephaestus.fabric.gc-retention-days`) and are removed by a subsequent successful sweep. A digest is an integrity identifier, not authorization. Extended evaluation retention is disabled until it has a separate approved purpose, tenant-safe authorization, and the retention/erasure coverage required by the [artifact-source governance decision](./artifact-source-governance.md).
- Webhook and integration-sync event transport buffer (NATS JetStream): the `slack` and `outline` streams carry real message and document content and expire after at most 72 hours; the GitHub and GitLab streams carry delivered webhook payloads (pull-request, issue and review-comment bodies) and expire after at most 180 days. Each stream also carries a disk ceiling (`HEPHAESTUS_WEBHOOK_STREAM_MAX_BYTES`), and where ingest volume reaches it the broker discards the oldest messages first, so the actual retention is shorter than the time limit and is reported per stream as `webhook.stream.oldest.message.age`. The time limit is the maximum, not the guaranteed duration. PostgreSQL is the system of record and all consent/erasure controls apply there; erasure cannot reach inside the broker, so these horizons, not an erasure request, are what remove the buffered copy.
- Container stdout (startup and error output; no per-request records): rotated by size by the container runtime, per the per-service caps described there. There is no time-based expiry; a line survives until the rotation window displaces it.
```

**Reasoning**

```text
Hephaestus is contributor-facing. Account-bound data exists to give the data subject continuity of feedback while they participate, and is removed when they leave or on a verified erasure request (Art. 5(1)(e) GDPR storage limitation). Server-side logs and container stdout are bounded to a window short enough to limit exposure and long enough to investigate security incidents under Art. 32(1) GDPR (Art. 5(1)(c) data minimisation).
```

**Deletion responsibility**

```text
Routine retention-driven deletion (logs, container stdout): handled automatically by the runtime; ops contact AET operations team, ls1.admin@in.tum.de. Subject-rights deletion: Prof. Dr. Stephan Krusche (head of AET, responsible for this PA), with technical execution by AET maintainers on receipt of a verified request through the TUM DPO at beauftragter@datenschutz.tum.de. Identity-verification procedure and Art. 12(3) GDPR response timeframe (one month, extendable by two further months for complex or numerous requests) are described in §7 of the privacy statement.
```

**Access and portability fulfilment (Art. 15, Art. 20)**

```text
Hephaestus provides a self-service data export (Art. 20): a signed-in contributor requests an export from the in-app settings (account "Danger Zone"), the platform compiles a JSON archive of the personal data it holds about that contributor — the Hephaestus account, federated identity links, workspace memberships, account preferences, and the contributor's own authentication-event history — and the contributor downloads it from the app. The archive deliberately excludes credentials and session/signing-key material. Anything outside that scope is added by AET operators on a verified Art. 15 request: the workspace-scoped records the export omits (AI conversations, practice observations and their feedback, recognition signals, mirrored repository artefacts), product-feedback submissions and survey responses, and whatever of the requester's data still sits in the container-stdout rotation window. There are no HTTP access-log entries to disclose: no layer of the stack writes one. The IP addresses Hephaestus does hold are the ones on authentication events, which the self-service export already covers. Source-side content on GitHub or gitlab.lrz.de is exported by those source platforms, not by Hephaestus. Identity verification, response timeframe, and contact path are the same as for erasure.
```

**Deletion guarantee**

```text
- The Hephaestus sign-in account and federated identity links: covered by the in-app account-deletion control. Deletion immediately revokes all sessions and marks the account for deletion with a 48-hour cooldown; after the window a scheduled sweeper hard-deletes the account-bound rows (identity links, feature flags, the session/revocation list, export artefacts, product-feedback submissions, and survey responses or dismissals), tombstones the account's contact PII, and severs the link to the git-provider activity mirror.
- Workspace purge also removes workspace-scoped product-feedback submissions and survey responses.
- Contributor profile and dependent records: account preferences and workspace memberships are covered by the
  account/operator paths. Automated person-level traversal does not cover every SCM-only identity, AI conversation,
  observation, feedback record, evaluation label, and retained evidence copy. Operators must locate and remove or
  irreversibly de-identify those records when fulfilling a request. Extended evidence retention remains disabled
  until complete traversal is implemented and tested.
- Mirrored third-party content, on disconnection of the corresponding integration or on workspace purge (both triggers erase the same rows, by hard deletion, not by a deleted-flag):
    - GitHub / gitlab.lrz.de: the mirrored repository and everything cascading from it — issues, pull/merge requests, reviews, review threads and comments, discussions, labels, milestones, collaborators — plus the workspace's repository monitors, any local git clone, the org-level mirror (teams, team memberships, organisation memberships), the activity-event log, and the SCM-derived practice observations and feedback (whose evidence quotes mirrored content verbatim). Repository rows are instance-global and shared: a repository another workspace still monitors is retained for that workspace, and only the disconnecting workspace's access path is removed. The org-level mirror is removed only when no other workspace is bound to the same organisation.
    - Slack: messages, threads, monitored-channel records, participant-consent records, mentor threads, and the conversation-derived observations and feedback.
    - Outline: documents, collections, and the document event log.
- Retained after disconnection / purge, for all four integrations: the operational sync history (`sync_job`, `connection_activity`, `connection_audit`) — job kind, type, status and timestamps only, capped per connection, carrying no third-party content — so that the disconnection itself remains auditable. Connection credentials are cleared atomically as part of the same transition. Cross-tenant identity rows (accounts, organisations, identity providers) are not touched by a disconnection; they are covered by the account-deletion and erasure-request paths above.
- Artifact-source manifests, repository snapshots, CAS references, derived assessments, exports, and governed evaluation cases must be traversed by source, workspace, and person erasure. Expiry/erasure may leave only a non-content typed tombstone and makes replay unavailable; audit reproducibility never overrides erasure.
- Source-side content on GitHub or gitlab.lrz.de: not modified by deletion in Hephaestus.
- Container stdout (startup and error output): rotated by size by the container runtime (per-service caps under "Where stored") and not selectively prunable — an erasure request cannot reach inside the rotation window, which displaces the lines on its own.
- Off-host backups: not configured at the time of submission. Any VM-level snapshots taken by AET infrastructure operations are governed by their separate retention policy at the infrastructure layer.
```

## Technical and organisational measures (Art. 30(1)(g) + Art. 32)

```text
Pseudonymisation and encryption (Art. 32(1)(a))
- TLS-terminated at Traefik with Let's Encrypt; HTTP redirects to HTTPS with HSTS.
- Internal service-to-service traffic stays within the Docker network.
- Outbound calls to GitHub, gitlab.lrz.de, the LLM provider, and Slack are HTTPS-only. Where an operator sets a second display currency (`HEPHAESTUS_LLM_DISPLAY_CURRENCY`, unset by default), the application server additionally makes one unauthenticated HTTPS GET each weekday for the European Central Bank's public daily reference-rate file. It sends no personal data and no request parameters, so the ECB is not a recipient of personal data under Art. 30(1)(d) and needs no separate legal basis; it is listed here only to keep this inventory of outbound calls complete.
- Federated identity links to GitHub user ID / gitlab.lrz.de `sub` minimise collected identifiers; surrogate primary keys are used internally.
- PostgreSQL data at rest relies on the host's filesystem and access-control protections; application-level at-rest encryption of the general store is not enabled. Secrets are sealed at rest with AES-256-GCM under two platform-level keys: `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` protects workspace LLM API keys, login-provider OAuth client secrets, Outline webhook signing secrets, sandbox job tokens and the JWT signing keys; `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY` protects the integration connection credentials (GitHub and GitLab tokens, Slack bot tokens, Outline API keys, and the OAuth access and refresh tokens held with them), is versioned, and can be rotated online. Compromise of the first key is the blast radius for workspace secrets and session-token signing; compromise of the second exposes every connected provider's credential. Neither key is confined to the application-server container: both are injected into every container that reads an encrypted column — `application-server`, `application-worker` (the practice-review worker decrypts the workspace's LLM-provider credential to serve its in-process LLM proxy), and `webhook-server` (it verifies inbound Slack and Outline deliveries against the per-connection secret stored on the encrypted credential row) — as container environment variables, so all three are in scope for key-compromise assessment. The keys are not persisted to disk by the application and are not written to any log.

Confidentiality (Art. 32(1)(b))
- SSH key-only host access; password authentication disabled.
- End-user access via Hephaestus-native auth federating to GitHub OAuth + gitlab.lrz.de OIDC; short-lived ES256 cookie-session JWTs with server-side revocation (ADR 0017).
- Workspace-scoped membership and role checks enforced at the application layer (`@PreAuthorize` and dedicated workspace-membership filters).
- Least-privilege source-system access via per-workspace GitHub App installation or scoped access token.
- Reverse proxy exposes only required routes; everything else returns 404.
- Practice-review sandbox runs as non-root inside isolated Docker containers on per-job `--internal` networks with no DNS and no general egress; the only outbound path is a per-job, token-authenticated LLM proxy. Sandbox execution is workspace opt-in in every case: a container runs only for a workspace that has an enabled model binding for the purpose concerned. The two purposes are gated differently, and neither is on for a workspace that has bound nothing.

  - *Queued practice review* additionally requires the operator to set `AGENT_ENABLED=true`. The deployment's compose files pass that variable to both the application server and the worker, defaulting it to `false`, and no runtime profile overrides it — so a worker started outside those compose files, on the `worker` Spring profile, is equally off until the variable is set. It gates the job queue: with it unset, no practice-review job is submitted, claimed or executed.
  - *Interactive mentor conversations* are request-affine and do **not** go through that queue, so `AGENT_ENABLED` does not gate them. Their sandbox is activated by the worker runtime capability (`hephaestus.runtime.worker.enabled`), which defaults to `true` and is on for the application server. A workspace with an enabled mentor model binding therefore executes sandbox containers even where `AGENT_ENABLED` is `false`. Withholding the capability, not the variable, is what stops all sandbox execution on a given container.
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
- No scheduled off-host backup of personal data is configured in the application repository at the time of submission. The PostgreSQL container uses a named Docker volume on the host. The authoritative copies of pull/merge-request content live on GitHub and gitlab.lrz.de. Loss of the host-local PostgreSQL volume would lose Hephaestus-specific state (workspace state, observations, practice configurations) — risk accepted at this scope, with off-host backup tracked as an open AET-ops item.

Testing and evaluation (Art. 32(1)(d))
- CI runs CodeQL (GitHub Default Setup), Trivy (filesystem and container image), TruffleHog secret detection, and Renovate dependency updates.
- Unit, integration, and end-to-end tests run on every change.

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

Optional academic-research participation: Art. 6(1)(a) GDPR. It is separate from the terms and from the public-task basis for platform operation. Research enrollment and analysis require the latest `RESEARCH_PARTICIPATION` decision to be a grant for the current notice version; they do not fall back to a preference flag or another legal basis after withdrawal. The append-only ledger records grants, refusals and withdrawals with a UTC timestamp, mechanism, notice version and SHA-256 digest. `consent_notice` preserves the corresponding first-layer text. Withdrawal ends the authorization for further research processing immediately. Account erasure removes the ledger's account reference; the resulting non-account-linked event remains with the archived notice as evidence of how consent was managed.

Product feedback and surveys for improving the TUM-operated instance: Art. 6(1)(e) GDPR i.V.m. Art. 2 BayHIG and Art. 4(1) BayDSG. Responses are not reused for research without the separate research opt-in.

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
- From the HTTP connection: nothing. No layer writes a per-request record. The source IP address and user agent are collected as such only for authentication events, and are retained under the auth-event log's own 12-month window described above.
```

## Information duty (Art. 13)

- https://hephaestus.build/privacy
- https://hephaestus.build/imprint

Markdown source under `webapp/public/legal/profiles/tumaet/`.

## Other Remarks (DSMS form vendor-pool comment)

```text
Bitte folgende Auftragsverarbeiter zum AET-Pool hinzufügen, soweit noch nicht vorhanden: GitHub Inc. (USA), Microsoft Corp. (Azure OpenAI Service, USA/EU), OpenAI OpCo, LLC (USA) ggf. mit OpenAI Ireland Ltd. (Irland) als EWR-Vertragspartner, Salesforce / Slack Technologies, LLC (USA). Beschreibungen unter "Recipient Categories"; Drittlandtransfers durch das EU–US Data Privacy Framework und Standardvertragsklauseln Modul 2 (jeweils im Rahmen des einschlägigen Enterprise-AVV) abgedeckt; DPF-Status pro Empfänger vor Anbindung verifizieren.
```
