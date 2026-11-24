# ADR 0150: An outcome renders from rows, as an item card or a citation token

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0131](0131-a-person-has-one-conversation-in-each-workspace-and-its-answers-stream.md) (the conversation), [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) (admitted and sealed observations), [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (private practice feedback has one reader)

**Relates to:** [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (typed commands on the card), [ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md) (the card controls for a withheld copy)

## Context

A review result is a set of admitted rows: observations, problems, their severity and the delivery state of each copy.
On main, the conversation tells a person about a result in the model's own words, and `show_feedback` marks the feedback as delivered ([ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decision 14).
A model that writes an outcome as free text can contradict the admitted rows.
When the rows change later, for example after a correction or a withheld copy, nothing corrects the words.

The result also needs a home that exists before any model runs, so that a person sees it during an outage.

The real constraint is that no text states a result that the rows do not hold.

## Decision drivers

- A claim about a result always matches the admitted rows, also when the rows change later.
- A result shows before any model runs.
- No model decides or states a delivery state.
- One result has one home, and every place that shows it shows the same thing.

## Considered options

1. **Prose for results.** The model writes outcomes and delivery states in its own words. The words can contradict the rows, and nothing corrects them when the rows change.
2. **Keep `show_feedback` of ADR 0082.** It marks feedback as delivered in a turn. The words around it are still prose, and nothing renders the current state of the result.
3. **A card that a model writes.** Each update of the card needs a turn, and its text can contradict the rows.
4. **Item cards and citation tokens that render from rows (chosen).**

## Decision

Option 4.

1. **An item card is a projection of rows about one item.** It shows the problems with their labels and cited lines. It also shows the delivery state of each copy, the task status and "Could not check". No model writes it. An update needs no turn.
2. **The card exists from the first queued review of the item,** except for an On request result. ADR 0137 (proposed in #4863) decides what shows of that result. "Reviewing #42" is a state of the card. Its lifecycle is in progress, open and closed. Its revision only grows.
3. **The same card shows in four places.** These are each message that cites it, the Activity section, the practice page and the extension.
4. **Generated prose never carries a check verdict.** It refers to a check only through a citation token. The renderer shows the admitted verdict from rows beside it.
   - Render check verdicts only from admitted citation tokens and server-owned text.
   - Withhold a unit if any check it names lacks its own admitted token.
   - Also withhold it if the server cannot establish that its generated prose is verdict-free.
   - A unit names a check when it holds the check's identifier or title.
   - The known checks are an exact set: the practices of the workspace. So the rule is deterministic.
   - A rejected unit is withheld, never edited. The model never writes an outcome or a delivery state as free text.
5. **A message says "posted" only if the copy was delivered when the message was stored.** Its token renders the current delivery state after that.
6. **Activity gets a proposed section that lists the owner's item cards** in three groups: In progress, Open feedback and Closed. Only the owner reads it. Main's Needs you stays as it is.
7. **Card controls.** Mute card notifications stops messages and notifications about the card. Typed commands follow [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md).
   - Mark card done closes a card, and its manual reverse opens it again.
   - A card also opens again when a review of a newer version finds a new or changed problem. Problem identity decides what is new or changed.
   - A duplicate seal never opens it again.

### What this changes in other ADRs

- [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decision 14 goes with ADR 0082 ([ADR 0131](0131-a-person-has-one-conversation-in-each-workspace-and-its-answers-stream.md)). A citation token takes the role of `show_feedback`. Feedback in conversation counts as delivered when a committed entry cites it.
- [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) rule 18 stays. The item card and the Activity section have the same one reader.

## Consequences

- Good: a result shows on its card before any model runs, and it stays correct when the rows change.
- Good: the app, the extension, the practice page and a message show one card, so they cannot disagree.
- Bad: the server holds each visible unit until its check passes. An answer shows a little later than raw tokens would.
- Bad: a card is less personal than a sentence. The message around it carries the tone.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- CardWithoutModelTest fails when an item card update needs a turn.
- KnownCheckWithoutTokenTest fails if the server exposes or edits a rejected unit.
  It covers a verdict without an exact check name and a verdict beside a token.
  It also covers a wrong token and a valid server-rendered citation.
- PostedClaimTest fails when a message says "posted" for a copy that was not delivered at its commit.

## Revisit trigger

- The known-check rule withholds more than 1 % of visible units.
- People ask what a card means more often than they open it.

## Sources

- Ji et al., [Survey of Hallucination in Natural Language Generation](https://doi.org/10.1145/3571730), ACM Computing Surveys 55(12), 2023. Generation "is prone to hallucinate unintended text". So a result is never left to the model's words, and decision 4 renders it from rows.
