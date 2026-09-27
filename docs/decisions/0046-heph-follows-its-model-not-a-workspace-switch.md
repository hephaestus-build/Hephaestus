# ADR 0046: Heph follows its model, not a workspace switch

**Status:** Accepted
**Date:** 2026-09-27
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md) (per-purpose agent bindings)

## Context

Whether a workspace offered Heph was decided twice: by the workspace switch **Chat with Heph** and by
whether a Heph model binding under **AI models** was ready. The two could disagree. With the switch on
and no ready binding, Heph was offered and could not answer; with a ready binding and the switch off,
the model sat configured and unused.

## Decision drivers

- One control per question: an admin who sets up a model for Heph means Heph to answer.
- No workspace gains Heph by upgrading.
- Each member's AI choice keeps deciding whether Heph answers them.

## Considered options

1. **Keep both controls.** Rejected: two places to turn Heph off, and a state where Heph is offered
   but cannot answer.
2. **Keep the switch and ignore binding readiness.** Rejected: the switch cannot make an unready model
   answer.
3. **Offer Heph wherever a Heph model binding is ready, and remove the switch.** Chosen.

## Decision

Heph is offered to every member of a workspace where a Heph model binding is ready, on the web and in
Slack direct messages, within each member's AI choice. Workspace admins manage the bindings under
**AI models**; there is no separate switch.

- **Turning Heph off** is turning off the workspace's Heph model rows; turning them back on restores it.
- **The upgrade carries the switch over:** in workspaces that had **Chat with Heph** off, it turns the
  Heph model rows off.

## Consequences

- The floating Heph panel appears only where a Heph model is ready.
- The restore-clone lockdown turns off every Heph model row, and lifting it means turning them back on
  under **AI models**.
- The schema change is expand/contract: the switch's column stays in place, unread, until a later
  release drops it.
- Which Heph rows were on before the upgrade is not recorded; undoing it needs a pre-upgrade backup.

## Revisit trigger

A workspace that needs Heph unavailable while its Heph model rows stay on.
