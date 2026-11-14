---
title: GitHub App permissions
description: The permissions, events and manifest of the GitHub App that Connect GitHub registers, and why Hephaestus needs each one.
page_type: reference
---

This page lists the permissions and webhook events of the GitHub App of an instance, and the manifest that holds them.
[Connect GitHub](./github-integration) registers the App with them.
The events are the events that the server handles.

In a GitHub manifest, `write` includes read access.
Each permission that this page does not list stays at **No access**.

## Required permissions

Synchronization and feedback delivery need these permissions.

| Permission (manifest key) | Scope | Level | Why |
| --- | --- | --- | --- |
| Metadata (`metadata`) | Repository | Read | Repository identity and metadata. GitHub always grants it. |
| Contents (`contents`) | Repository | Read | Push and commit sync. Repository checkout for practice reviews. No code writes. |
| Pull requests (`pull_requests`) | Repository | Read & write | Pull request and review sync. Delivery of review feedback, inline notes, and approvals. |
| Issues (`issues`) | Repository | Read & write | Issue sync. Delivery of feedback as issue or pull request comments. |
| Members (`members`) | Organization | Read | Organization membership, teams, and member events |
| Issue types (`issue_types`) | Organization | Read | Issue-type sync, and the issue-type change that starts a practice review |
| Checks (`checks`) | Repository | Read | Check state and check runs of the head of a pull request |
| Commit statuses (`statuses`) | Repository | Read | The same state and runs from commit statuses, also from CI outside GitHub Actions |

[Product feedback](./product-feedback.mdx) also uses the App, with **Issues: read and write**, on the repositories that it names.

## Provisioned permissions

These read permissions enable no feature.
The App asks for them at registration, because a permission increase needs the approval of each installation's account owner.
Until the owner approves an increase, the installation keeps its earlier grants.
Leave out a provisioned read that you prefer to approve later.

| Permission (manifest key) | Scope | Level | Why |
| --- | --- | --- | --- |
| Actions (`actions`) | Repository | Read | Workflow, run, and job signals. No workflow dispatch or cancellation. |
| Deployments (`deployments`) | Repository | Read | Delivery cadence, outcomes, and approval history |
| Merge queues (`merge_queues`) | Repository | Read | Queue use and ejection signals. No queue control. |
| Dependabot alerts (`vulnerability_alerts`) | Repository | Read | Response times to vulnerabilities, and dismissal practices |
| Secret scanning alerts (`secret_scanning_alerts`) | Repository | Read | Response times to leaked credentials |
| Code scanning alerts (`security_events`) | Repository | Read | Triage practices for static analysis |

A provisioned permission gives access, not a subscription.
Their events stay [unsubscribed](#events-not-subscribed).

## Held permissions

The manifest grants none of these permissions at the level shown or above.

| Permission (manifest key) | Scope | Level | Why it is held |
| --- | --- | --- | --- |
| Checks (`checks`) | Repository | Read & write | No feature writes checks. Read is required. |
| Administration (`administration`) | Repository | Read | GitHub has no narrower grant for branch protection. |
| Issue fields (`issue_fields`) | Organization | Read | Custom issue fields have no reader. Issue-type sync uses Issue types. |
| Copilot metrics (`organization_copilot_metrics`) | Organization | Read | No privacy assessment approves it. |

## Fine-grained token permissions

A workspace that syncs with a fine-grained personal access token needs the organization as the resource owner of the token, and these permissions.
They are the required permissions without Checks, which GitHub does not offer to fine-grained tokens.

| Permission (manifest key) | Scope | Level | Without it |
| --- | --- | --- | --- |
| Contents (`contents`) | Repository | Read | Commit sync stays empty. Practice reviews cannot check out the repository. |
| Pull requests (`pull_requests`) | Repository | Read & write | Pull request and review sync stays empty. Feedback delivery on pull requests fails. |
| Issues (`issues`) | Repository | Read & write | Issue sync stays empty. Feedback delivery as comments fails. |
| Commit statuses (`statuses`) | Repository | Read | Commit statuses do not count toward the check state of a pull request. |
| Members (`members`) | Organization | Read | Organization membership and team sync stay empty. |
| Issue types (`issue_types`) | Organization | Read | Issue-type sync stays empty. |
| Metadata (`metadata`) | Repository | Read | GitHub always grants it. |

GitHub accepts a token without these permissions, and the sync that needs a missing permission stays empty.
Without Checks, GitHub can refuse the check state of a private repository.
Then only `check_suite` and `status` deliveries record it.

## Event subscriptions

The receiver stores each delivery in the provider event inbox before it checks for a handler.
Thus, an event that no handler reads costs storage until [the inbox deletes it](./webhook-ingestion-operations#how-long-the-inbox-keeps-an-event).
The App subscribes to these events only.
GitHub's [event reference](https://docs.github.com/en/webhooks/webhook-events-and-payloads) gives the permission that each event needs.

| Event | What Hephaestus does with it |
| --- | --- |
| `push` | Refreshes commits and repository activity. |
| `pull_request` | Syncs the lifecycle and changes of pull requests. |
| `pull_request_review` | Syncs submitted and changed reviews. |
| `pull_request_review_comment` | Syncs inline review comments. |
| `pull_request_review_thread` | Records when a review thread is resolved. |
| `check_suite` | Keeps the check state and runs of the head current. Starts reviews when checks finish. |
| `status` | The same for commit statuses. |
| `issues` | Syncs the lifecycle and content of issues. |
| `issue_comment` | Syncs issue and pull request comments. |
| `label` | Keeps label metadata current. |
| `milestone` | Keeps milestone metadata current. |
| `member` | Records changes of repository collaborators. |
| `repository` | Records repository metadata and lifecycle. |
| `sub_issues` | Updates parent and child issues. |
| `issue_dependencies` | Updates blocking and blocked-by relations. |
| `organization` | Records organization changes. |
| `team` | Syncs teams. |
| `membership` | Syncs team membership. |

GitHub sends `installation`, `installation_repositories` and `installation_target` to every App.
They are not in the manifest's `default_events`.

### Events not subscribed

| Not subscribed | Why |
| --- | --- |
| `check_run`, `workflow_run`, `workflow_job`, `deployment`, `deployment_status`, `deployment_review`, `merge_group`, `merge_queue_entry`, `release`, `commit_comment`, `create`, `delete`, `dependabot_alert`, `secret_scanning_alert`, `code_scanning_alert` | No handler reads them. The check suite event gives the conclusion of its runs. |
| `deployment_protection_rule`, `exemption_request_push_ruleset` | Each asks Hephaestus for an approval decision. Hephaestus observes work and never gates it. |
| `deploy_key`, `team_add` | Access management. Team sync uses the team and membership events. |

## Manifest

The manifest uses GitHub's [manifest format](https://docs.github.com/en/apps/sharing-github-apps/registering-a-github-app-from-a-manifest).
Hephaestus does not implement the manifest registration flow, so [Connect GitHub](./github-integration#github-app-mode) registers the App by hand.
The manifest's `description` is the consent text that an installer reads.
"Never modifies your code" refers to Contents read, not to review and comment writes.

```json
{
  "name": "your-hephaestus",
  "url": "https://<host>",
  "description": "Reads repository and organization data, including CI, deployment, merge-queue and security-alert signals. Can write pull-request reviews and issue/PR comments for practice feedback. Never modifies your code.",
  "public": true,
  "hook_attributes": {
    "url": "https://<host>/webhooks/github",
    "active": true
  },
  "callback_urls": ["https://<host>/api/oauth/callback/github"],
  "request_oauth_on_install": true,
  "default_permissions": {
    "metadata": "read",
    "contents": "read",
    "pull_requests": "write",
    "issues": "write",
    "members": "read",
    "issue_types": "read",
    "checks": "read",
    "statuses": "read",
    "actions": "read",
    "deployments": "read",
    "merge_queues": "read",
    "vulnerability_alerts": "read",
    "secret_scanning_alerts": "read",
    "security_events": "read"
  },
  "default_events": [
    "push",
    "pull_request",
    "pull_request_review",
    "pull_request_review_comment",
    "pull_request_review_thread",
    "check_suite",
    "status",
    "issues",
    "issue_comment",
    "label",
    "milestone",
    "member",
    "repository",
    "sub_issues",
    "issue_dependencies",
    "organization",
    "team",
    "membership"
  ]
}
```
