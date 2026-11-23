# ADR 0124: Hephaestus gives practice feedback with a conversation, and practices are the unit

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0056](0056-one-practice-model-for-items-and-scans.md) (one practice model), [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) (the review pipeline), [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) (the conversation)

**Relates to:** ADR 0120 to ADR 0123 (proposed in #4862), ADR 0125 to ADR 0130 and ADR 0134 (proposed in this pull request)

## Context

Hephaestus reviews work against the practices that a team values.
It delivers feedback on the work, on the practice page and in conversation ([ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md)).
The conversation answers questions about that feedback ([ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md)).

The next decisions add goals, memory, check-ins, private checks and learning that travels with the person.
Each of them needs one test: does it help a person apply a practice in real work?
Without a stated product frame, each feature argues its own scope, and the frame drifts toward a chat assistant or a review bot.

Software engineering is the first domain.
Courses, research labs and leadership work have the same shape: a person does work, and the work shows a practice or not.
A frame that hard-wires pull requests breaks when a second domain ships after the 1.0 freeze.

The research that Hephaestus supports also needs more than one kind of reviewed work.
Its questions compare feedback on pull requests and on issues, so issues need review in 1.0.

The language guide now forbids "AI mentor" for Hephaestus.
The maintainer decided that "AI mentor" is the product category, and that the word "mentor" alone stays reserved for people.

## Decision drivers

- Feedback is grounded in the person's real work, with the cited line.
- The goal is the person's growth over months, never their use of the product.
- One model of practices serves every domain.
- Hephaestus is honest that it is software. It is not a person, and it is not an agent.

## Considered options

1. **A review bot.** Hephaestus posts on pull requests and nothing else. It has no continuity, no private place to ask, and no memory of goals. Every comment is public, so a person cannot fail safely.
2. **A general chat assistant with repository access.** It answers anything. It has no practice model, so its claims are opinions, not evidence. It cannot withhold a claim that the evidence does not support.
3. **A framework for software engineering only.** Practices, roles and evidence assume pull requests. Each later domain then changes the schema and the API after the freeze.
4. **Practice feedback with a conversation, with practices as the unit in every domain (chosen).**

## Decision

Option 4.

1. **One product.** Hephaestus gives a person practice feedback on their real work, and a conversation about it. The feedback and the conversation share one model of practices and observations.
2. **The practice is the unit.** Reviews, observations, feedback, goals, growth and the help mode each refer to a practice.
3. **A review only produces observations.** The server admits and seals them ([ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md)). Feedback and messages come from sealed observations (ADR 0120 and ADR 0121).
4. **Three records support a claim about someone's work.** They are an observation, a private check outcome and a self-report. Everything else is context and never evidence.
   - A self-report is marked as such and never counts in totals.
   - A capture failure says "Could not check". It never becomes a claim about the work.
5. **The model is domain-neutral.**
   - A practice pack owns practices. An adapter owns item kinds. One pack can serve many adapters.
   - Each item kind declares what it counts as: `WORK`, `ACCOUNT` (the person's own account of work elsewhere) or `CONTEXT` (never reviewed).
   - A calendar is context only. Hephaestus never treats the time of someone's work as evidence.
   - The adapter declares the roles that a person can hold for an item. An observation concerns the holder of a practice role.
6. **Issues are a reviewed item kind in 1.0,** on GitHub and GitLab, beside pull requests and merge requests.
   - Issues are excluded by default. A workspace admin includes them and can exclude them again.
   - Slack and Outline add no reviewed item kind. Their adapters stay as they are.
   - This rule can move back to 1.x. If the research scope narrows to pull requests, issue review returns to 1.x.
7. **Code decides, and a model judges.** Deterministic code does every step that it can. A model runs only where judgement is necessary.
8. **The category is "AI mentor".**
   - "AI mentor" is the product category in product text, onboarding and docs. It is always written with "AI".
   - "Mentor" alone means a person who guides another person.
   - Hephaestus is the name. "Conversation agent" and "composer" are system terms. Hephaestus is not an agent.
9. **What Hephaestus is not.**
   - It shows no score, rank, streak, badge or level.
   - It never writes what a person submits as their own work.
   - It is not a friend, a therapist or a channel to human resources.
   - It is never a performance instrument (ADR 0125, proposed in #4863).

### What this changes in other ADRs and docs

- The language guide (`docs/contributor/practice-feedback-language.mdx`) changes when this ADR is accepted. The row for Hephaestus removes "AI mentor" from its Avoid column. A new row names "AI mentor" as the product category. The row for Mentor stays.
- "Developer" stays the term in practice feedback. "Person" becomes the term in the API and the schema elsewhere, because a domain other than software has no developers.
- [ADR 0056](0056-one-practice-model-for-items-and-scans.md) stays. ADR 0130 (proposed in #4863) adds a portable identity to each practice.

## Consequences

- Good: every later decision has one test. A feature that does not help a person apply a practice is out of scope.
- Good: a second domain adds a practice pack and an adapter. It does not change the schema.
- Good: claims about work come only from three kinds of record, so admission stays the one gate for evidence.
- Bad: "AI mentor" raises expectations. People can read it as a human mentor service. The product text must say that Hephaestus is software.
- Bad: "person" in the API and "developer" in practice feedback live side by side. This ends when the vocabulary stages finish.
- Bad: domain-neutral names cost more thought now, for a second domain that is not built yet.
- Bad: issue review adds a second reviewed item kind to 1.0. Practices must say which item kinds they read.

## Confirmation

Not enforced yet. The implementation adds these checks:

- `gate:prose` fails when product text uses "mentor" alone for Hephaestus, or "AI mentor" for a person.
- A review gateway test fails when a review run calls a tool that writes outside its observations.
- A fixture test with an item kind of class `CONTEXT` fails when such an item produces an observation.
- An integration test fails when an issue of a workspace that did not include issues gets a review.

## Revisit trigger

- A second domain needs an item kind that `WORK`, `ACCOUNT` and `CONTEXT` cannot describe.
- Support requests or study interviews show that people read "AI mentor" as a human service.
- The research scope narrows to pull requests. Then issue review moves back to 1.x (decision 6).

## Sources

- Hattie and Timperley, [The Power of Feedback](https://doi.org/10.3102/003465430298487), Review of Educational Research, 2007. Feedback about the task and its process helps, and feedback about the self helps little. This makes the practice in the work the unit, not the person.
- Kluger and DeNisi, [The effects of feedback interventions on performance](https://doi.org/10.1037/0033-2909.119.2.254), Psychological Bulletin, 1996. About a third of feedback interventions lowered performance, most when attention moved to the self. This is why Hephaestus shows no score, rank or level.
