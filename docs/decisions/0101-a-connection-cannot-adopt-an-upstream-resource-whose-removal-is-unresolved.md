# ADR 0101: A connection cannot adopt an upstream resource whose removal is unresolved

**Status:** Accepted
**Date:** 2026-11-13
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0075](0075-a-transaction-is-one-use-case-and-talks-to-nothing.md) § Bounded lifecycle calls

**Builds on:** [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md), [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md), [ADR 0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md)

**Relates to:** [ADR 0078](0078-lifecycles-are-explicit-and-rigor-follows-the-damage.md), [ADR 0095](0095-one-adapter-for-each-provider-behind-a-vendor-neutral-spi.md)

## Context

After a local timeout releases the lifecycle lock, a provider can still remove a resource that a reconnect adopted.
A local version cannot cancel accepted remote work.

Outline also loses SUSPENDED cleanup after a failed listener. ADR 0080 protects admission and erasure; upstream cleanup needs a separate rule.

## Considered options

1. **Durable identity fence, chosen.** One adoption/removal boundary across providers. Unproved removal can block reuse indefinitely.
2. **Immutable route generations.** Each hook generation needs ownership and retirement. A new URL cannot protect a reused hook ID, installation or token. Those still need the identity fence.
3. **Lifecycle lock through the provider call.** Orders confirmed calls simply. Neither a longer timeout nor a grace period excludes late remote completion.

## Decision

1. **Fence identity before removal.** Commit immutable intent before dispatch. Adoption, activation, credential replacement and last-owner checks share coordination, including the no-row-yet case. Lock identities before connections in stable order. Another workspace's ownership prevents removal; responses never reveal it.
2. **Dispatch after commit.** Reuse publication-registry wake/retry and reconciliation. Before every removal retry, read the captured target and confirm ownership positively: its hook URL/route, subscription URL or installation App ID must match. GitHub's App-scoped installation API needs no separate ownership marker. Delete only on a match. Verified absence or a different owner resolves our removal without DELETE; an unreadable target, including a permission-hidden `404`, stays unresolved for rule 9. Never resolve a retry target from resumed configuration. Retire ADR 0075's network-call lock.
3. **Uncertainty keeps the fence.** Timeout, `202`, hidden `404`, credential expiry and retry deadlines prove no completion. The verified ownership results in rule 2 resolve our removal without DELETE. Other automatic release requires proof that removal is terminal and the identity cannot be reused unsafely; rule 9 supplies the manual reverse state. Otherwise require a distinct identity. No exactly-once provider-execution claim.
4. **Record ownership before external creation.** Owned hooks need a non-secret, listable ownership marker. OAuth completion checks its durable attempt. Empty listings cannot exclude late creation; unknown outcomes need admin repair. Never delete unowned resources or retain signed callback URLs.
5. **Apply the identity rule to every adapter.**

   | Adapter | Exact removal identity | Reconnect or resume while unresolved |
   | --- | --- | --- |
   | GitHub App | Provider origin, App ID and installation ID | Require a distinct installation. PAT connections own no installation to uninstall. |
   | GitLab | Provider origin, group ID and hook ID | Register a distinct hook. Discovery excludes the replacement's ownership marker. |
   | Slack | Provider origin, team ID, bot user ID and unkeyed SHA-256 fingerprint of the exact bot token | Fence the token and bot user until a contract test proves revocation cannot affect a replacement token for that bot user. Token revocation is not app uninstall. |
   | Outline | Provider origin and subscription ID | Register a distinct subscription. Manual PAT ownership stays separate. |

6. **Keep erasure independent.** Intents and publications hold no credentials, payloads or signed routes. Credential deadlines stay fixed. After owner deletion, retain only pseudonymous provider identifiers and minimal repair state without workspace/person pointers. A GitHub user-account installation can identify a person. Retention serves integrity and security, not anonymous-data processing; the personal-data map records its legal basis. Delete the identity when the fence resolves or an admin releases it; unresolved retention can be indefinite and must remain visible for release. Scoped references cascade with workspaces. Slack fingerprints its high-entropy bot token with unkeyed, domain-separated SHA-256: `SHA-256("hephaestus/slack-bot-token/v1" || token)`. No fingerprint key needs backup or rotation.
7. **Repair Outline SUSPENDED deletion.** Commit cleanup for SUSPENDED and UNINSTALLED. Durable retry and reconciliation target the old subscription; the fresh one survives. Close `INBOX-LR-OUTLINE-SUSPENDED-DELETE` and remove its `AfterCommitRepairArchTest` exception only with implementation proof.
8. **Preserve event boundaries.** ADR 0080 owns admission, deduplication and erasure. Cleanup cannot retag deliveries or restore payloads. Both admin consoles show waiting, authorization needs or fresh-resource use. Automatic continuation requires durable work.
9. **Release by explicit admin confirmation.** When automatic proof is unavailable, an instance admin confirms out of band that the old resource is gone, then explicitly releases its fence. Audit the actor, connection, confirmation and release; show the result in the administration console that owns the connection. Workspace-only roles cannot release a fence. The action binds the exact connection/workspace and fence, and cannot release another tenant's fence by substituting an ID. After owner deletion, the fence stays visible to instance admins for release by its exact provider identity, without restoring workspace/person pointers.

## Provider assumptions and proof

- **GitLab:** integration disable resets settings; hook deletion and PAT revocation are separate APIs. **Assumption:** no cascade deletes arbitrary hooks or revokes PATs. A supported-version contract test must disable an integration, inspect hook/token state, then delete only the owned hook. Hephaestus disconnect is local and preserves user-supplied PATs.
- **Slack:** `auth.revoke` documents bot-user deactivation and channel-membership removal. Their effect on a replacement token for the same bot user is **unconfirmed**. Until a contract test proves isolation, fence both token identity and `(team ID, bot user ID)`; a different token alone does not permit adoption.
- **Outline:** the API documents subscription deletion and user suspension, not a subscription `SUSPENDED` state. Here SUSPENDED is local. **Assumption:** neither suspension proves subscription deletion. A supported-version contract test must inspect subscriptions using independent admin credentials; lost-wake/retry tests prove old deletion and fresh-subscription survival.
- **All adapters:** **assume** IDs may be reused and timed-out calls may finish. Apart from rule 2's verified ownership results, automatic release requires a provider guarantee; finite tests cannot prove non-reuse. Contract tests must verify ownership-marker round trips and pagination; otherwise repair stays pending. Slack token freshness is unconfirmed: test repeated and distinct values.

## Consequences

- Delayed removal cannot destroy an admitted replacement, including across workspaces.
- Reconnects may need fresh resources. Credential erasure can leave pseudonymous security identifiers indefinitely until resolution or admin release.
- Discovery, scoped admin state and repair signals add operational cost.

## Confirmation

**Not enforced.** The follow-up needs deterministic provider fakes for each adapter:

- Accept removal, time out locally, refuse the same identity, adopt a fresh one, finish old removal: the replacement survives.
- Race ownership/removal across workspaces; crash before/after dispatch; retry the captured target.
- Reuse a hook/subscription ID for a foreign resource: the retry reads the different ownership marker and deletes nothing. Unreadable ownership remains unresolved.
- Revoke an old Slack token after a replacement arrives: assert bot-user state and memberships as well as token validity; block the shared bot user until isolation is proved.
- Keep the fence for `202`, hidden `404` and lost credentials. Test stable SHA-256 fingerprints across restarts and credential-key rotation, without a fingerprint key.
- Complete registration/OAuth after UNINSTALLED; repair proven-owned resources or retain unresolved work.
- Lose Outline's SUSPENDED wake and first DELETE; reconciliation removes the old subscription. Then remove the inventory exception.
- After out-of-band confirmation, an instance admin releases the exact fence and the connection console shows its audit. Reject workspace-only callers and mismatched workspace/connection/fence IDs; neither failure changes the fence.

Preserve `OutlineFrameworkRegistrationIntegrationTest` admission and `ProviderEventErasureIntegrationTest` erasure proofs. Add tenancy and illegal-transition checks.

## Revisit trigger

- A provider supplies conditional deletion or a terminal receipt for safe reuse.
- Fence counts or reconnect waits become an operational constraint.
- A provider removes the ownership field needed to discover late registrations.

## Sources

- [RFC 9110, §§15.3.3 and 15.5.5](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.3.3): `202` does not prove completion; `404` can hide an existing resource. This requires unresolved state instead of timeout-based release.
- [GitHub App installation deletion](https://docs.github.com/en/rest/apps/apps#delete-an-installation-for-the-authenticated-app): deletion targets an installation ID and returns `202`. The fence is our inference, not a provider conditional-delete guarantee.
- [Slack `auth.revoke`](https://docs.slack.dev/reference/methods/auth.revoke/): the request targets a token and reports whether it was revoked. Revocation does not uninstall the app, but deactivates the bot user and removes channel memberships. Replacement-token isolation is not documented; the bot-user fence stays until a contract test proves it.
- [GitLab group hooks](https://docs.gitlab.com/api/group_webhooks/#delete-a-group-hook), [integration disable](https://docs.gitlab.com/api/project_integrations/#disable-github) and [PAT revocation](https://docs.gitlab.com/api/personal_access_tokens/#revoke-a-personal-access-token): explicit hook deletion is idempotent; integration settings reset and token revocation are separate operations. No documented cleanup cascade replaces the fence.
- [Outline API reference](https://www.getoutline.com/developers): `POST /webhookSubscriptions.delete` is separate from `users.suspend`. This supports exact-subscription cleanup, not an assumed provider subscription-suspension lifecycle. The assumptions above define the remaining proof.
