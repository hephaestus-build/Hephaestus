# ADR 0162: A team owns its growth record in its workspace

**Status:** Proposed
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) for shared Slack channels.

**Builds on:** [ADR 0158](0158-the-mentor-orders-commitments-per-person-and-answers-per-request.md). ADR 0159 defines message places. ADR 0160 defines review producers and guidance. ADR 0163 defines continuation by reference.

## Context

Some work belongs to a team. A pipeline or a design can change through several people's decisions. Practice Review can assess that work. The team needs to record what it will try, who can see it and what changed in a later version. A team finding has no place in a person's practice standing.

## Considered options

- **A second mentor for teams.** This gives the team a clear entry point but duplicates review, decision and follow-up state.
- **Copy team observations to personal profiles.** This loses the work's team ownership and can leave copies after access changes.
- **Use team scope in the same framework.** Reuse the request, review, commitment and delivery paths. Bind each read and write to the team and its current audience.

## Decision

1. Represent each team as a record inside one workspace. Store the workspace ID, the team's name and members drawn from current workspace members. A project lead can create a team. A provider team can supply its membership. The workspace keeps custody. Check the current team membership at each read and send.
2. Workspace admins select the practices that express project expectations. A team goal is a shared commitment. An authorized team member must explicitly create or change it. The mentor can suggest a goal or a next step. It cannot create a commitment, change an expectation or assign a person. A review can run without a goal or individual assignee.
3. A sealed review of team-owned work can enter the same runtime under the team key in `MentorEvent.ownerOrSharedKey`. A future TEAM producer uses the same immutable producer choice as MENTOR and WORK_PLACE. Team decisions and follow-ups use the same commitment store, ordered per team. The workspace's authorized model assignment and payer cover team work.
4. Team guidance uses the reserved `TEAM` audience. It stores advice, revisions and references to evidence at its source revision. An update or retire changes guidance only. A team finding creates no result row or practice standing for an individual. Practice Review keeps its evidence and result.
5. A person's mentor can load authorized team work by reference. Check current team access on every load. If a member loses access, the reference becomes unavailable. The personal mentor can help relate work to an owner's goal in the app while keeping each record in its own custody. Sharing a plan to the team needs an explicit person action, current audience authority and its own effect key.
6. A Slack channel thread is a team place. Separate the grants to read authorized sources, answer a deliberate mention in its thread and start a project update. Verify the whole audience, including guests, external members and applicable export readers, at read and send. Channel membership grants no access to personal records. A channel has no personal context and starts no monitoring or posting by default. **Continue privately** opens an authenticated app turn by reference, with a new access check. It sends no copy back.
7. Only the channel rules in decision 6 apply to 1.0. Keep shared channels disabled until the audience proof passes. Starting project updates stays outside 1.0. The team record, TEAM producer, TEAM guidance and team commitments are later work. Reviewing an upload without a connected repository requires a separate connector decision.

## Consequences

Team work can have continuity through repeated reviews and decisions. The same system can handle owner-private and team-scoped work. Each operation needs up-to-date membership and audience proof. An improved artifact alone cannot establish an individual's performance or a team's learning.

## Confirmation

The 1.0 gate exercises a deliberate mention for a verified whole audience and a hold when a guest or stale reader breaks proof. The provider request and visible reply contain no personal records. The default admits no channel effect.

The later TEAM implementation must prove the following before enablement:

- An authorized team member can record a goal. Another role, the model and a provider event cannot record it.
- After removal from the team, a member's next personal mentor load cannot return team decisions or source words.
- A team finding creates no individual assessment or practice standing. Retiring guidance leaves the evidence unchanged.
- A team can request a review and revisit its recorded decision on a later version. Check both a connected work item and an uploaded artifact after its connector is available.

## Revisit trigger

Revisit the conversation view if teams repeatedly need past decisions that the project review view cannot present. Revisit the actor and audience model if a provider team cannot supply current membership.

## Sources

- [Järvelä et al., socially shared regulation of learning](https://doi.org/10.1007/s11412-016-9238-2) motivates making team planning, monitoring and adaptation visible.

## Update — 2026-11-27: the team mentor starts in January 2027

The maintainer decided that January 2027 delivers a Team Mentor.
This update replaces decision 7.
[ADR 0165](0165-a-team-reviews-immutable-submitted-versions-within-its-own-scope.md) states how a review of a team item hands off to the mentor.

**Decision 7 now reads:** the team record, the TEAM producer, TEAM guidance and team decisions are enabled from January 2027 for flagged workspaces.

- They ship dark in the 1.0 release candidates. Stable 1.0 does not depend on them.
- Team goals and team commitments stay later work.
- The channel rules of decision 6 are unchanged. Shared channels stay disabled until the audience proof passes.
- The team mentor writes to no Slack channel in January. Starting project updates stays outside 1.0.
- ADR 0165 is the decision for submitted work without a connected repository.

A team decision is the record of ADR 0165 decision 8, one for each problem.
It is not a commitment, so the commitment store of decision 3 holds no team decision.
