# ADR 0124: Hephaestus gives practice feedback with a conversation, and practices are the unit

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:**

- [ADR 0056](0056-one-practice-model-for-items-and-scans.md) (one practice model)
- [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) (the review pipeline)
- [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) (the conversation)
- [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (Activity)

**Relates to:**

- ADR 0120 to ADR 0123 (proposed in #4862)
- ADR 0125 to ADR 0130, ADR 0134 and ADR 0137 (proposed in #4863)
- ADR 0132 and ADR 0133 (proposed in #4865)

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

Main already reviews issues ([ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md), [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md)).
The research that Hephaestus supports compares feedback on pull requests and on issues, so issues must enter the new delivery flow in 1.0.

The language guide forbids "AI mentor" for Hephaestus today.
A category name sets what people expect from the product.
The design does more than help with one task, so the name must say so, and it must not promise a person.

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

The names considered for the category:

- **Coach.** A coach works on one skill toward a goal that a sponsor often sets, for a bounded engagement. Hephaestus keeps the person's own goals across teams, with no end date.
- **Tutor.** A tutor teaches set content toward a grade. Hephaestus works on the person's real work, and it must not grade.
- **Assistant.** An assistant does the task for the person. Hephaestus never writes the person's submission, and it grows the person's own judgment.
- **Reviewer bot.** It names option 1: feedback on the work, with no private place, no memory and no return.
- **AI mentor (chosen).** Decision 8 gives the reasons.

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
6. **Issues stay a reviewed item kind in 1.0,** on GitHub and GitLab, beside pull requests and merge requests. The first two rules are new.
   - Issues are excluded by default. A workspace admin includes them and can exclude them again.
   - Issue feedback is private in 1.0: no work place and no copy on the work (ADR 0121).
   - Slack and Outline add no reviewed item kind. Their adapters stay as they are.
   - If the research scope narrows to pull requests, the private delivery of issue feedback moves to 1.x.
7. **Code decides, and a model judges.** Deterministic code does every step that it can. A model runs only where judgement is necessary.
8. **The category is "AI mentor".**
   - "AI mentor" is the product category in product text, onboarding and docs. It is always written with "AI".
   - "Mentor" alone means a person who guides another person.
   - Hephaestus is the name. "Conversation agent" and "composer" are system terms. Hephaestus is not an agent.
   - Five parts of the design make Hephaestus an AI mentor and not a coach. Goals in the person's own words outlive a team (ADR 0132, ADR 0129).
   - Hephaestus comes back at agreed times (ADR 0133). Help fades on evidence (ADR 0130).
   - The person sees and controls what Hephaestus keeps in mind (ADR 0132). Hephaestus refers the person to people for what it cannot do.
9. **What Hephaestus is not.**
   - Practice feedback and any assessment of a person show no score, rank, streak, badge or level. Activity keeps the views of [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md).
   - It never writes what a person submits as their own work.
   - It claims no feeling, infers no emotion, and sends no message only to bring a person back.
   - It is not a friend, a therapist or a channel to human resources.
   - It is never a performance instrument (ADR 0125, proposed in #4863).

### What this changes in other ADRs and docs

- The language guide (`docs/contributor/practice-feedback-language.mdx`) changes with this ADR. The row for Hephaestus removes "AI mentor" from its Avoid column. A new row names "AI mentor" as the product category. The row for Mentor stays.
- "Developer" stays the term in practice feedback. "Person" becomes the term in the API and the schema elsewhere, because a domain other than software has no developers.
- [ADR 0056](0056-one-practice-model-for-items-and-scans.md) stays. ADR 0130 (proposed in #4863) adds a portable identity to each practice.
- [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) stays. Its Activity views show provider work, not practice feedback or an assessment of a person.

## Consequences

- Good: every later decision has one test. A feature that does not help a person apply a practice is out of scope.
- Good: a second domain adds a practice pack and an adapter. It does not change the schema.
- Good: claims about work come only from three kinds of record, so admission stays the one gate for evidence.
- Bad: "AI mentor" raises expectations. People can read it as a human mentor service. The product text must say that Hephaestus is software.
- Bad: "person" in the API and "developer" in practice feedback live side by side. This ends when the vocabulary stages finish.
- Bad: domain-neutral names cost more thought now, for a second domain that is not built yet.
- Bad: issues enter the new delivery flow in 1.0. Practices must say which item kinds they read.

## Confirmation

Not enforced yet. The implementation adds these checks:

- `gate:prose` fails when product text uses "mentor" alone for Hephaestus, or "AI mentor" for a person.
- A review gateway test fails when a review run calls a tool that writes outside its observations.
- A fixture test with an item kind of class `CONTEXT` fails when such an item produces an observation.
- An integration test fails when an issue of a workspace that did not include issues gets a review.
- An integration test fails when an issue gets a work place or a copy on the work.

## Revisit trigger

- A second domain needs an item kind that `WORK`, `ACCOUNT` and `CONTEXT` cannot describe.
- Support requests or research interviews show that people read "AI mentor" as a human service.
- The research scope narrows to pull requests. Then the private delivery of issue feedback moves to 1.x (decision 6).

## Sources

- Hattie and Timperley, [The Power of Feedback](https://doi.org/10.3102/003465430298487), Review of Educational Research, 2007. Feedback about the task and its process helps, and feedback about the self helps little. This makes the practice in the work the unit, not the person.
- Kluger and DeNisi, [The effects of feedback interventions on performance](https://doi.org/10.1037/0033-2909.119.2.254), Psychological Bulletin, 1996. Over one third of feedback interventions lowered performance, most when attention moved to the self. This is why Hephaestus shows no score, rank or level.
- Kram, Mentoring at Work, Scott, Foresman, 1985. A mentor serves career and psychosocial functions over time. Decision 8 takes only the functions that software can serve honestly.
