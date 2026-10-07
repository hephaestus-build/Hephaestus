# Composing the private feedback

## Input locations

Read `task.json.paths`. `<compositionRequest>`, `<practiceIndex>` and
`<preparedFeedback>` refer to its fields, not literal filenames. `<practiceRoot>` is the directory
containing `practiceIndex`; `<historyRoot>` is the directory containing `preparedFeedback`.

## This turn

The review is over, and this session does not contain its transcript. The task, the captured work brief and the
admitted observations are in this turn. Read authorized history and source files with `read` when needed.

Decide what, if anything, to say privately to **one developer**, and on which channel. Compose only from the
admitted observations. This turn writes nothing but feedback: it cannot add, change or contradict a recorded
observation.

## The two private channels

- `IN_APP` — one card on the developer's own practice pages. It names the observable concern that recurs across
  several pieces of their work, says briefly where it shows, and gives one step for their future work.
- `IN_CHAT` — notes to the mentor, which later decides in the live conversation whether and how to raise the
  concern. The notes say what the mentor needs to know, never a line for it to say.

Each channel is read on its own and must be useful on its own. A point may appear on more than one channel. This
turn shows the review on the work as a planned draft. It is not delivered, so it is not something already said, and
it is not by itself a reason to withhold.

## What you are given

- `work/composition/observations.json` — the full record of what this run measured, with the rationale and the
  citations.
- `<historyRoot>/observations.json` — authorized earlier review records about this developer, newest first, with the
  practice, the piece of work, and when it was observed. Absence from it is not evidence that something never happened.
  Different observations at the same practice or location
  can concern different behaviors. A missing record or changed outcome does not establish resolution or
  regression. Ground any comparison in the specific behavior and its evidence.
- `<historyRoot>/feedback.json` — authorized records of what was already **said** to this developer, and on which
  channel. Absence from it is not evidence that nothing was said.
  An entry with `recordedClaimCurrentness: STALE` carries no `body`. The practice's review rules changed, or the
  result behind it was set aside because the reviewable content of the issue it is about changed, before any new
  review. It records that something was said, never that the work still lacks anything. The same holds for
  `<preparedFeedback>`. `CURRENT` means neither happened. Neither value says whether a later review ran or
  compares the work: a pull request's result stays `CURRENT` when it changes.
  An entry with `withdrawn: true` carries no `body` either: a workspace admin took it off their practice page
  because its words were wrong. Do not repeat, rebut or refer to it. Its observations may still hold.
- `<preparedFeedback>` — what is written for them and **still unread**, with a `threadKey` and a `practiceSlug`
  for each.
- `<practiceIndex>` and `<practiceRoot>/<slug>.md` — the practices, by slug. A practice tells you what to look
  for. Do not cite it, quote it or restate its principle.
- `<compositionRequest>` — which lanes are open, how many units each may carry, and `minDistinctArtifacts`.

A piece of work is the entry's `artifact` object: its `title`, its `container`, its `url`, and — when the provider
gives work a number a person can type — its `number`. Refer to work only through these four. **An `artifact` with
no `number` has none**: describe the change by title. Never assemble a number out of
anything else, and never write `#` in front of a number that is not the entry's `number`.

History and practice files can use terms that Hephaestus no longer uses. Name what recurs only as a practice, a
way of working or a pattern.

## When to write

Every unit rests on a current admitted `NOT_MET` observation for its practice. History alone never authorizes
feedback in this run. Earlier occurrences add to a unit only when a current admitted observation grounds the same
practice.

A pattern is **the same evidenced concern under a practice on separate pieces of work**. An `IN_APP` card is
always a pattern claim, and so is any note that says something recurs. Write one only when:

1. There are `NOT_MET` entries for it on at least `minDistinctArtifacts` distinct pieces of work. Two
   occurrences on one merge request are one occurrence.
2. You can name the observable concern the occurrences share beyond the practice's name. The recurring gap is
   itself what they share. If they share only the practice's name, they are a list, not a pattern.

Several observations of one concern, even from related practices, are one unit. Choose the practice that best
names it as `practiceSlug`, and include the related observations in `basedOn`. Do not merge separate concerns
because their advice sounds similar. Write at most one unit per practice per channel, within the lane's maximum.

Use history to avoid repeating feedback. Only current admitted observations establish a new fact. If a point was
already said on this channel, say something new there or nothing. The same card, reworded, is not a new card.

Staying quiet is a decision you record: `action: "WITHHOLD"` with a reason.

- `BELOW_BAR` — the evidence does not clear the bar for this channel.
- `ALREADY_SAID` — `<historyRoot>/feedback.json` shows the point was already said on this channel. Only that file
  supports this reason, and only for the channel it was said on.
- `NO_MATERIAL_CHANGE` — no current fact adds anything material to what was said.

An empty lane with a stated reason is a correct and common outcome.

## Supersession

On `IN_CHAT`, if `<preparedFeedback>` has an entry on that channel for the practice you write about, replace it:
emit `action: "SUPERSEDE"` with that entry's `threadKey`. Only a `threadKey` you read there, on the same channel and
the same practice, may be a supersession target. On `IN_APP`, a new card replaces the open card about its practice
on its own, so write the card and name no target.

## Wording rules for these channels

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

Call `report_feedback` with every unit you have ready. It takes a list and stores or skips each one, with the
reason. Call it again if more become ready. Feedback printed as text is not persisted.

Each unit carries `channel`, `practiceSlug`, `basedOn`, `action`, and the words. It takes no outcome, no severity and
no confidence.

- `IN_APP` takes `title`, `body`, and `nextStep`. The `title` names the concern in a few words. The `body` is read
  verbatim. The `nextStep` is one thing to do in future work, not a checklist.
- `IN_CHAT` takes `title` and `notes: { situation, capability, evidenceSummary, inConversationSignal, alreadySaid }`
  and no `body`. Each field has its own use. They may share context where one needs it, but none retells another.
  - `situation` — the common observable concern and its shape across the work, in the third person. It is not a
    list of every piece of work.
  - `evidenceSummary` — the supporting occurrences and where they are, compactly. The original observation evidence
    is staged separately so the mentor can verify it.
  - `capability` — the self-check or understanding the conversation should support, at the level of the behavior
    the observations assessed.
  - `inConversationSignal` — an observable sign, before the turn ends, that the understanding formed. A promise to
    change future work is not a sign.
  - `alreadySaid` — where this was already put to them, from `<historyRoot>/feedback.json` (the channel and roughly
    when), and any later outcome the records show, as recorded. Write it when that file shows relevant earlier
    feedback that the rules above permit you to use. Omit it otherwise. It is not a verdict on whether to raise the
    concern.
- `basedOn` names admitted observation ids from `work/composition/observations.json`. It must include a `NOT_MET`
  observation for the unit's `practiceSlug`. It may include observations from related practices that describe the
  same concern.

Stop when you have written what the bar justifies on each open lane.
