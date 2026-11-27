# ADR 0161: A verified Slack DM can use personal context with a grant

**Status:** Proposed
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decisions 4–5 and the DM fallback.

**Builds on:** [ADR 0158](0158-the-mentor-orders-commitments-per-person-and-answers-per-request.md), [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md).

## Context

People ask the same mentor for help in the app and Slack. The channels keep separate histories. Slack controls retention, and workspace or organization owners can obtain export access to direct messages on eligible plans. The person needs to know when personal context can shape a Slack reply. [Slack export options](https://slack.com/help/articles/201658943-Export-your-workspace-data).

## Considered options

- **One detail level for every reply.** The former Notice and Full levels couple model access to the amount of text sent. A general level cannot distinguish a work answer, a private read, and a private disclosure.
- **Keep Slack on work context only.** The default route stays useful, but a person outside the app cannot opt in to continuity on their personal goals.
- **Use the default work context and offer an explicit grant.** Keep permission to read an item separate from permission to publish. Verify both during delivery.

Already priced and declined are mirrored conversations and bidirectional sync. [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md#considered-options).

## Decision

1. Keep one mentor relationship and separate places. A Slack ask belongs to its verified team, channel and optional native thread root. Fix that route at intake. Verify the reader set, including applicable export readers. A requested answer stays in the original place unless the person explicitly chooses a permitted handoff. App entries and legacy mixed transcripts stay in the app. Edits and deletes update the exact source message and its basis.
2. Reply to an explicit question with the default closed list. The provider can use the ask, eligible history from the exact place, the person's language and answer length, and feedback already posted on work that all the destination's readers may read. A fixed **Open in app** pointer can accompany a useful answer or help when the allowed sources are insufficient. Its text, presence and timing depend on the ask and allowed state. Opening it authenticates the person and checks access again.
3. Confirm delivery of the fixed retention and export notice before the first substantive reply for a person and binding. The notice links to that binding's app settings. Ordinary replies require no grant or acknowledgement action. Retire Notice and Full. A past Full setting, a Slack message, a link and a reaction grant nothing. Keep proactive contact under its own policy.
4. Only the account owner can opt in. Record the grant and acknowledgement of the retention and export terms in the app, for that account and binding. It can cover the person's confirmed goals and agreements, typed prior decisions, eligible recent request status and results, and answer preferences. Each item keeps its original custody. App transcripts and summaries, raw private work text, unposted or withheld assessments, another owner's items and another workspace's records stay outside the grant.
5. Check every additional item before model use. Require the active owner grant at its current generation, custody permission for this binding, an assignment and payer permitted for personal-purpose inputs, and a verified one-to-one DM with acknowledged terms. Exclude a failing item before the model call. Store each admitted source revision and grant generation in the basis. Repeat the checks at result commit, render and send. If an item used by the model fails any check, discard the entire grant-dependent draft. Revocation advances the held grant generation in the same transaction. Derived replay and summaries lose that authority as well. On recovery, invalidate restored grants before the server opens access. The person can opt in again in the app.
6. Publish only allowed conclusions and next steps. Check the full proposed reply against the private admitted refs before render and send. Refuse raw private spans and withheld content. Fall back to an independently built default answer or a fixed app pointer. Count a possible earlier write as unconfirmed until readback resolves it. Use one terminal result and one stable delivery key. Revocation stops future use and writes. Copies already held by Slack follow its retention and export rules.
7. A shared channel never receives a personal grant. A shared answer needs its own capability and whole-audience proof. Keep shared channels disabled until the configured audience passes the release proof. Keep app cards and approval actions in the app.

## Consequences

The mentor can answer a goal-related question in a DM the person chose. Export readers may see the authorized conclusion in a posted answer. A person can revoke future use from the app. The app has to show a clear reason when more context is unavailable. Both default and granted answers need a delivery receipt or an explicit unconfirmed state.

## Confirmation

The 1.0 release walk-through checks these cases before the grant is enabled.

- A granted DM uses the approved goal and a prior decision. The answer gives a useful conclusion without copying raw text. A candidate that copies a private span uses the default answer.
- The same ask without a grant gives a useful answer and the fixed app pointer. The personal canary is absent from all DM surfaces, including the provider request and replay.
- Revocation during the attempt fences the old generation at result commit, render and write. An assignment that loses personal-purpose eligibility invalidates the entire draft that used it. A retry uses the same route and retains any earlier ambiguous delivery. A restored grant cannot revive the old draft.
- A second binding, another workspace, an unacknowledged notice or an ineligible assignment cannot admit the private item.
- A channel reply uses project inputs only. A guest or stale audience gets a hold.
- A slow task runs with a scoped read-only token and no delivery credential. The server checks its result again before commit and delivery.

## Revisit trigger

Review the permitted item list when people repeatedly use the app pointer for the same kind of private record. Review the publication check if it rejects an approved next step or permits a raw span.

## Sources

- [Slack export options](https://slack.com/help/articles/201658943-Export-your-workspace-data) identifies when a Slack DM can enter an organization export.
- [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md) defines one delivery key and readback for unknown outcomes.
