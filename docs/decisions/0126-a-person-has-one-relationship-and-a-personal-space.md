# ADR 0126: A person has one relationship and a personal space

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) (SQL tenancy), [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) (one account), ADR 0124 (proposed in #4866)

**Relates to:** [ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md) (what travels into the personal space), [ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md) (the person's authorization generation)

## Context

On main, a person has one account and is a member of team workspaces ([ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md)).
Every row that holds work data belongs to one workspace, and SQL enforces it ([ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md)).
Nothing belongs to the person across workspaces.

A career outlives a team and an employer.
Goals, lessons and growth need a home that no team owns.
If a team removes the person, that home must stay.
The real constraint is that SQL tenancy stays literal: a second tenancy model would need a second set of isolation tests.

## Decision drivers

- What the person learns stays theirs when they move to another team.
- One tenancy model and one set of isolation tests.
- An employer cannot cut a person off from their learning.
- The operator controls the model spend.

## Considered options

1. **A separate account for each employer or team.** Learning stays behind at every move. The person must start again.
2. **A personal store outside the workspaces.** It needs its own tenancy, its own queries and its own isolation tests. Every module must learn a second kind of owner.
3. **Personal records as rows of the account, with no workspace.** The workspace statement inspector cannot protect them, and a run could read them beside team data without a tenancy check.
4. **One relationship for each account, and a personal space that is a workspace of kind `PERSONAL` (chosen).**

## Decision

Option 4.

1. **One relationship for each person on an instance.** It belongs to the account. It holds the help settings, the change-feed sequence and the person's authorization generation.
2. **Every workspace-owned row has exactly one workspace.** One run reads one workspace. SQL tenancy stays literal.
3. **The personal space is a workspace of kind `PERSONAL`.**
   - It has one member, its owner, and no team connections.
   - Its slug is `me--` and 16 random characters. A team slug cannot take that form.
   - A guard runs before any controller. Staff routes accept only team workspaces.
4. **The personal space exists from the first need.** The first personal record, transfer or grant creates it, in one idempotent transaction. Sign-up creates nothing.
5. **Runs in the personal space are on by default.**
   - They follow the same model readiness and the same model budget for each payer as team workspaces.
   - An operator can turn them off with one instance switch.
   - Turning runs off never removes storage or access.
6. **Leaving keeps the learning.**
   - When a team removes a person, the person gets a 30-day leaving window with a scoped session.
   - In that window, the person can transfer their own words ([ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md)) and download their data.
   - The team cannot close that window.
   - A person whose identity is removed gets a verified portability address.
7. **Absorption and erasure suspend the space.** They run while the workspace is `SUSPENDED`. Main's filter already hides a space that is not active. No new status exists.
8. **Personal-space records enter Slack one at a time, by the person's act.** No turn takes them to Slack on its own.

### What this changes in other ADRs

- [ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md) stays. The personal space is a workspace, so every rule of it applies.
- [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md) stays. The relationship adds account-level rows beside the account. It adds no second account.

## Consequences

- Good: one tenancy model covers team and personal data. `DataIsolationArchitectureTest` and the inspector protect both.
- Good: a person who leaves a team keeps their learning.
- Good: no empty personal spaces exist for people who never need one.
- Bad: every admin and staff route needs the team-only guard. A route that forgets it can show a personal space to staff. A test covers each route.
- Bad: personal-space runs cost the operator model calls that no team pays for. The instance switch is the only control in 1.0.
- Bad: the leaving window keeps a scoped session alive for 30 days after the team removed the person.

## Confirmation

Not enforced yet. The implementation adds these tests:

- A guard test fails when a staff route answers for a workspace of kind `PERSONAL`.
- A provisioning test fails when two parallel first needs create two personal spaces.
- A slug test fails when a team workspace can take a slug of the form `me--`.
- A leaving test fails when a removed member cannot use the scoped session within 30 days. It also fails when they can after it.
- A switch test fails when turning personal-space runs off removes a record or access.

## Revisit trigger

- A person needs connections in the personal space, such as a personal repository. The space then needs person-owned connections.
- Most operators turn personal-space runs off because of cost.

## Sources

- OpenAI, [Memory FAQ, Business version](https://help.openai.com/en/articles/9295112-memory-faq-business-version). Without a merge, "your personal workspace, conversations, and memories will remain separate". This confirms that a personal space apart from the team workspace is what people expect.
