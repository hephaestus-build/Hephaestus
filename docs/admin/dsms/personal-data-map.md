---
title: Personal-data map and erasure verification
description: The authoritative map from personal-data stores to export, retention, and erasure controls.
---

# Personal-data map and erasure verification

This inventory maps each personal-data store to its removal control and executable evidence. Add a
store or derived copy here, with an erasure or retention test, before it receives personal data:
`PersonalDataMapArchTest` fails the build when a database table is named in neither this map nor the
test's own list of tables that hold no personal data, and when a source or test cited below has
moved.
Workspace purge applies storage limitation when a workspace loses its purpose; a verified
person-erasure request follows the [instance-admin procedure](../production-operations-runbook#access-and-erasure-for-one-person).
The implementation inventory below owns each store's exact selection, export and erasure citations.
The frozen scope includes reviewed work on which the person authored, merged, commented, reviewed,
was assigned, was requested to review, or contributed a linked commit. Derived observations, feedback
and evidence copies use that same source scope, including when their primary author is another person.
Provider-owned source attribution is shared by scope selection and processing suppression:
`server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/scm/ScmPersonSourceIdentityContributor.java`,
`server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/slack/SlackPersonSourceIdentityContributor.java`
and `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/outline/OutlinePersonSourceIdentityContributor.java`.
Each guard checks the requesting workspace in SQL; mirror sync cannot bypass a native-identity control.
OAuth state nonces record only a new exact initiating account; exports omit the nonce capability, and erasure deletes its row. Older rows have no inferred account attribution.
The self-service account export and cooldown remain separate, narrower operations.

| Store / copy | Data and access path | Workspace removal | Person erasure | Residual retention and evidence |
|---|---|---|---|---|
| PostgreSQL account, identity and authentication rows — `account`, `account_feature`, `account_export`, `identity_link`, `issued_jwt`, `consent_decision`, `auth_event`, `config_audit_event` | Sign-in identity; self-service export plus instance-admin person export | Account-global rows remain | Self-service: 48-hour cooldown, then account purge. Instance-admin person job: account purger after the other store steps | Authentication history retains references to the account tombstone, with IP address, user agent and details cleared on erasure, and expires in 12-month rolling partitions. Settings-change history detaches actor references and expires after 365 days; exports expire after 48 hours. `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/AccountHardDeleteSweeperIntegrationTest.java` and `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/export/AccountExportRetentionIntegrationTest.java`. |
| PostgreSQL product feedback and surveys — `product_feedback`, `product_survey_participation` | Data supplied by the account holder; instance-admin person export | Purge contributors remove workspace-owned rows: feedback sent from the workspace, surveys targeted at it and participation recorded from it | Account purge removes account-owned rows through `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/FeedbackAccountErasureAdapter.java`, because the account tombstone leaves the `ON DELETE CASCADE` unfired | No independent time-based expiry. Workspace removal: `server/application/src/test/java/de/tum/cit/aet/hephaestus/workspace/WorkspacePurgeIntegrationTest.java` (`purgeRemovesTheProductRowsTheWorkspaceOwnsAndKeepsInstanceWideSurveys`). Person erasure: `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/AccountHardDeleteSweeperIntegrationTest.java` (`shouldEraseProductSubmissionsWhenAccountIsPurged`). |
| PostgreSQL contributor profiles and preferences — `user`, `user_preferences` | Mirrored contributor identity and personal settings; self-service export plus instance-admin person export | Instance-global rows remain | Instance-admin person contributor removes preferences and anonymises the exact provider profile; self-service account deletion does not remove these rows | No independent time-based expiry. The [record of processing](./record-of-processing.md) defines the operator responsibility. `server/application/src/main/java/de/tum/cit/aet/hephaestus/account/UserPreferences.java` links preferences to the contributor profile, not the sign-in account. |
| PostgreSQL SCM mirror — `organization`, `organization_membership`, `team`, `team_membership`, `team_repository_permission`, `repository`, `repository_collaborator`, `pull_request`, `pull_request_requested_reviewers`, `pull_request_review`, `pull_request_review_comment`, `pull_request_review_thread`, `issue`, `issue_assignee`, `issue_comment`, `milestone`, `discussion`, `discussion_comment`, `git_commit`, `commit_contributor`, `commit_file_change`, `project`, `project_item`, `project_field_value`, `project_status_update` | GitHub/GitLab profiles and authored work; source-provider export plus instance-admin person export | Last repository/connection removal and workspace purge erase the active mirror | Exact provider instance and native user matching; authored content is cleared and shared actor roles are detached separately | `server/application/src/test/java/de/tum/cit/aet/hephaestus/workspace/ScmWorkspaceErasureIntegrationTest.java`. |
| PostgreSQL Slack mirror — `slack_message`, `slack_thread`, `slack_monitored_channel`, `slack_participant_consent`, `slack_channel_consent_event`, `mentor_slack_thread` | Messages, identities and attribution; instance-admin person export | Disconnect and workspace purge | Slack opt-out and instance-admin person contributors | `server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/slack/retention/SlackRetentionErasureIntegrationTest.java` and `server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/slack/interactivity/SlackAppHomeOptOutErasureIntegrationTest.java`. |
| PostgreSQL Outline mirror — `outline_document`, `outline_collection`, `outline_document_event` | Documents and attribution; instance-admin person export | Collection removal, disconnect and workspace purge | Instance-admin person contributors | `server/application/src/test/java/de/tum/cit/aet/hephaestus/integration/outline/lifecycle/OutlineWorkspacePurgeAdapterIntegrationTest.java`. |
| PostgreSQL mentor, observations, feedback and activity read models — `chat_thread`, `chat_message`, `chat_message_vote`, `observation`, `reaction`, `feedback`, `feedback_observation`, `feedback_dispatch`, `feedback_placement`, `feedback_approval`, `feedback_withdrawal`, `observation_invalidation`, `delivery_policy_evaluation`, `activity_event`, `workspace_membership` | Conversations, derived judgements and persisted citation-verification results; instance-admin person export | Purge contributors remove each workspace-owned family | Account purge alone does not remove these rows. Slack opt-out removes conversation-derived feedback for a matched member; wider person erasure uses the instance-admin contributors below. | `server/application/src/test/java/de/tum/cit/aet/hephaestus/workspace/WorkspacePurgeIntegrationTest.java` exercises the assembled purge chain, including delivered and never-delivered conversation feedback, its placement and observation join, and the chat message that delivered it. `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/slack/events/SlackPersonErasureService.java` owns the Slack person path. |
| PostgreSQL remembered activity visibility preference — `workspace_hidden_former_member` | That an administrator had hidden a person from workspace activity before the provider roster stopped granting them access; instance-admin person export | A purge contributor removes the workspace's rows | Removed when the person becomes a member again, or when their SCM user row is deleted (`ON DELETE CASCADE`); account purge alone does not remove it | No independent retention window: the row is kept for as long as the person is not a member. `server/application/src/test/java/de/tum/cit/aet/hephaestus/workspace/WorkspacePurgeIntegrationTest.java` proves the purge removal. |
| PostgreSQL review-scope selection — `practice_review_person_target` | Which people a workspace administrator selected for practice review; instance-admin person export | Removed with the workspace: the row cascades from the `workspace_membership` row the purge deletes (`sfk_practice_review_person_target_membership`, `ON DELETE CASCADE`) | Removed with the membership | No independent retention window. `server/application/src/test/java/de/tum/cit/aet/hephaestus/workspace/WorkspacePurgeIntegrationTest.java` proves the membership deletion the cascade follows. |
| PostgreSQL operator-action and operational-history records — `connection_audit`, `sync_job`, `artifact_signal`, `review_backfill_run`, `review_sweep_schedule` | Account or contributor references for workspace operations | Lifecycle facts remain after workspace purge | Typed account attribution in connection history is detached on account purge; person contributors handle the other operator stores | The upgrade clears untyped `actor_ref` and free-text `detail` on existing `ADMIN` and `USER` connection audit rows without backfill. Event types, state changes and times remain. New writes use a typed account reference. Provider and system references remain. `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/ConnectionAuditAccountErasureAdapter.java` and `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/IntegrationCoreConnectionPersonDataCatalog.java`. |
| PostgreSQL instance-administration records — `instance_settings`, `instance_llm_settings`, `product_survey` | The stable account reference of the administrator who changed silent mode or instance LLM policy, and the account that authored a survey | Instance-scoped; workspace purge does not reach them | Account purge detaches settings actor references. Person erasure also detaches the survey creator. | The upgrade drops old settings display-login attribution without backfill. Settings, survey definitions and change times remain. `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/settings/SettingsAccountErasureAdapter.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/catalog/LlmSettingsAccountErasureAdapter.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/adapter/ProductfeedbackPersonDataCatalog.java`. |
| PostgreSQL `agent_job`, manifests, evidence references and diagnostics | Review inputs, outputs and provenance; instance-admin person export | `AgentWorkspacePurgeAdapter` deletes workspace jobs before workspace configuration | Person selection includes reviewed authors, source contributors, derived observations and the evidence-owner hook; diagnostics are cleared after evidence removal is acknowledged | `server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/AgentWorkspacePurgeIntegrationTest.java`, `server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/job/AgentJobRetentionServiceTest.java`, and `server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/job/AgentJobRetentionObservationIntegrationTest.java` cover purge, 14-day diagnostics and 90-day rows. |
| PostgreSQL `llm_usage_event` | Token/cost accounting with workspace and opaque source identifier; monthly workspace/admin reports | Retained for accounting until its retention sweep | Person contributors detach the selected job/conversation source identifiers while retaining accounting facts | The window and its operator control are in the [record of processing](./record-of-processing.md). `server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/usage/LlmUsageRetentionIntegrationTest.java` proves the cutoff across workspaces; `server/application/src/test/java/de/tum/cit/aet/hephaestus/agent/usage/LlmUsageRetentionSweeperTest.java` verifies job outcomes. |
| PostgreSQL installed-client sessions — `client_session`, `client_sign_in_handoff` | Which installed clients (the browser extension) are signed in, and the hashes of their single-use sign-in codes; refresh-secret hashes live on `issued_jwt` | Account-global rows remain | Account purge removes both through `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/auth/AccountPurger.java` | A sign-in code expires after 60 seconds. A session ends at its absolute deadline, on sign-out or on revocation, and becomes eligible for deletion with its token rows at absolute expiry or one day after revocation, whichever comes first. The hourly cleanup skips locked rows until a later pass; see `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/auth/clientsession/ClientSessionPruner.java`. `server/application/src/test/java/de/tum/cit/aet/hephaestus/core/auth/clientsession/ClientSessionIntegrationTest.java`. |
| PostgreSQL email subscriptions — `notification_subscription` | Native account id, category, opt-in state/time and opaque unsubscribe capability | Account-scoped, not workspace-owned | `NotificationAccountErasureAdapter` removes rows during account purge; export includes choices but no capability | Retained until account erasure; each capability only disables one subscription. Unsubscribe does not erase the opt-out, so old links remain valid. |
| PostgreSQL survey email invitations — `product_survey_email_invitation` | Survey/account/workspace ids, requesting administrator, request/expiry/cancellation and relay-acceptance facts; no address or answers | Workspace purge removes scoped rows; survey deletion removes its invitations | Account erasure removes recipient rows and clears the erased requesting administrator's reference | Retained with the survey. Separate from in-app participation and never a proxy for opened/read email. |
| PostgreSQL notification outbox — `event_publication` | The Spring Modulith event publication registry: one row per pending or failed notification listener, holding the serialized event (source and recipient ids, bounded lifecycle information and timestamps, never an address or report text) and its delivery attempts; instance-admin person export | Instance-scoped: workspace purge does not directly reach it; delivery rechecks source and membership | The row holds ids only; the address is resolved at send time from `account.primary_email`, which the account purge clears, so a resubmitted notification for a purged account has no recipient and completes as withheld | Completed publications are deleted. Account-deletion confirmations expire at the purge deadline; other kinds use their bounded lifecycle window (at most seven days). Source erasure, opt-out or lost eligibility completes queued mail without sending on the next attempt. `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/NotificationRedeliveryJob.java`, `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/AccountDeletionEmailListener.java`. |
| NATS JetStream | Buffered webhook, message and document payloads; not directly included in self-service export | No selective deletion | No selective deletion | Slack/Outline expire after 72 hours; GitHub/GitLab after 180 days; byte ceilings can shorten both. Stream configuration tests enforce these bounds. |
| Worker-local repository mirrors and attempt folders | Person export lists exact-linked repository history and safe mounted-copy facts, not raw Git objects or other profiles | Mirror sweep follows repository monitoring; workspace purge durably queues attempt-folder removal before deleting job rows, and the mounted worker confirms removal after commit | Person erasure cancels affected attempts and waits for mounted-store acknowledgement through `EvidenceFolderPersonDataCatalog`; repository owners erase Git authorship at the source, not by rewriting Hephaestus mirrors | Attempt-input lifetime follows [ADR 0041](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0041-compose-1x-kubernetes-2.md#evidence-admission-and-deletion); [`artifact-source-governance.md`](./artifact-source-governance.md) owns erasure requirements. Offline mounts retain a removal receipt until acknowledgement. |
| Configured LLM provider | Prompts and responses for enabled purposes; provider/operator access process | A completed request cannot be retracted | Provider process | Deployment-specific provider terms must define the bound before processing starts; see the [processor checklist](./processor-checklist.md). |
| GitHub/GitLab/Slack delivery destination | Posted feedback; source-provider export | Not silently crawled or rewritten | Best-effort correction/removal through the operator path | The provider controls its copy and audit history. |
| Application metrics | Aggregate operational counts without account, workspace, export or source labels | Not applicable | No subject-level series exists | Backend retention is deployment-specific; operators must record it. |
| Container logs and support bundles | Operational output; logging policy excludes raw request bodies and secrets, but operators must assess exported bundles | No selective deletion | No selective deletion | Shipped container logs rotate by size; support-bundle retention is deployment-specific. |
| PostgreSQL and filesystem backups | A copy of the backed-up personal-data corpus | No selective deletion inside a backup | Expiry of the backup copy | The optional encrypted off-host PostgreSQL backup overlay retains full backup chains by count, not by a legal time limit. Operators must document and enforce destination retention, including copies of `.env` and broker evidence; see [backup and restore](../backup-restore.mdx). |

## Operator verification

Each erasure, export and retention job publishes a bounded success/failure counter and an
affected-row counter; the names, labels and cardinality guarantee are in the
[observability contract](../observability.mdx). Alert configuration remains deployment-owned: alert
on `outcome="failure"`, on `outcome="incomplete"` — a retention pass that ran out of its time budget
with expired rows still in place — and on a missing successful daily `llm_usage_retention` run for
more than 48 hours.

Review this map with the [record of processing](./record-of-processing.md),
[artifact-source governance](./artifact-source-governance.md), and the public privacy statement.

## Member onboarding and AI choices

`account_ai_choice` stores an account's AI choice (`ai_choice`: `NO_AI`, `IN_HOUSE_ONLY` or
`CLOUD`) and when it was last changed. It is answered once and holds in
every workspace the account is a member of; a row exists only once the person has answered. The
account export includes it with the choice as that literal; account erasure deletes it. Workspace
purge never touches it. `workspace_member_onboarding` stores which settings revision an account
finished or skipped a workspace's setup page at (`seen_revision`); it carries no choice and no free
text, and is removed with account erasure and with workspace purge. `workspace_onboarding_settings`
stores whether the setup page is on, whether a choice is required, and required integration
identifiers; it holds no free text and is removed with workspace purge. `llm_model` and
`workspace_llm_model` carry the declared operator (`operated_by`) and a data-handling note
(`data_handling_note`), an admin-only field for region, agreement, renewal date and retention
details. It is shown to admins only, never to developers, and must not name individuals. None of these tables has an independent expiry. Configuration changes also follow the
existing configuration-audit retention policy.

## Person request implementation inventory

Exact identity resolution also includes registered provider rows whose type and normalized network
origin are identical. Host case and a default port do not create a different provider instance. The
native subject and Slack workspace key must still match exactly. A conflicting account link on any
such row stops the request; display attribution is never used to resolve it. Permanent suppression
and native write locks use that same type, origin and native subject, including provider rows
registered after erasure. Adding an equivalent provider row cannot reset a processing control.
Slack workspace keys still distinguish suppression decisions; sharing a lock does not merge teams.

Source connections register their provider instance even when nobody signs in through that provider.
`server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/SourceProviderNamespaces.java`
runs before activation and before an exact source URL spelling changes. Registration and native-control
inheritance commit together, under capture admission. The synchronous listener in
`server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/PersonSuppressionService.java`
copies only exact type/origin/subject/Slack-workspace controls; it does not infer account ownership.
Existing sources are registered by
`server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/PersonSourceProviderBackfillChange.java`.
A bound connection cannot be changed to a different network origin. A legacy Outline mirror without
an exact provider instance stops the upgrade instead of guessing a binding or deleting visible work.

Each active database store has an export and erasure contributor in its owning module. The shared
mechanics in `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/spi/JdbcPersonDataStore.java`
apply only the frozen row keys. The catalog owns the exact identity predicate, export field allowlist
and deletion or anonymisation policy. A new store must have both operations; classification alone is
not enough. Shared provider metadata has an explicit empty person selection, not a name match.

| Store | Person export implementation | Person erasure implementation |
|---|---|---|
| `user_preferences` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/account/adapter/AccountPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/account/adapter/AccountPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `activity_event` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/activity/adapter/ActivityPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/activity/adapter/ActivityPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `person_evidence_copy` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/EvidenceFolderPersonDataCatalog.java` (`selectCopies`, safe copy facts; raw Git objects excluded) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/EvidenceFolderPersonDataCatalog.java` (`prepareErasure`, mounted-store acknowledgement; `erase`, idempotent receipt removal) |
| `person_data_store_administration` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java`; `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/PersonDataStoreAdministrationContributor.java` (`select`, exact acting account; `export`, operational step facts) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java`; `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/PersonDataStoreAdministrationContributor.java` (`erase`, clear the exact account reference and retain counts and times) |
| `agent_job`, `llm_usage_event`, `instance_llm_settings`, `review_backfill_run`, `review_sweep_schedule` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/AgentPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/agent/adapter/AgentPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `account` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CoreAccountPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CoreAccountPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `account_feature`, `identity_link`, `issued_jwt`, `client_session`, `client_sign_in_handoff`, `account_export`, `consent_decision`, `auth_event`, `config_audit_event`, `config_audit_event_acting_account`, `config_audit_event_membership_subject`, `instance_settings`, `event_publication`, `person_data_request`, `person_data_request_administration`, `person_suppression` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/core/privacy/CorePrivacyPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `git_repository_history` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/IntegrationCoreConnectionPersonDataCatalog.java` (`GitRepositoryHistoryPersonDataStore.select`, repository references only) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/IntegrationCoreConnectionPersonDataCatalog.java` (`GitRepositoryHistoryPersonDataStore.erase`, preserves upstream caches; affected attempt folders use the evidence-erasure hook) |
| `oauth_state_nonce`, `user`, `organization_membership`, `team_membership`, `repository_collaborator`, `issue_assignee`, `pull_request_requested_reviewers`, `organization`, `team`, `team_repository_permission`, `repository`, `milestone`, `issue_comment`, `pull_request_review`, `pull_request_review_comment`, `discussion_comment`, `issue`, `pull_request_review_thread`, `discussion`, `git_commit`, `commit_contributor`, `commit_file_change`, `project`, `project_item`, `project_status_update`, `project_field_value`, `connection_audit`, `sync_job`, `artifact_signal`, `git_commit_committer`, `git_commit_authored_content`, `issue_merger`, `discussion_answer_actor`, `artifact_signal_requester` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/IntegrationCoreConnectionPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/core/connection/IntegrationCoreConnectionPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `outline_document`, `outline_document_event`, `outline_collection`, `outline_document_editor`, `outline_document_content`, `outline_document_collaborator` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/outline/adapter/IntegrationOutlinePersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/outline/adapter/IntegrationOutlinePersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `slack_thread`, `slack_message`, `mentor_slack_thread`, `slack_participant_consent`, `slack_channel_consent_event`, `slack_monitored_channel` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/slack/adapter/IntegrationSlackPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/integration/slack/adapter/IntegrationSlackPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `chat_message_vote`, `chat_message`, `chat_thread`, `chat_message_feedback_copy`, `chat_thread_feedback_runtime_copy` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/mentor/adapter/MentorPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/mentor/adapter/MentorPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `notification_subscription` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/preferences/adapter/NotificationPreferencesPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/notification/preferences/adapter/NotificationPreferencesPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `delivery_policy_evaluation`, `feedback_dispatch`, `reaction`, `feedback_placement`, `feedback_observation`, `feedback_approval`, `feedback_withdrawal`, `observation_invalidation`, `feedback`, `observation`, `feedback_approval_actor`, `feedback_withdrawal_actor`, `feedback_restoration_actor`, `observation_invalidation_actor`, `observation_restoration_actor` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/adapter/PracticesPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/practices/adapter/PracticesPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `product_feedback`, `product_survey_participation`, `product_survey_email_invitation`, `product_survey`, `product_feedback_resolver`, `product_survey_invitation_requester` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/adapter/ProductfeedbackPersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/productfeedback/adapter/ProductfeedbackPersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |
| `workspace_membership`, `workspace_hidden_former_member`, `practice_review_person_target`, `account_ai_choice`, `workspace_member_onboarding`, `workspace_onboarding_settings` | `server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/adapter/WorkspacePersonDataCatalog.java` (`select`, explicit export fields; `JdbcPersonDataStore.export`) | `server/application/src/main/java/de/tum/cit/aet/hephaestus/workspace/adapter/WorkspacePersonDataCatalog.java` (`erase` policy; `JdbcPersonDataStore.erase`) |

### Shared source content and runtime journals

Milestones are selected by their exact creator reference. Commit file-change copies are selected by
the exact author or co-author reference, never by a path, name or email. Erasure clears milestone
content and attribution while retaining its operational row, and deletes selected file-change copies
before contributor attribution is detached. A person who only applied another developer's commit
loses their committer attribution; the other developer's file-change rows remain.

Heph exports the structured conversation messages and their parts. It does not export the internal
runtime session journal, which can contain tool context rather than only the person's conversation.
The journal is removed with an erased person's thread.

The upgrade clears legacy Heph runtime journals once because they have no complete exact-person
provenance. Visible conversation messages, titles and times stay. This is a reset of hidden
conversation memory, not deletion of the conversation a person can read.

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
arbitrary error metadata and hidden reasoning are excluded: they can contain credentials or copied
profiles from another person. The projection is implemented in
`server/application/src/main/java/de/tum/cit/aet/hephaestus/mentor/adapter/MentorMessagePersonDataStore.java`;
canonical stored messages are not changed by export.

Slack AI projections check the native author and Slack team in SQL. Outline AI projections check
native creator, editor and collaborator subjects in the document's provider instance. These checks
apply to direct reads, workspace inputs and ranked search before its result limit. Provider sync may
still mirror upstream records; the controlled rows do not return to these AI input queries.

Feedback copied into another person's Heph conversation is selected from its exact delivery message
ID and workspace. The export includes the copied message, not the other person's profile or replies.
Erasure replaces the linked copy with a neutral notice and clears that thread's hidden runtime journal.
It preserves replies, parent links and conversation times. A delivery workspace mismatch stops preview.

Activity reconciliation acquires one ordered set of native-identity transaction locks before filling
unresolved commit actors. It updates only admitted authors, so an erased author cannot reappear in
the activity ledger and does not prevent other authors in the same batch from being reconciled.

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
a one-hour cleanup grace, and restart cleanup removes abandoned folders. Repository history is not
exported as an unfiltered Git object archive.

Workspace purge queues removal of all mounted attempt copies before deleting job rows. The request
commits with the workspace purge; it does not wait for a worker inside that database transaction.
An offline mounted store keeps its receipt and exact identity keys until it returns and confirms
folder deletion. After that confirmation, purged-workspace receipts keep only technical coordinates and
the deletion state. Person erasure still waits for every selected store to acknowledge deletion.

### Copied evidence and secondary subjects

A person can appear in a review's captured evidence without being its primary developer. The preview
freezes the exact job IDs from native-identity capture receipts. Observation, feedback and delivery
contributors use those same IDs for export and erasure. A receipt's native provenance stays after
folder deletion is acknowledged until the person-erasure step clears it. Unrelated review jobs and
other people's profiles and replies are not selected through repository history alone.

Provider-feedback inspection also covers unconfirmed summary and inline writes.
`ExternalFeedbackPersonDataStore` includes the exact reviewed-work locator when the provider has
not returned a comment handle and freezes a SHA-256 fingerprint of operational delivery facts in
`PersonDataSelection.externalDeliveryRevision`. A changed write on the same selected row invalidates
the preview. No feedback body, profile, credential or subject is placed in this fingerprint. An
unconfirmed write with no exact inspection locator fails closed; erasure cannot silently discard
its delivery record. The JSON export includes `feedback_dispatch.delivered_external_url`.

`PracticeFeedbackPersonDataAdmission.deliver` retains the shared copy-admission lease
from dispatch intent creation through provider requests and their persisted outcomes.
`PersonSuppressionService.isReviewJobSuppressed` reads the exact frozen job keys in a fresh
transaction while an erasure is active or resumable. A leased-session check of the job capability
hash rejects stale queued callers after completion; erasure revokes that hash without retaining
a second subject identity. This applies to automatic packages, approved packages and recovery.

`AgentPersonDataCatalog` also includes legacy provider comment references retained on jobs,
even when no dispatch or feedback placement was projected. It lists the exact reviewed-work
locator and freezes those inspection facts with the same fingerprint rule. A changed legacy
comment reference requires a new preview; a missing exact locator fails closed before deletion.
