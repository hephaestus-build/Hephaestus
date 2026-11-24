# ADR 0121: A composer on a shared thread writes feedback on the work, and it cannot read private data

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) decisions 2, 11 and 12. [ADR 0079](0079-one-agent-system.md) § Interactive and proactive work and its update of 2026-11-09 on the review run kinds.

**Builds on:** [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) (threads, inbox items and the commit rule), [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (private practice feedback has one reader), [ADR 0090](0090-a-review-cites-only-captured-bytes-and-admission-verifies-each-citation-once.md) (citations)

**Relates to:** [ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md) (the copy deadline), [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) (Slack channel threads)

## Context

On main, a `REVIEW_COMPOSE` run composes feedback on the work after the seal, in the review's sandbox ([ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) decision 11).
Its tools check and publish a package, and it holds no provider token (decision 12).
It has no path for a reply on the work or for a person's command about the copy.

With [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md), the conversation agent holds the person's private conversation.
A pull request is read by the team.
If one model reads private text and writes where a team reads, any private fact can reach that team.
A prompt in the work can also ask the model to repeat private text.

An output filter on such a writer must catch every leak.
A model-based disclosure check is itself a model that can miss, and its false positives withhold valid feedback.
The real constraint is the input of the writer, not the text of its output.

## Decision drivers

- Feedback earns trust or it is not sent. A teammate never reads a person's private conversation.
- The boundary must be provable by a test, not estimated by an evaluation.
- The person's acts about the work stay possible from the private conversation.
- A reply on the work and a person's command reach the writer through the same inbox as other inputs.

## Considered options

1. **The conversation agent writes on the work, behind output filters.** One model holds the private conversation and writes the public copy. A model-based disclosure check and a short-title check filter its output. Each filter can miss, and a miss is public. False positives withhold valid feedback.
2. **Free-text handoff from the private side.** The conversation agent passes a brief or a plan in its own words to a separate writer. Free text can carry private facts and injected instructions. A closed, typed plan narrows that, but private state still shapes public text and timing.
3. **Keep `REVIEW_COMPOSE` as a separate run.** A reply on the work and a command need their own run kinds and their own order. Composition stays outside the inbox and the commit rule of ADR 0120.
4. **A composer that runs turns on a shared thread and reads only scoped projections (chosen).**

## Decision

Option 4.

1. **The audience rule.** Each thread is private or shared. A turn reads only sources that every reader of its output may read.
   [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decision 5 states the one exception: the person's Slack grant.
2. **Private threads belong to one account's conversation agent.** The conversation agent never writes on the work. It has no tool that reaches a shared place.
3. **Shared threads belong to the workspace.** A **work place** is the shared thread of one pull request or merge request.
   - In 1.0, an issue gets no work place and no copy on the work. ADR 0124 (proposed in #4866) states the issue scope.
   - Its key is the workspace and the provider target.
   - The seal of the first review with an eligible copy on the work creates it.
   - A Slack channel thread is the other shared kind ([ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md)).
4. **The composer runs the turns of every shared thread.** On a work place, it reads only scoped projections. Their SQL binds workspace, audience, place kind and canonical place together. The projections are:
   - the eligible set for the work of this place and version.
   - the cited parts of the work.
   - the provider thread of Hephaestus's own copies, with replies and thread states.
   - the inbox items of this thread, and the workspace's practice catalog.
5. **A foreign ID resolves to nothing.** A typed reference resolves only through a composite key with the thread's workspace and place. The composer reads no conversation, practice-page copy, private check or personal record.
6. **The gateway binds each tool to its place kind.** A work place has `check_package`, `publish_package`, `edit_own` and `reply_on_work`. A tool returns a staged draft. Admission happens at the commit of ADR 0120.
7. **Four guardrails run on each tool call and again at commit.** They reuse main's validation (`FeedbackGate`, `store.check`, `publication.refusal`).
   1. **Destination rules.** The feedback mode applies, and main's eligibility holds: author only, open, not a draft, no repeat.
   2. **Observation reference validation.** Each cited problem is in the eligible set, and the target's readers can read it. Each outcome or delivery statement matches the facts that the server renders.
   3. **Required problems.** The required Critical and Major problems of the eligible set come first, with their stored labels and statements. A confirmed exclusion changes that set first.
   4. **Markdown sanitization.** Images, raw HTML, comments, tag characters and bidirectional controls are dropped. Mentions are escaped.

   A refusal returns its reason, and the model rewrites once. A second refusal withholds an unpublished copy with that reason. A refused edit leaves the published copy.
8. **A typed command is the only path from a private thread to a shared place.**
   - A command carries IDs and enumerated values only, never a reason or free text.
   - The person confirms it on the item card, after a preview such as "Post 2 problems on PR #412".
   - The confirmation binds to the preview's receipt: operation ID, place, review, head, generation and shown selection.
   - If a fact changed, the server returns a fresh preview and writes nothing. A repeated confirmation writes nothing.
   - The commands are Request a new copy, Exclude, Include again, Withdraw and Post these exact words.
   - Exclude and Include again reverse each other. Withdraw reverses any posted copy.
   - Request a new copy and Post these exact words have no cancel. Withdraw removes the copy that they post.
   - Exclude of a Critical or Major problem needs the workspace policy `allowSevereExclusion`. It is off by default.
   - When the policy is off, the preview refuses that selection and says why.
   - A workspace admin turns the policy on or off in either admin console. Each is the other's reverse.
   - The conversation agent can attach a command control to its message. It never issues a command.
   - The composer sees "post now" or "leave problem p out", never why. A reason stays in a private row.
9. **The composer can answer a reply to its own copy,** in Automatic mode only.
   - The item is open, the head is the reviewed head, and the reply is less than 24 hours old.
   - At most one reply for each turn, and three for each thread and day. It never answers a bot.
   - A person who resolved the thread wins. The composer never edits a copy because of a reply.
10. **Graded work uses no model on the work.** **Graded work** is a workspace mode.
    - In it, a fixed renderer writes every copy on the work from the eligible set.
    - The same guardrails and send checks apply.
    - ADR 0149 (proposed in #4868) decision 5 decides how graded work and a delivery experiment exclude each other.
11. **The practice-page copy.** For results in the eligible set, the practice-page copy is a server projection of the composed copy on the work. It is not a second route of the work-place turn ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 8).
    - The conversation agent writes the practice-page copy for private-only results.
    - Until a copy exists, the page renders the admitted results from rows.
12. **No model-based disclosure check.** The composer has no private input, so isolation tests prove the boundary. They assert workspace, place and audience together, with row-level fixtures. They run on the packaged server as a release gate.
13. **Cutover.** Outside the rollout, main's `REVIEW_COMPOSE` stays the producer until the cutover, with its own lease check.
14. **Our work is a private page.** It shows the person's relationship with Hephaestus, organized around goals: the goals, the check-ins and where help stepped back.
    - Its audience is the account alone, as for a private thread. No staff role reads it.
    - No shared thread writes to it, and nothing on it reaches a shared place except by a typed command.

### What this changes in other ADRs

- [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) decision 11 changes. The seal writes an inbox item for the work place. A composer turn on `conversation:<work place id>` composes, not a `REVIEW_COMPOSE` run in the review's sandbox.
- ADR 0086 decision 2 loses the `REVIEW_COMPOSE` run kind after the cutover.
- ADR 0086 decision 12 stays and grows. The tools return staged drafts, and `reply_on_work` joins them.
- ADR 0086 decision 13 stays: one notifying write, at most 3 edits and 12 model calls.
- ADR 0137 (proposed in #4863) decision 10 points here. The `allowSevereExclusion` policy belongs to the Exclude command of decision 8.
- [ADR 0079](0079-one-agent-system.md) § Interactive and proactive work runs `review_compose` in the review's sandbox. So does its update on the review run kinds. The composer now runs in the sandbox of its work place.

## Consequences

- Good: no private text can reach the work through the composer, because the composer never reads it.
- Good: a test proves the boundary. No evaluation of a model filter is needed.
- Good: a reply on the work and a person's command use the same inbox and commit rule as other input.
- Bad: the composer cannot use what the person said privately, also when that would improve the copy.
- Bad: a person who wants a change on the work must confirm a command on the card. A request in the conversation alone changes nothing.
- Bad: two writers describe one review independently. The composer writes for the team, and the conversation agent writes for the person.
  Citation tokens keep the outcomes equal. Nothing keeps the emphasis or the suggested next step consistent.
- Bad: each open pull request with a copy costs one more turn and one more sandbox subject. A warm review sandbox no longer composes.
- Bad: a confirmed Request a new copy cannot be stopped before it posts. Withdraw removes the copy after the post.
- Bad: a bug in a projection's SQL is a leak. The isolation tests and review of each projection carry that risk.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- The composer isolation tests fail when a private row is visible to a composer turn. They also fail when an ID of another place or workspace resolves. PackagedIsolationTest runs them on the packaged server.
- ComposerTokenContradictionTest fails when a body states an outcome that contradicts its token.
- ExclusionBumpsEligibleSetTest fails when a confirmed exclusion of a Major problem does not change the set and its generation first.
- PreviewDriftTest and RepeatedConfirmationTest fail when a stale or repeated confirmation writes.
- ExclusionRefusedInPreviewTest fails when the preview allows a Major exclusion while `allowSevereExclusion` is off.
- A gateway test fails when a tool of one place kind answers a lease of another place kind.

## Revisit trigger

- The composition eval shows that copies need context that only the private conversation holds.
- A provider exposes reviewer replies that the composer must answer outside Automatic mode.
- An isolation test fails after release.

## Sources

- Simon Willison, [The lethal trifecta for AI agents](https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/), 2025. An agent with private data, untrusted content and external communication can be tricked into "sending it to that attacker". The composer has two of the three and no private data.
- Debenedetti et al., [Defeating Prompt Injections by Design](https://arxiv.org/abs/2503.18813), 2025. CaMeL extracts "the control and data flows from the (trusted) query". Then "the untrusted data … can never impact the program flow". Typed commands with IDs only follow that rule.
