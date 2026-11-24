# ADR 0148: Actions on a person's behalf need an approval bound to a digest, and 1.0 reserves only their names

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 10 (the agent proposes, and the server acts), ADR 0135 (proposed in #4867, no reserved shapes)

**Relates to:** ADR 0121 (proposed in #4862, typed commands on item cards)

> **1.0 does not build this.**
>
> - A 1.x release builds action proposals, action approval, standing authorization and audit records.
> - 1.0 reserves only the names of the action kinds and their enum values.
> - 1.0 creates no table, column, event kind, task state, operation or problem type for actions.

## Context

A person may want Hephaestus to act on the work: push a fix, open a pull request, create an issue or update a doc.
Each such action is a write that other people read, in the person's name.

Typed commands on item cards already exist in 1.0 (ADR 0121, proposed in #4862).
They change Hephaestus's own records, such as Mark card done.
An action is different. It changes a provider's state, and it can be hard to undo.

Two risks are real.
The approved text can differ from the written text, if anything changes between approval and execution.
The installation token has wider rights than the person, so an action with that token can do what the person could not.

## Decision drivers

- The person approves the exact write that happens.
- An action never does more than the person could do alone.
- No model runs while the person decides.
- 1.0 carries no unused shape.

## Considered options

1. **Actions in 1.0.** The proposal, approval and audit flows add scope before the core conversation ships.
2. **Approval of a summary.** The person approves a description, and the server writes what the model drafted. The two can differ.
3. **The installation token as the principal.** The app's rights then apply, also on work that the person cannot change.
4. **A model turn that waits for the approval.** The turn holds a sandbox for minutes, and a late approval meets a changed context.
5. **Standing authorization that pushes to the person's branch.** A mistake lands in the person's work with no review.
6. **Reserved names now, and a bound approval later (chosen).** 1.0 reserves the names. In 1.x, an approval binds a digest of payload, target and expected version.

## Decision

Option 6. Decision 1 applies in 1.0. Decisions 2 to 11 bind the 1.x design.

1. **1.0 reserves the action kind names:** `PUSH_CHANGE`, `OPEN_PULL_REQUEST`, `CREATE_ISSUE`, `UPDATE_DOC`, `REQUEST_REVIEW`, `REVIEW_REMINDER` and `COMMENT`. No other action kind uses them. The enums that will carry them are open, so 1.x adds them without a break.
2. **The principal is the requesting person.** The server checks the person's own provider permission on the target at execution. The installation token stays in the server.
3. **An action proposal shows the exact payload, the target and the expected version.** It has **Discard** and an approve button that names the action:
   - `PUSH_CHANGE`: **Push commit**
   - `OPEN_PULL_REQUEST`: **Open pull request**
   - `CREATE_ISSUE`: **Create issue**
   - `UPDATE_DOC`: **Update document**
   - `REQUEST_REVIEW`: **Request review**
   - `REVIEW_REMINDER`: **Send reminder**
   - `COMMENT`: **Post comment**
4. **The approval binds a digest of payload, target and expected version.** At execution, the server checks the principal and the target version again. A substantive change needs a new approval.
5. **No model turn runs while the person decides.** The approval is a state change of the action proposal.
6. **Standing authorization is narrow, revocable and on the person's own work only.** It never pushes to the person's branch. It makes a suggested change or a pull request from a new branch. It is defined only after a first action kind ships with the rules above.
7. **Never covered:** a merge, an approving pull request review and anything on another person's work. Anything that cannot be undone or that crosses workspaces is never covered either.
8. **Undo is a named compensating operation,** with its conflict and unavailable outcomes stated.
9. **Provider attribution.** The Hephaestus app is the provider author. The person is the co-author where the provider records one. Hephaestus never approves or merges.
10. **Each action writes an audit record** that names the person, the digest and the outcome.
11. **Requests between the conversation agents of two accounts are 1.x too,** with their own readers stated.

### What this changes in other ADRs

No ADR changes in 1.0. In 1.x, each action is an outbox action under [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 10, with the approval as the transaction that inserts it.

## Consequences

- Good: what the person approves is exactly what happens.
- Good: an action never exceeds the person's own rights on the provider.
- Good: 1.0 ships no table or route for a feature that does not exist.
- Bad: in 1.0, a person makes every change on the work alone.
- Bad: a change in the target between proposal and execution makes the person approve again.
- Bad: some useful actions, such as a merge, are never offered.

## Confirmation

Not enforced. 1.0 has no approval-bound action flow.
The outbox of `agent_action` and `ActionExecutor` stays as it is. Email, feedback and Slack writes use it.

Review catches a 1.0 change that adds one of these:

- a reserved action kind
- an approval table
- an approval operation
- an approval task state.

When actions ship, a test fails when an execution's digest differs from the approved digest. Another test fails when an action runs with rights that the person does not have.

## Revisit trigger

- People ask for actions on the work in shared conversations more than once a month.
- A provider adds fine-grained tokens on behalf of a user that remove the need for the installation token's check.

## Sources

- OWASP, [LLM06:2025 Excessive Agency](https://genai.owasp.org/llmrisk/llm062025-excessive-agency/). Limit an agent's permissions to the user's, and require approval for high-impact actions. This gives decisions 2 and 4.
- GitHub Docs, [Creating a commit with multiple authors](https://docs.github.com/en/pull-requests/committing-changes-to-your-project/creating-and-editing-commits/creating-a-commit-with-multiple-authors). A `Co-authored-by` trailer records a co-author. This gives decision 9.
