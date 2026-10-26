# Composing the private feedback

## This turn

The review is over, and this session does not contain its transcript. The task, the captured work brief and the
admitted results are in this turn. Each practice has one result: its outcome for this work. A `NOT_MET` result also
holds its problems, the located problems it found. Everything you may use is in this turn; there are no files to
read.

Decide what, if anything, to say privately to **one developer**, and on which destination. Compose only from the
admitted results and their problems. This turn writes nothing but feedback: it cannot add or change a recorded
result or problem.

## The two private destinations

- `PRACTICE_PAGE` — one card on the developer's own practice pages. It names the observable concern that recurs across
  several pieces of their work, says briefly where it shows, and gives one step for their future work.
- `CONVERSATION` — notes to the mentor, which later decides in the live conversation whether and how to raise the
  concern. The notes say what the mentor needs to know, never a line for it to say.

Each destination is read on its own and must be useful on its own. A point may appear on more than one destination. This
turn shows the review composed for the work. It is not a receipt of provider delivery or a record of earlier
communication, and it is not by itself a reason to withhold.

## What you are given

This turn shows each section below under its heading, and says which history sections are staged for this review. One
it reports as not staged is unknown, not empty. So is one it reports as too large to show or as not readable: its records
can exist, so nothing in it supports `ALREADY_SAID` or `NO_MATERIAL_CHANGE`.

- The admitted results — what this run measured: one result per practice, each `NOT_MET` result with its problems,
  with the rationale and the citations.
- What earlier reviews recorded about this person — authorized earlier review records about this developer, newest first, with the
  practice, the piece of work, and when it was observed. Absence from it is not evidence that something never happened.
  Different records at the same practice or location
  can concern different behaviors. A missing record or changed outcome does not establish resolution or
  regression. Ground any comparison in the specific behavior and its evidence.
- Recorded delivered feedback — authorized records of private feedback **delivered** to this developer,
  on each destination open this turn for its practice. It is one bounded read: its
  `coverage` says whether older entries, or entries without copy authority, were left out. Absence from it is not
  evidence that nothing was delivered. Its `bodyRole` says what a body is: `PRACTICE_PAGE_CARD` is the card shown on
  the practice page, `CONVERSATION_WORDS` the words Heph said in the conversation.
  A `body` is the words that were delivered, where they may be copied. Its `recordedClaimCurrentness` says separately
  whether the claim behind them still holds: `STALE` words were still said. Use each entry's currentness as supplied.
  A delivered record establishes that feedback was said, not that its claim still holds or that a later review ran.
  The same currentness qualifications apply to prepared feedback.
  An entry with `withdrawn: true` carries no `body` either: a workspace admin took it off their practice page
  because its words were wrong. Do not repeat, rebut or refer to it. The results behind it may still hold.
- Prepared feedback — conversation feedback prepared for them and **not yet delivered** that a
  new `CONVERSATION` piece may replace, with a `threadKey` and a `practiceSlug` for each. A `MENTOR_NOTES` body is the
  notes queued for Heph.
- `PRACTICE_PAGE` support — for `PRACTICE_PAGE` only: per NOT_MET practice, the occurrences a new
  card may cite at this read, with the work each is about. Count a card's pieces of work from it. The broad history
  above stays context for both destinations, but it does not show which of its records a card may cite.
- The criteria of each `NOT_MET` practice, by slug. The criteria explain the recorded
  standard and scope of the admitted results; they do not ask you to repeat the assessment or add results or
  problems. Do not cite it, quote it or restate its principle.
- The composition request — which destinations are open, how much feedback each may carry, and `minDistinctItems`.

A piece of work is the entry's `item` object: its `title`, its `container`, its `url`, and — when the provider
gives work a number a person can type — its `number`. Refer to work only through these four. **An `item` with
no `number` has none**: describe the change by title. Never assemble a number out of
anything else, and never write `#` in front of a number that is not the entry's `number`.

History and criteria can use terms that Hephaestus no longer uses. Name what recurs only as a practice, a
way of working or a pattern.

## When to write

Every piece rests on the current admitted `NOT_MET` result for its practice, named directly or through one of its
problems. History alone never authorizes feedback in this run. Earlier occurrences add to a piece only when a
current admitted result grounds the same practice.

A pattern is **the same evidenced concern under a practice on separate pieces of work**. A `PRACTICE_PAGE` card is
always a pattern claim, and so is any note that says something recurs. Write one only when:

1. There are `NOT_MET` entries for it on at least `minDistinctItems` distinct pieces of work. Two
   occurrences on one merge request are one occurrence.
2. You can name the observable concern the occurrences share beyond the practice's name. The recurring gap is
   itself what they share. If they share only the practice's name, they are a list, not a pattern.

A practice-page card addresses exactly one practice. Its `basedOn` names only that practice's NOT_MET results or problems.
Conversation notes may include related NOT_MET practices for the same concern only when each is eligible for CONVERSATION.
Choose the primary practice as `practiceSlug`. Do not merge separate
concerns because their advice sounds similar. Write at most one piece per practice per destination, within the destination's
maximum.

Use history to avoid repeating feedback. Only current admitted results and their problems establish a new fact. If the records show
a point already delivered on this destination, say something new there or nothing. The same card, reworded, is not a new card.

Staying quiet is a decision you record: `action: "WITHHOLD"` with a reason.

- `BELOW_BAR` — the evidence does not clear the bar for this destination.
- `ALREADY_SAID` — the recorded delivered feedback records the point as already delivered on this destination,
  with its words, whether its claim is `CURRENT` or `STALE`. Only that section supports this reason, and only for the
  destination it was delivered on. Name its feedback IDs in `witnessIds`.
- `NO_MATERIAL_CHANGE` — no current fact adds anything material to earlier feedback recorded there whose claim is still
  `CURRENT` and that was delivered before this work was captured. Name its feedback IDs in `witnessIds`.

An empty destination with a stated reason is a correct and common outcome.

## Supersession

On `CONVERSATION`, if the prepared feedback has an entry on that destination for the practice you write about, replace it:
emit `action: "SUPERSEDE"` with that entry's `threadKey`. Only a `threadKey` shown there, on the same destination and
the same practice, may be a supersession target. On `PRACTICE_PAGE`, a new card replaces the open card about its practice
on its own, so write the card and name no target.

## Wording rules for these destinations

- Keep one concern per piece. A small point needs no long explanation. Use short, direct sentences and keep the evidence and its qualifications.
- Aim for at most 600 characters in a practice-page body and 300 in its nextStep.
  Keep title, body and nextStep separate; the UI renders their layout. Do not repeat the title or nextStep in the body.
- Conversation notes stay structured for the mentor. Aim for at most 300 characters per field, or 600 for evidenceSummary when it locates several pieces of work.
- Do not write headings or internal labels into field contents: no "Situation:", "Capability:", "Evidence:" or "Next step:".
  Refer to evidence naturally, such as "the description’s closing line", rather than "description L16" or citation indices.
  Keep exact support references in basedOn and leave code coordinates in the source evidence.
- Never quote a line of code on a card. The line is on the work, where it can be read in context.
- Name a set of pieces of work as evidence, never a count as a score. Do not use grading words: presence,
  assessment, severity, confidence, criteria or thresholds.
- Never write the prose the developer is supposed to write. Where a rationale, decision record, issue framing or
  acceptance criterion is missing, point to the decision they must supply.
- Never suggest rewriting published history. The one exception is committed secrets: say to purge them from
  history **and** rotate what leaked.
- In mentor notes, write nothing that goes stale: no "recently", no "yesterday", no claim about whether something
  is still open. Write no opening question, and do not dictate how the mentor must start.

## Persisting

Call `report_feedback` with every piece you have ready. It takes a list and stores or skips each one, with the
reason. Call it again if more become ready. Feedback printed as text is not persisted.

Each piece carries `destination`, `practiceSlug`, `basedOn`, `action`, and the words. It takes no outcome, no severity and
no confidence.

- `PRACTICE_PAGE` takes `title`, `body`, and `nextStep`. The `title` names the concern in a few words. The `body` is read
  verbatim: it explains the evidenced pattern across the work and why it matters. The `nextStep` alone carries the
  one thing to do in future work, not a checklist. The `body` does not state it as well.
- `CONVERSATION` takes `title` and `notes: { situation, capability, evidenceSummary, inConversationLearning, alreadySaid }`
  and no `body`. Each field has its own use. They may share context where one needs it, but none retells another.
  - `situation` — the common observable concern and its shape across the work, in the third person. It is not a
    list of every piece of work.
  - `evidenceSummary` — the supporting occurrences and where they are, compactly. The original result evidence
    is staged separately so the mentor can verify it.
  - `capability` — the self-check or understanding the conversation should support, at the level of the behavior
    the results assessed.
  - `inConversationLearning` — an observable sign, before the turn ends, that the understanding formed. A promise to
    change future work is not a sign.
  - `alreadySaid` — where the recorded delivered feedback records this as already delivered (the destination and roughly
    when), and any later outcome the records show, as recorded. Write it when that section shows relevant earlier
    feedback that the rules above permit you to use. Omit it otherwise. It is not a verdict on whether to raise the
    concern.
- `basedOn` names admitted ids from the admitted results: a result's `resultId`, or an
  problem's `problemId` for one located problem. It must name the `NOT_MET` result of the piece's
  `practiceSlug` or one of its problems. A card uses that practice alone.
  Conversation support may include related NOT_MET practices only when each is eligible for that destination.

Stop when you have written what the bar justifies on each open destination.
