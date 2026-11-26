# ADR 0046: Hephaestus follows its model, not a workspace switch

<a id="adr-0046-heph-follows-its-model-not-a-workspace-switch"></a>

**Status:** Accepted
**Date:** 2026-09-27
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md) (per-purpose agent bindings)

## Context

Whether a workspace offered Hephaestus was decided twice: by the workspace's conversation switch and by
whether a Hephaestus model binding under **AI models** was ready. The two could disagree. With the switch on
and no ready binding, Hephaestus was offered and could not answer; with a ready binding and the switch off,
the model sat configured and unused.

## Decision drivers

- One control per question: an admin who sets up a model for Hephaestus means Hephaestus to answer.
- No workspace gains Hephaestus by upgrading.
- Each member's AI choice keeps deciding whether Hephaestus answers them.

## Considered options

1. **Keep both controls.** Rejected: two places to turn Hephaestus off, and a state where Hephaestus is offered
   but cannot answer.
2. **Keep the switch and ignore binding readiness.** Rejected: the switch cannot make an unready model
   answer.
3. **Offer Hephaestus wherever a Hephaestus model binding is ready, and remove the switch.** Chosen.

## Decision

Hephaestus is offered to every member of a workspace where a Hephaestus model binding is ready, on the web and in
Slack direct messages, within each member's AI choice. Workspace admins manage the bindings under
**AI models**; there is no separate switch.

- **Turning Hephaestus off** is turning off the workspace's Hephaestus model rows; turning them back on restores it.
- **The upgrade carries the switch over:** in workspaces that had the conversation switch off, it turns the
  Hephaestus model rows off.

## Consequences

- The floating Hephaestus panel appears only where a Hephaestus model is ready.
- The restore-clone lockdown turns off every Hephaestus model row, and lifting it means turning them back on
  under **AI models**.
- The switch's column is dropped in the same release, after the upgrade has carried its value over to the
  Hephaestus model rows.
- Which Hephaestus rows were on before the upgrade is not recorded; undoing it needs a pre-upgrade backup.

## Confirmation

`DefaultConversationReadinessQueryTest` fails if Hephaestus counts as ready without an enabled binding that resolves to a model.
`-conversation-panel-availability-route.test.tsx` checks that the floating panel stays away where no Hephaestus model is ready.
`restore-clone-lockdown.sql` turns off every Hephaestus model row. Review catches a new workspace switch for Hephaestus.

## Revisit trigger

A workspace that needs Hephaestus unavailable while its Hephaestus model rows stay on.
