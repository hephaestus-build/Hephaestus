---
title: Personal-data map and erasure verification
description: The authoritative map from personal-data stores to export, retention, and erasure controls.
---

# Personal-data map and erasure verification

This inventory maps each personal-data store to its removal control and executable evidence. Before a store or derived copy receives personal data, complete these steps:

1. Add it here.
2. Implement its export and erasure operations.
3. Add focused behavioral tests.

`PersonalDataMapArchTest` fails the build if neither this map nor its own non-personal-data table list names a database table. It also fails if a source or test cited below has moved.
The architecture test also requires a single catalog owner and citations in the dedicated
implementation table. A mention elsewhere in prose cannot satisfy ownership.

Workspace purge applies storage limitation when a workspace loses its purpose. A verified
person-erasure request follows the [instance-admin procedure](../production-operations-runbook#access-and-erasure-for-one-person).
The implementation inventory below owns each store's exact selection, export and erasure citations.
The frozen scope includes reviewed work where the person had any of these roles:

- Author, merger, commenter or reviewer.
- Assignee or requested reviewer.
- Contributor of a linked commit.

Evidence copies use that same
source scope, including when their primary author is another person. Results, problems and feedback, with
their deliveries, are selected only when the person is the developer they are about or their recipient.

Provider-owned source attribution is shared by scope selection and processing suppression:
`server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/ScmPersonSourceIdentityContributor.java`,
`server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/slack/SlackPersonSourceIdentityContributor.java`
and `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/outline/OutlinePersonSourceIdentityContributor.java`.
Each guard checks the requesting workspace in SQL. Mirror sync cannot bypass a native-identity control.

OAuth state nonces record only a new exact initiating account. Exports omit the nonce capability, and erasure deletes its row. Older rows have no inferred account attribution.

The self-service account export and cooldown remain separate, narrower operations.

| Store / copy | Data and access path | Workspace removal | Person erasure | Residual retention and evidence |
|---|---|---|---|---|
| PostgreSQL account, identity and authentication rows — `account`, `account_feature`, `account_public_activity_hidden_by_default`, `account_export`, `identity`, `issued_jwt`, `consent_decision`, `auth_event`, `config_audit_event` | Sign-in identity. Self-service export plus instance-admin person export | Account-global rows remain | Self-service: 48-hour cooldown, then account purge. Instance-admin person job: account purger after the other store steps | Authentication history retains references to the account tombstone, with IP address, user agent and details cleared on erasure, and expires in rolling partitions. Settings-change history detaches actor references and expires. Exports expire. [Data retention](../data-retention.mdx) gives the periods. `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/AccountHardDeleteSweeperIntegrationTest.java` and `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/export/AccountExportRetentionIntegrationTest.java`. |
| PostgreSQL product feedback and surveys — `product_feedback`, `product_survey_participation` | Data supplied by the account holder. Instance-admin person export | Purge contributors remove workspace-owned rows: feedback sent from the workspace, surveys targeted at it and participation recorded from it | Account purge removes account-owned rows through `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/FeedbackAccountErasureAdapter.java`, because the account tombstone leaves the `ON DELETE CASCADE` unfired | No independent time-based expiry. Workspace removal: `server/application/src/test/java/de/tum/cit/aet/hephaestus/workspace/WorkspacePurgeIntegrationTest.java` (`purgeRemovesTheProductRowsTheWorkspaceOwnsAndKeepsInstanceWideSurveys`). Person erasure: `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/AccountHardDeleteSweeperIntegrationTest.java` (`shouldEraseProductSubmissionsWhenAccountIsPurged`). |
| PostgreSQL contributor profiles — `user` | Mirrored contributor identity. Self-service export plus instance-admin person export | Instance-global rows remain | Instance-admin person contributor anonymizes the exact provider profile. Self-service account deletion does not remove these rows | No independent time-based expiry. The operator's [record of processing](./record-of-processing.md) defines the operator responsibility. Personal settings, such as the AI choice and feedback delivery, are columns of `account`. |
| PostgreSQL SCM mirror — `organization`, `organization_membership`, `team`, `team_membership`, `team_repository_permission`, `repository`, `repository_collaborator`, `pull_request`, `pull_request_requested_reviewers`, `pull_request_review`, `pull_request_review_comment`, `pull_request_review_thread`, `issue`, `issue_assignee`, `issue_comment`, `milestone`, `discussion`, `discussion_comment`, `git_commit`, `commit_contributor`, `commit_file_change`, `project`, `project_item`, `project_field_value`, `project_status_update` | GitHub/GitLab profiles and authored work. Source-provider export plus instance-admin person export | Repository removal erases a source repository once no workspace monitors or keeps it. A stopped GitLab project stays kept until **Remove synced work**. Connection removal and workspace purge erase the active mirror, including kept projects | Exact provider instance and native user matching. Authored content is cleared and shared actor roles are detached separately | `server/application/src/test/java/de/tum/cit/aet/hephaestus/workspace/ScmWorkspaceErasureIntegrationTest.java`. |
| PostgreSQL Slack mirror — `slack_message`, `slack_thread`, `slack_monitored_channel`, `slack_participant_consent`, `slack_channel_consent_event` | Messages, identities and attribution. Instance-admin person export | Disconnect and workspace purge | Slack opt-out and instance-admin person contributors | `server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/slack/retention/SlackRetentionErasureIntegrationTest.java` and `server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/slack/interactivity/SlackAppHomeOptOutErasureIntegrationTest.java`. |
| PostgreSQL Outline mirror — `outline_document`, `outline_collection` | Documents and attribution. Instance-admin person export | Collection removal, disconnect and workspace purge | Instance-admin person contributors | `server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/outline/lifecycle/OutlineWorkspacePurgeAdapterIntegrationTest.java`. |
| PostgreSQL conversations — `mentor_conversation`, `mentor_entry`, `mentor_turn` | One main conversation for each account in each workspace (`mentor_conversation.account_id`), with its threads, messages, weekly digests, votes and turn usage. Instance-admin person export of the account's own conversations, without tool results and summaries. Each exported message holds the words that the app shows, including the words of each link that Hephaestus showed. The export also has the Hephaestus messages in other conversations that showed feedback about the person | Workspace purge deletes them. Slack disconnect erases every message written in Slack and the copies of its words. It stops running Slack turns and clears the Slack DM identity of each conversation | Deletes the account's own conversations. Redacts Hephaestus messages in other people's conversations that showed feedback about the person (`MentorFeedbackCopyPersonDataStore`). As the last step, redacts every `read`, `bash` and `load_context` result in the person's workspaces and removes their turn sandboxes (`MentorToolResultCopyPersonDataStore`). After the erasure request, Hephaestus refuses a write for the account with `PERSON_ERASED`, and a turn that ends stores nothing | No independent time-based expiry. Account absorption moves the conversations to the claiming account and merges two main conversations of one workspace |
| PostgreSQL practice proof — `result`, `problem`, `result_mark`, `feedback`, `feedback_basis`, `feedback_copy`, `feedback_send`, `feedback_decision`, `feedback_response` | Results, guidance, responses and actual copy receipts | Workspace purge deletes scoped records through their owners | Erasure removes affected feedback and source-person proof before membership | Retention does not erase a referenced result while retained feedback still cites it. Physical receipt custody remains until mounted removal is acknowledged |
| PostgreSQL operation history — `connection_audit`, `sync_job`, `item_event`, `review_batch`, `review_schedule` | Source events, actor/requester attribution and operation history | Workspace-owned records follow their owner purge | Audit actors become null without changing recorded actions. Provider restrictions survive erasure | [Data retention](../data-retention.mdx) gives the period of unreferenced terminal item events |
| PostgreSQL retained occasion evidence — `item_event.evidence` | A pull request or submitted review occasion's title, description, review words, branch and state, as stored when the occasion was recorded. The provider identities and logins of its author, assignees, merger and reviewer, as stored then. At most 1 MiB for each occasion. The personal export lists the occasion without these words | Workspace purge, SCM mirror erasure and removal of the item from the workspace's source remove it. The occasion row stays | Erasure of any person the evidence copied removes the whole evidence, also when that person was not the actor | Expires at the item event retention horizon after it was recorded ([Data retention](../data-retention.mdx)), also while a review references the occasion. No later decision or retry extends it. The same deadline bounds writer inputs and raw session copies. Physical removal follows the existing sweep and runtime leases |
| PostgreSQL instance-administration records — `instance_settings`, `instance_llm_settings`, `product_survey` | The stable account reference of the administrator who changed silent mode or instance LLM policy, and the account that authored a survey | Instance-scoped. Workspace purge does not reach them | Account purge detaches settings actor references. Person erasure also detaches the survey creator. | The upgrade drops old settings display-login attribution without backfill. Settings, survey definitions and change times remain. `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/settings/SettingsAccountErasureAdapter.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/catalog/LlmSettingsAccountErasureAdapter.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/adapter/ProductfeedbackPersonDataCatalog.java`. |
| PostgreSQL runtime — `review_job`, script execution, capture receipts and diagnostics | Temporary inputs, output and provenance | Workspace purge queues mounted evidence removal before parent deletion | Erasure removes all source-person dependencies, including another developer’s review | [Data retention](../data-retention.mdx) gives the payload and job periods. Product reviews and results do not depend on retained jobs |
| PostgreSQL transient composition — `review_composition_basis` and `person_evidence_copy` | Original bounded inputs and physical custody for an attempt | Purge retains physical cleanup receipts until acknowledgement | Erasure follows every copied person, not only the result developer | [Data retention](../data-retention.mdx) gives what removes receipts |
| PostgreSQL product reviews — `review`, `review_practice`, `review_item` | Pinned versions, method, per-item proof membership and aggregate report | Workspace purge uses the report owner | Person erasure removes personal proof and affected membership, including developerless file proof | [Data retention](../data-retention.mdx) gives the period of raw scan proof. Aggregate reports remain without quotes or personal identity and identify expired proof |
| PostgreSQL `llm_usage_event` | Token/cost accounting with workspace and opaque source identifier. Monthly workspace/admin reports | Retained for accounting until its retention sweep | Person contributors detach the selected job/conversation source identifiers while retaining accounting facts | [Data retention](../data-retention.mdx) gives the window and its operator control. `server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/usage/LlmUsageRetentionIntegrationTest.java` proves the cutoff across workspaces. `server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/usage/LlmUsageRetentionSweeperTest.java` verifies job outcomes. |
| PostgreSQL account passkeys — `passkey_credential`, `passkey_challenge`, `passkey_recovery_code` | Account-bound public credentials, browser-session challenges, and recovery-code hashes. Self-service export includes names, dates, and the personal protection choice, not credential blobs or recovery codes | Account-global rows remain | Account purge removes all three through `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/auth/webauthn/PasskeyService.java` | Challenges expire after five minutes and are removed on use or later challenge creation. Recovery invalidates all previous credentials and codes. `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/webauthn/PasskeyLifecycleIntegrationTest.java`. |
| PostgreSQL installed-client sessions — `client_session`, `client_sign_in_handoff` | Which installed clients (the browser extension) are signed in, and the hashes of their single-use sign-in codes. Refresh-secret hashes live on `issued_jwt` | Account-global rows remain | Account purge removes both through `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/auth/AccountPurger.java` | A sign-in code expires after 60 seconds. A session ends at its absolute deadline, on sign-out or on revocation. It becomes eligible for deletion with its token rows at absolute expiry or one day after revocation, whichever comes first. The hourly cleanup skips locked rows until a later pass. See `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/auth/clientsession/ClientSessionPruner.java`. `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/clientsession/ClientSessionIntegrationTest.java`. |
| PostgreSQL notification subscriptions — `notification_subscription` | Native account id, category, opt-in state/time and opaque unsubscribe capability. The `MENTOR_DIGEST` category records the weekly digest choice | Account-scoped, not workspace-owned | `NotificationAccountErasureAdapter` removes rows during account purge. Export includes choices but no capability | Retained until account erasure. Each capability only disables one subscription. Unsubscribe does not erase the opt-out, so old links remain valid. |
| PostgreSQL survey email invitations — `product_survey_email_invitation` | Survey/account/workspace ids, requesting administrator, request/expiry/cancellation and relay-acceptance facts. No address or answers | Workspace purge removes scoped rows. Survey deletion removes its invitations | Account erasure removes recipient rows and clears the erased requesting administrator's reference | Retained with the survey. Separate from in-app participation and never a proxy for opened/read email. |
| PostgreSQL notification outbox — `event_publication` | The Spring Modulith event publication registry has one row per pending or failed notification listener. It holds the serialized event and its delivery attempts. The event holds source and recipient ids, bounded lifecycle information and timestamps, never an address or report text. Instance-admin person export | Instance-scoped: workspace purge does not directly reach it. Delivery rechecks source and membership | The row holds ids only. The listener resolves the address at send time from `account.primary_email`. Account purge clears that field. A resubmitted notification for a purged account therefore has no recipient and completes as withheld | Completed publications are deleted. Account-deletion confirmations expire at the purge deadline. Other kinds use their bounded lifecycle window ([Data retention](../data-retention.mdx)). Source erasure, opt-out or lost eligibility completes queued mail without sending on the next attempt. `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/NotificationRedeliveryJob.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/AccountDeletionEmailListener.java`. |
| NATS JetStream | Buffered webhook, message and document payloads. Not directly included in self-service export | No selective deletion | No selective deletion | Streams expire by age and byte ceiling ([Data retention](../data-retention.mdx)). Stream configuration tests enforce these bounds. |
| Worker-local repository mirrors and attempt folders | Person export lists exact-linked repository history and safe mounted-copy facts, not raw Git objects or other profiles | Mirror sweep follows repository monitoring. Workspace purge durably queues attempt-folder removal before deleting job rows, and the mounted worker confirms removal after commit | Person erasure cancels affected attempts and waits for mounted-store acknowledgement through `EvidenceFolderPersonDataCatalog`. Repository owners erase Git authorship at the source, not by rewriting Hephaestus mirrors | Attempt-input lifetime follows [ADR 0041](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0041-compose-1x-kubernetes-2.md#evidence-admission-and-deletion). [`item-source-governance.md`](./item-source-governance.md) owns erasure requirements. Offline mounts retain a removal receipt until acknowledgement. |
| Research datasets outside the instance database, held by the research team at `[location]` | Pseudonymized copies of data that a participant allowed for research, and the key that links codes to people, stored apart from the data | Workspace purge does not reach them | The person-erasure job does not reach them. The operator removes the account's data from datasets that are not yet anonymized | Kept while the research continues, then deleted or anonymized. Anonymized datasets cannot be reached and can remain. See "Research use of existing stores". |
| Configured LLM provider | Prompts and responses for enabled purposes. Provider/operator access process | A completed request cannot be retracted | Provider process | Deployment-specific provider terms must define the bound before processing starts. See the [processor checklist](./processor-checklist.md). |
| GitHub/GitLab/Slack delivery destination | Posted feedback. Source-provider export | Not silently crawled or rewritten | Best-effort correction/removal through the operator path | The provider controls its copy and audit history. |
| Application metrics | Aggregate operational counts without account, workspace, export or source labels | Not applicable | No subject-level series exists | Backend retention is deployment-specific. Operators must record it. |
| Optional trace export and log collection | Trace timing and outcome. Collected log lines with stacks, opaque identifiers and operational correlation | Recipient-specific process | Recipient-specific process. A log store keeps its lines until its retention removes them | Off by default. Configure and document exact recipients, lawful bases and retention before enabling either. The processor checklist owns the approval evidence. |
| External avatar hosts and browser caches | Image request address and visitor network metadata, including on public pages | Host/browser process | Host/browser process | No Hephaestus export or erasure of a third party's request logs. Record actual hosts and their privacy terms. |
| Container logs and support bundles | Operational output. Logging policy excludes raw request bodies, model output, sandbox transcripts and secrets. Lines still carry opaque identifiers and can carry library exception text, so operators must assess exported bundles | No selective deletion | No selective deletion | Shipped container logs rotate by size ([Collecting logs](../observability.mdx#collecting-logs)). Support-bundle retention is deployment-specific. |
| PostgreSQL and filesystem backups | A copy of the backed-up personal-data corpus | No selective deletion inside a backup | Expiry of the backup copy | The optional encrypted off-host PostgreSQL backup overlay retains full backup chains by count, not by a legal time limit. Operators must document and enforce destination retention, including copies of `.env` and broker evidence. See [backup and restore](../backup-restore.mdx). |

## Operator verification

Each erasure, export and retention job publishes a bounded success/failure counter and an
affected-row counter. The names, labels and cardinality guarantee are in the
[observability contract](../observability.mdx). Alert configuration remains deployment-owned.

1. Alert on `outcome="failure"`.
2. Alert on `outcome="incomplete"`.
   This means a retention pass exhausted its time budget with expired rows still in place.
3. Alert if more than 48 hours pass without a successful daily `llm_usage_retention` run.

Review this map with your [record of processing](./record-of-processing.md),
[source governance](./item-source-governance.md), and the public privacy statement.

## Member onboarding and AI choices

`account` stores an account's AI choice (`ai_choice`: `NO_AI`, `IN_HOUSE_ONLY` or
`CLOUD`) and when it was last changed. It is answered once and holds in
every workspace the account is a member of. A row exists only once the person has answered. The
account export includes it with the choice as that literal. Account erasure deletes it. Workspace purge never touches it.

`membership` stores which settings revision an account
finished or skipped a workspace's setup page at (`seen_revision`). It carries no choice and no free
text, and is removed with account erasure and with workspace purge.

`workspace_onboarding_settings` stores whether the setup page is on, whether a choice is required, and required integration
identifiers. It holds no free text and is removed with workspace purge.

`llm_model` and `workspace_llm_model` carry the declared operator (`operated_by`) and a data-handling note
(`data_handling_note`), an admin-only field for region, agreement, renewal date and retention
details. It is shown to admins only, never to developers, and must not name individuals. None of these tables has an independent expiry. Configuration changes also follow the
existing configuration-audit retention policy.

## Account membership and choices

Membership keeps the account, workspace role, review selection and hidden preference.
Logical removal records leftAt without granting current access.
Unclaimed human accounts can exist before sign-in. Bots remain provider actors.

AI choice and external delivery preference apply across the person's identities and workspaces.
Erasure deletes dependent proof and feedback before membership, without anonymizing personal results by clearing developer identity.
Identity restrictions retain provider and team keys after account removal.

## Research use of existing stores

Research use processes stores that this map already lists.
The latest `RESEARCH_PARTICIPATION` decision in `consent_decision` gates it.

A research dataset release adds one store, the disclosure record: `research_disclosure` and `research_disclosure_subject`.
It holds no dataset payload. It records which participant key each released file used for each account, and the exact grant that allowed the release.
An instance administrator with a fresh passkey can read which keys must be removed, and why.

Person erasure drops the account link, but keeps the key and records that removal is owed.
Recording that the researchers removed a participant drops the link and the grant reference.
Recording that the whole copy was deleted or anonymized deletes the subject rows.

The disclosure header keeps the releasing administrator's account until that account is erased.
An open disclosure blocks workspace purge.
Before any further analysis, the research team checks the removal list. Hephaestus cannot revoke a downloaded copy.

A decision authorizes research only for the current notice version and the current research organization (`consent_decision.research_organization`).
An earlier yes to narrower wording authorizes nothing. Every account answers once more when the wording changes.

The research can use this data of a participant who allowed it:

- Work in connected repositories and tools: pull/merge requests, issues, reviews, comments and chat.
- Hephaestus practice results, their problems and practice feedback about that work.
- The participant's responses to that feedback.
- How the participant uses Hephaestus, including conversations with Hephaestus and research survey answers.

The research uses no sign-in credentials or access tokens.
A participant who stops Hephaestus from using their Slack messages has those messages erased from the instance database. The research team removes them from datasets that are not yet anonymized, and research does not use them again. Anonymized datasets cannot be linked to the participant and can remain.

### Where research copies live

The research team holds research datasets outside the instance database, at `[location]`.
Before analysis, a code replaces name, username and contact details. The key that links the code to the person is stored apart from the data.
Only the research team can reach the key.

### How withdrawal and erasure reach research copies

Withdrawal and person erasure act on the instance database. They do not reach research copies.
The operator must close that gap:

1. Apply the latest `RESEARCH_PARTICIPATION` decision of each account before any export or analysis.
2. After withdrawal or a verified erasure request, remove the account's data from datasets that are not yet anonymized.
   After a Slack message-use opt-out, remove the affected messages the same way.
   For a released research dataset, the removal list names the dataset and the participant keys to remove.
   It also names a key whose grant was withdrawn and later given again.
   Remove the participant's own rows.
   Also remove every review whose subject list contains the key.
   Include each such review's shared facts, such as usage and assignment counts.
   A participant can be named on a review with no row of their own in the file.
   If the copy cannot be cleaned selectively, delete it and record the closure. A new release can replace it.
3. Finish the removal without undue delay. This is a manual step of the research team.
   Record the removal, or record that the whole copy was deleted or anonymized. Hephaestus cannot check the research team's storage. The record is the operator's statement.

An anonymized dataset holds no link to a person. Withdrawal and erasure cannot reach it, so its data cannot be removed.

### Residual retention of research copies

- Pseudonymized research data and the key: the [record of processing](./record-of-processing.md) and the public notice give the retention criterion. No fixed period applies. The team then deletes or anonymizes them.
- Anonymized datasets: outside the GDPR. They can remain and be published with no end date. They cannot be removed.
- Consent ledger rows: unchanged. They are append-only.

The operator's [record of processing](./record-of-processing.md) owns the research purpose, safeguards and retention reasoning.
The operator's DPIA owns the risks.

## Person request implementation inventory

Exact identity resolution also includes registered provider rows whose type and normalized network
origin are identical. Host case and a default port do not create a different provider instance. The
native subject and Slack workspace key must still match exactly. A conflicting account link on any
such row stops the request. Display attribution is never used to resolve it.

Permanent suppression
and native write locks use that same type, origin and native subject, including provider rows
registered after erasure. Adding an equivalent provider row cannot reset a processing control.

Slack workspace keys still distinguish suppression decisions. Sharing a lock does not merge teams.
Hephaestus checks these exact processing controls when a question arrives and again when a worker starts its turn.
An eligible model or a recreated mirrored profile cannot bypass an erased developer identity.
This entry-point check does not itself remove copies that a running turn already holds.

The user, workspace and authored-work context sources read their current data for each turn.
They do not retain formatted personal-content caches. The unrelated practice-catalog cache remains.
It contains workspace practice definitions, not copied developer profiles.

Source connections register their provider instance even when nobody signs in through that provider.
`server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/SourceProviderNamespaces.java`
runs before activation and before an exact source URL spelling changes. Registration and native-control
inheritance commit together, under capture admission. The synchronous listener in
`server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/IdentityRestrictionService.java`
copies only exact type/origin/subject/Slack-workspace controls. It does not infer account ownership.

Existing sources are registered by
`server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/PersonSourceProviderBackfillChange.java`.
A bound connection cannot be changed to a different network origin. A legacy Outline mirror without
an exact provider instance stops the upgrade instead of guessing a binding or deleting visible work.

Each active database store has an export and erasure contributor in its owning module. The shared
mechanics in `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/spi/JdbcPersonDataStore.java`
apply only the frozen row keys. The catalog owns the exact identity predicate, export field allowlist
and deletion or anonymization policy. A new store must have both operations. Classification alone is
not enough. Shared provider metadata has an explicit empty person selection, not a name match.

| Store | Person export implementation | Person erasure implementation |
|---|---|---|
| `activity_event` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/activity/adapter/ActivityPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/activity/adapter/ActivityPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `activity_automation` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/activity/adapter/ActivityPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/activity/adapter/ActivityPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `person_evidence_copy` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/EvidenceFolderPersonDataCatalog.java` (`selectCopies`, safe copy facts, raw Git objects excluded) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/EvidenceFolderPersonDataCatalog.java` (`prepareErasure`, mounted-store acknowledgement, `erase`, idempotent receipt removal) |
| `person_data_store_administration` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/PersonDataStoreAdministrationContributor.java` (`select`, exact acting account, `export`, operational step facts) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/PersonDataStoreAdministrationContributor.java` (`erase`, clear the exact account reference and retain counts and times) |
| `review_job`, `review_composition_basis`, `llm_usage_event`, `instance_llm_settings` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/AgentPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/AgentPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `account` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CoreAccountPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CoreAccountPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `passkey_credential`, `passkey_challenge`, `passkey_recovery_code`, `account_feature`, `account_public_activity_hidden_by_default`, `identity`, `issued_jwt`, `client_session`, `client_sign_in_handoff`, `account_export`, `consent_decision`, `auth_event`, `config_audit_event`, `config_audit_event_acting_account`, `config_audit_event_membership_subject`, `instance_settings`, `event_publication`, `person_data_request`, `person_data_request_administration`, `identity_restriction` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `git_repository_history` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/privacy/ScmPersonDataCatalog.java` (`GitRepositoryHistoryPersonDataStore.select`, repository references only) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/privacy/ScmPersonDataCatalog.java` (`GitRepositoryHistoryPersonDataStore.erase`, preserves upstream caches. Affected attempt folders use the evidence-erasure hook) |
| `user`, `organization_membership`, `team_membership`, `repository_collaborator`, `issue_assignee`, `pull_request_requested_reviewers`, `organization`, `team`, `team_repository_permission`, `repository`, `milestone`, `issue_comment`, `pull_request_review`, `pull_request_review_comment`, `discussion_comment`, `issue`, `pull_request_review_thread`, `discussion`, `git_commit`, `commit_contributor`, `commit_file_change`, `project`, `project_item`, `project_status_update`, `project_field_value`, `git_commit_committer`, `git_commit_authored_content`, `issue_merger`, `discussion_answer_actor` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/privacy/ScmPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/privacy/ScmPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `oauth_state_nonce`, `connection_audit`, `sync_job`, `item_event`, `item_event_evidence`, `item_event_requester` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/IntegrationCoreConnectionPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/IntegrationCoreConnectionPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `outline_document`, `outline_collection`, `outline_document_editor`, `outline_document_content`, `outline_document_collaborator` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/outline/adapter/IntegrationOutlinePersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/outline/adapter/IntegrationOutlinePersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `slack_thread`, `slack_message`, `slack_participant_consent`, `slack_channel_consent_event`, `slack_monitored_channel` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/slack/adapter/IntegrationSlackPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/slack/adapter/IntegrationSlackPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `mentor_entry_tool_result_copy`, `mentor_entry_feedback_copy`, `mentor_entry`, `mentor_turn`, `mentor_conversation` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/mentor/adapter/MentorPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`), `server/application/src/main/java/de/tum/cit/aet/hephaestus/mentor/adapter/MentorEntryPersonDataStore.java` (the words that the app shows for each message) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/mentor/adapter/MentorPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `mentor_turn_attempt_usage` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/MentorAttemptUsagePersonDataCatalog.java`. `select` freezes the person's own conversation turns with their workspaces, also before a measurement exists. `export` gives the counts of each attempt of those turns. | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/MentorAttemptUsagePersonDataCatalog.java`. `prepareErasure` keeps the observed spend, deletes the measurements and detaches the turns' ledger rows. `erase` deletes what is left. The person-data write fence refuses registration after the account's suppression. |
| `notification_subscription` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/preferences/adapter/NotificationPreferencesPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/preferences/adapter/NotificationPreferencesPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `research_disclosure_subject`, `research_disclosure` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/research/adapter/ResearchDisclosurePersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/research/adapter/ResearchDisclosurePersonDataCatalog.java` (`erase` policy: drop the account link and record the owed removal, `JdbcPersonDataStore.erase`) |
| `feedback_send`, `feedback_response`, `feedback_copy`, `feedback_basis`, `feedback_decision`, `feedback`, `result_mark`, `review_item`, `problem`, `result`, `result_mark_created_actor`, `result_mark_removed_actor`, `feedback_decision_actor`, `review`, `review_batch`, `review_schedule` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/adapter/PracticesPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/adapter/PracticesPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `product_feedback`, `product_survey_participation`, `product_survey_email_invitation`, `product_survey`, `product_feedback_resolver`, `product_survey_invitation_requester` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/adapter/ProductfeedbackPersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/adapter/ProductfeedbackPersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |
| `membership`, `workspace_onboarding_settings` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/adapter/WorkspacePersonDataCatalog.java` (`select`, explicit export fields, `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/adapter/WorkspacePersonDataCatalog.java` (`erase` policy, `JdbcPersonDataStore.erase`) |

A `HIDE_PUBLIC` row in `identity_restriction` retains only the provider id and provider user key.
It honors a public-page objection, or the absence of an answer, after identity unlink or account purge.
An account that never chose to show is hidden by default, so an unlink also writes this row for an account that has not answered.
It is included in the person export and retained on erasure, like a suppression key. It does not stop internal processing.
An explicit Show me choice clears only currently owned, linked identities.

`account_public_activity_hidden_by_default` holds only the ids of the accounts that the hidden-until-answered upgrade hid.
A rollback of that upgrade restores exactly those accounts.
An explicit choice removes the row of the account. Erasure deletes it, and the person export includes it.

### Shared source content and Hephaestus tool results

Milestones are selected by their exact creator reference. Commit file-change copies are selected by
the exact author or co-author reference, never by a path, name or email. Erasure clears milestone
content and attribution while retaining its operational row, and deletes selected file-change copies
before contributor attribution is detached. A person who only applied another developer's commit
loses their committer attribution. The other developer's file-change rows remain.

Hephaestus exports the person's conversations and threads, their questions, Hephaestus's answers, weekly digest
entries, votes and turn usage. It does not export
tool results, summaries or Pi runtime entries: these can contain context about other people rather
than only the person's conversation. They are deleted with an erased person's conversations.

Another member's conversation can hold tool results that read data about the person. These
include their membership, conversations, documents, authored work, the reviews and comments they wrote, the
commits they authored, or a review whose evidence contained them.

A `read`, `bash` or `load_context` tool result can copy the data of anyone in its workspace. These tools read
Hephaestus's context folder.
A `search_conversation` result quotes the conversation's own entries, and these entries can repeat what a
context folder result showed. Erasing an entry also erases the `search_conversation` results that quote it.
Other tool results copy no one's data.

The preview freezes every workspace that holds
the person's data. Erasure redacts every context folder result in those workspaces, including results
written after the preview, and exports none of them. This is the last step of the erasure. It runs
after every step that erases data that a context folder result can read. It also removes the turn
sandboxes of those workspaces.

Other tool results stay. Hephaestus's messages stay whole, including their stored hidden reasoning and
tool-call arguments. These can repeat what a context folder result showed.

A conversation turn stores its context folder results when it ends. Its end waits until a running erasure
finishes. If the last step of an erasure ran after the turn started, the end stores those results
redacted. That step does not record its workspaces, so this applies to turns in every workspace.

A turn that starts after that step reads only data that the erasure already removed. If an earlier step
fails, the last step has not run yet. It runs when the erasure resumes and then redacts the results
that turns wrote in the meantime.

A redacted entry keeps its row and position. Every summary that could have read it is redacted too,
so Hephaestus's next turn summarizes the conversation again from the remaining entries. A redacted tool
call and its result keep only their pairing. A redacted question keeps only its Pi entry id.

Content admission and erasure use transaction-scoped native-identity locks through
`server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/NativePersonDataWriteFence.java`.
The erasure side obtains its locks before the final preview check. Writers use READ_COMMITTED, hold a shared lock until
their content transaction commits and check the permanent control after acquiring it. A lock can
serialize team variants or hash collisions, but it never makes them the same person.

Shared Outline documents and co-authored commits can contain the person's work even when another
person is the primary author. Export includes those exact source-content copies without the other
author's profile. Erasure clears the inseparable local content copy and keeps the other author's
stable attribution and operational facts. Names and email addresses are not used to split content.

Conversation exports contain visible message text and numeric usage facts. Tool input/output,
error text, Pi payloads and hidden reasoning are excluded: they can contain credentials or copied
profiles from another person. The export columns are declared in
`server/application/src/main/java/de/tum/cit/aet/hephaestus/mentor/adapter/MentorPersonDataCatalog.java`.
Canonical stored messages are not changed by export.

Slack AI projections check the native author and Slack team in SQL. Outline AI projections check
native creator, editor and collaborator subjects in the document's provider instance. These checks
apply to direct reads, workspace inputs and ranked search before its result limit. Provider sync may
still mirror upstream records. The controlled rows do not return to these AI input queries.

Feedback copied into another person's conversation is selected from its exact delivery message
ID and workspace. The export includes the copied message, not the other person's profile or replies.
Erasure redacts the linked message and every summary that could have read it. It preserves replies,
message order and conversation times. A delivery workspace mismatch stops preview.

Activity reconciliation acquires one ordered set of native-identity transaction locks before filling
unresolved commit actors. It updates only admitted authors. An erased author cannot reappear in the activity ledger. This does not prevent reconciliation of other authors in the same batch.

An activity row with no resolved actor is still included when its stable work reference identifies
the person's authored commit, issue, pull request or comment. Another person's resolved actor is
not replaced or removed by that source-based selection.

## Git repository history and short-lived attempts

Person export lists repositories whose history may contain the person's commits under
`git_repository_history`, using exact stored provider-user references for authors, committers and
contributors. It never matches Git names or email addresses. The list includes provider and native
repository IDs and the repository URL, not another person's profile.

Raw Git objects in mirrors and job folders are caches of the upstream repository controlled by the
workspace's organization. Hephaestus does not rewrite Git history: that would change commit IDs, break
citations and be undone by the next fetch. Git authorship inside repository history must be erased
at the source repository by its owner. Sync can still mirror records that remain upstream.

Hephaestus erases its own person records, derived data and collected copies. Person erasure cancels
affected in-flight attempts and deletes their evidence folders through the evidence-erasure hook.
The job-folder contract does not change: admission deletes the verified folder, ended attempts have
a one-hour cleanup grace, and restart cleanup removes abandoned folders. One owner at a time removes a job's
folders, and cleanup retries a job that another owner holds on its next pass. Repository history is not
exported as an unfiltered Git object archive.

Workspace purge queues removal of all mounted attempt copies before deleting job rows. The request
commits with the workspace purge. It does not wait for a worker inside that database transaction.
An offline mounted store keeps its receipt and exact identity keys until it returns and confirms
folder deletion. After that confirmation, the next review job retention run deletes the receipts of the
purged workspace. Person erasure still waits for every selected store to acknowledge deletion.

### Copied evidence and secondary subjects

A person can appear in a review's captured evidence without being its primary developer. The preview
freezes the exact job IDs from native-identity capture receipts. That job is not exported. Its
evidence snapshot and diagnostics, its evidence folder and the Hephaestus tool results in its workspace
are erased through those IDs. The practice results and feedback it recorded about another developer stay
and are not exported.

A receipt's native provenance stays after folder deletion is acknowledged until
the person-erasure step clears it. For a deleted job, review job retention can delete the receipt first. Unrelated review jobs and
other people's profiles and replies are not selected through repository history alone.

Provider-feedback inspection also covers unconfirmed summary and inline writes.
`ExternalFeedbackPersonDataStore` includes the exact reviewed-work locator when the provider has
not returned a comment handle and freezes a SHA-256 fingerprint of operational delivery facts in
`PersonDataSelection.externalDeliveryRevision`. A changed write on the same selected row invalidates
the preview. No feedback body, profile, credential or subject is placed in this fingerprint.

An unconfirmed write with no exact inspection locator fails closed. Erasure cannot silently discard
its delivery record. The JSON export includes `feedback_copy.external_url`.

`PersonProcessingSuppression.isReviewJobSuppressed` reads the exact frozen job keys in a fresh
transaction while an erasure is active or resumable. A leased-session check of the job capability
hash rejects stale queued callers after completion. Erasure revokes that hash without retaining
a second subject identity. This applies to automatic feedback, approved feedback and recovery.

`AgentPersonDataCatalog` also includes legacy provider comment references retained on jobs,
even when no feedback copy was recorded. It lists the exact reviewed-work
locator and freezes those inspection facts with the same fingerprint rule. A changed legacy
comment reference requires a new preview. A missing exact locator fails closed before deletion.

## Contributor change contract

Keep each store in its owning module's `PersonDataCatalog`. The core privacy service coordinates
frozen selections and receipts, not provider-specific matching. Use the existing contributor SPI
instead of a second deletion service or an operator-SQL fallback. A new store cannot ship until:

1. Its physical table or derived-copy kind is classified here, and exactly one catalog declares it.
2. The catalog implements exact identity selection, an explicit export projection and idempotent
   erasure. Credentials and other people's profiles are excluded. A shared operational row needs
   an explicit anonymization or empty-selection policy, not a name/email match.
3. Both implementation citations are in the inventory table above. Runtime registration verifies
   that declared stores equal implemented contributors. Architecture checks verify coverage.
4. Focused tests cover a source-only identity, two workspaces and another person left intact.
   They also cover changed preview, failure/resume and any writer or derived-copy boundary relevant to the new store.
5. The controller-facing retention and source-governance records and data-subject disclosure match
   the implementation. Declare broker, backup and provider limits rather than promising their erasure.

The existing executable proof includes
`server/application/src/test/java/de/tum/cit/aet/hephaestus/core/privacy/AccountErasureIntegrationTest.java`,
`server/application/src/test/java/de/tum/cit/aet/hephaestus/core/privacy/PersonDataJobResumptionIntegrationTest.java`,
`server/application/src/test/java/de/tum/cit/aet/hephaestus/core/privacy/EvidenceFolderAccountErasureIntegrationTest.java`
and `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/privacy/PersonDataRegistryTest.java`.
The architecture test proves ownership and documentation coverage. It does not prove arbitrary
contributor behavior. Those behavior tests remain required.

### Upgrade attribution clearing

The person-data migration clears these old references:

- Administrator attribution on instance/silent-mode and model settings.
- Legacy ADMIN/USER connection-audit actor references and details.
- Membership-history subject references without an exact contributor ID.

It also invalidates pending integration authorizations.
No identity is inferred from a display name, login or email. A legacy Outline mirror without an exact
provider instance stops the upgrade. Before applying the upgrade, complete these steps:

1. Back up.
2. Verify recovery.

The shipped migration fragment owns the exact operator procedure.

The conversation store holds no data from releases before it. The upgrade that added it deleted
every earlier conversation, including the Hephaestus copies of Slack DMs. Slack keeps its own
copy of each DM. Feedback shown in an earlier conversation keeps its state, but its copy no longer
names the message that showed it.
