# ADR 0047: The Practice profile has one reader

**Status:** Accepted
**Date:** 2026-09-28
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md) (channel audiences),
[ADR 0017](0017-replace-keycloak-with-spring-native-auth.md) (read-only user views, updates of 2026-09-11
and 2026-09-24)

## Context

[#1273] planned the practice-centric profile for two audiences: the developer and the workspace's
admins. The developer's half shipped as the Practice profile, and [#2260] removed what was left of the
old XP and league profile. Only the workspace-admin audience remains. Every Practice profile endpoint
serves the caller and nobody else; the one way to read another person's profile is an instance
administrator's audited **View as user**.

A workspace-admin audience would break what the product tells developers today:

- `docs/user/privacy.mdx`, on reading a member's practice pages: "Workspace administrators do not have
  this access."
- [Practice feedback language](../contributor/practice-feedback-language.md): feedback about a way of
  working is "prepared for one developer about what recurs across their work, readable by nobody
  else — the `IN_APP` channel".
- [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md): the audience of `IN_APP` is the
  "Recipient's private practice pages".
- `docs/admin/practice-review.mdx`, under **Review before sending**: "the developer's own practice pages
  and the mentor are written regardless, because nobody but the developer reads them and only on
  request."

The last one carries weight: in-app feedback skips the approval queue because it has one reader. Give it
a second and it becomes feedback about a person that reaches their admin with nobody having approved it.

What a workspace admin needs — what reviews concluded about a member, and a way to correct a wrong
conclusion — is already served by the admin practice-review pages.

## Decision drivers

- A promise made to developers is not reversed as a side effect of a feature.
- Feedback that skips approval is feedback nobody else reads.
- Someone who reads a named developer's record gives a reason and leaves a record of the read, as
  **View as user** does ([ADR 0017](0017-replace-keycloak-with-spring-native-auth.md), update of
  2026-09-24).
- One place per question for an admin.

## Considered options

1. **A workspace-admin audience:** read-only copies of the Practice profile endpoints for workspace
   admins, without the feedback text. Rejected: it reverses the four promises above. Standings, trends
   and the summary are a claim about a person across their work, so leaving out the feedback text does
   not make the view any less personal. It would need the disclosure records of [#1421] for every read,
   an amendment to ADR 0029's `IN_APP` audience, a privacy notice change that tells every member, and a
   new answer to why in-app feedback skips approval.
2. **A member opt-in to show their profile to workspace admins.** Rejected: nobody has asked for it,
   and it adds a consent surface with its own reverse state, its own record and its own wording in each
   instance's privacy notice, for a view admins do not need. Opt-ins for peer and public audiences
   belong to [#1275] and are not decided here.
3. **The Practice profile has one reader, the developer.** Chosen.

## Decision

The Practice profile is the developer's alone. Each of its endpoints serves the caller and takes no
user to read on behalf of.

- **Workspace admins** read what reviews concluded about a member in the admin practice-review pages:
  **Practices → Practice reviews → Observations**, filtered to that member, and the review and delivery
  pages beside it. They can mark an observation incorrect there. They see that in-app and conversation
  feedback exists and where its delivery stands, but not its text (`bodyVisibleToOperator` in
  `ReviewFeedbackQueryService`).
- **Instance administrators** can read a member's Practice profile through **View as user**
  (ADR 0017, update of 2026-09-24): read-only, with a stated reason, and each read recorded as a
  `USER_VIEW` audit row.

## Consequences

- The privacy page, the vocabulary and ADR 0029 stay as they are, and in-app feedback keeps skipping the
  approval queue.
- A workspace admin who wants to see what a developer sees asks the developer.
- An instructor or cohort view ([#1274]) and peer or public tiers ([#1275]) are new audiences, each
  decided in its own ADR rather than by widening this one.
- An endpoint that reads another user's Practice profile outside **View as user** is a change to this
  decision, not an addition beside it.

## Revisit trigger

Workspace admins who cannot do their job from the practice-review pages — for example, a course that
has to assess a named developer's practice over a term — or members asking to share their Practice
profile with their admins.

[#1273]: https://github.com/hephaestus-build/Hephaestus/issues/1273
[#1274]: https://github.com/hephaestus-build/Hephaestus/issues/1274
[#1275]: https://github.com/hephaestus-build/Hephaestus/issues/1275
[#1421]: https://github.com/hephaestus-build/Hephaestus/issues/1421
[#2260]: https://github.com/hephaestus-build/Hephaestus/pull/2260
