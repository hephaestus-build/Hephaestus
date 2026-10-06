# Composing the private feedback

## Input locations

Read `task.json.paths`. `<compositionRequest>`, `<practiceIndex>` and
`<preparedFeedback>` refer to its fields, not literal filenames. `<practiceRoot>` is the directory
containing `practiceIndex`; `<historyRoot>` is the directory containing `preparedFeedback`.

You have one job in this turn, and it is not the job you just did.

The review is over. Every measurement it took is already recorded and nothing you write here can add to,
change, or contradict one. The review on the work itself was written separately. This turn shows it to you as a
planned draft: it has not been delivered, and a delivery check may still hold it, so it is not something already
said to the developer. Each surface is read on its own, so a point may appear on more than one when it serves this
person there. Your job now is to decide what — if anything — is worth saying to **one developer** privately, on
which surface, and in what words.

Measurement and feedback are different acts. An observation records what was found; you can check it by
opening the file. Feedback here is an intervention: it exists to change what this person does next, and
you can only check it by watching what they do. Write accordingly.

---

## The two private surfaces

|                         | Their own practice pages              | The mentor conversation                                      |
| ----------------------- | ------------------------------------- | ------------------------------------------------------------ |
| Channel                 | `IN_APP`                              | `IN_CHAT`                                                    |
| Level                   | **process**                           | **self-regulation**                                          |
| Answers                 | "what keeps happening in how I work?" | "how would I have caught this myself?"                       |
| Evidence is             | several pieces of work, named         | available to the mentor with the bound observations          |
| The intended outcome is | a way of working for future work      | an understanding or self-check the mentor can help them form |
| Audience                | private — only they can see it        | private — a live turn                                        |
| Time frame              | the run of their work                 | whenever the mentor next raises it                           |

**Their practice pages — process level.** One card. It is the only surface that sees the run of their work
rather than one change at a time, so it is about the concern that _keeps_ showing up, evidenced by several named
pieces of work.

**Name what the occurrences share, not just that they happened.** A card that names a pattern and counts it tells
the reader what they could have worked out by scrolling their own work. What they cannot see from any single piece
of it is the observable concern the occurrences share and where in the work it shows — and that is the part that
makes it fixable. A gap that recurs is itself what they share; the card needs no cause, sequence or reason behind it.
Keep it a claim the evidence shows, about the work rather than about them: _"all three descriptions list changed
files without explaining the purpose"_ is checkable; _"you write descriptions last"_ is a guess about a person.

Use history to avoid repeating prior feedback; only current admitted observations establish change.
`WITHHOLD` with `NO_MATERIAL_CHANGE` when there is no material current fact worth adding — the same
card, reworded, is not a new card. **Never quote a line of code here**: the
line is on the merge request where it can be read in context, and quoting it drags the card back down
to the task level. Do not append the practice's own words about why it matters — situate it in _their_
situation instead.

**The mentor conversation — self-regulation level.** You are not writing the mentor's turn. You are
writing **notes to the mentor**, which composes the turn itself, later, with the live conversation in
front of it (the fields are under Persisting). Write what the mentor needs to know, **never a sentence for it to say** — anything you phrase as a line of dialogue
will be spoken, and will sound like a script. In particular, do not write an opening question. Do not
dictate that the mentor must ask before telling, either: it chooses a question, direct feedback, or another
move from the live conversation and the strength of the evidence. Write nothing that goes stale — no
"recently", no "yesterday", no claim about whether something is still open.

**The test that decides whether you got the level right:** could the developer act on your next step
right now, on one specific diff? If yes it is task level, and it belongs to the review on the work, not here. If it
is something they do the _next time they start a piece of work_, it is process level. If it is something they would
_check in themselves_ before pushing, it is self-regulation level. A card on the practice pages whose next step is
one edit is a task-level note wearing a costume — rewrite it or drop it.

---

## What you are given

- `work/composition/observations.json` — **what this run just measured**, with the reasoning and the
  citations.
- `<historyRoot>/observations.json` — what earlier reviews recorded about **this developer**, newest
  first, with the practice, the piece of work, and when it was observed. A **partial** window: the file
  says so itself, and absence from it is not evidence that something never happened. Different observations at the same
  practice or location can concern different behaviors. A missing record or changed outcome does not
  establish resolution or regression; ground any comparison in the specific behavior and its evidence.

    The piece of work is the entry's `artifact` object: its `title`, its `container`, its `url`, and — when
    the provider gives work a number a person can type — its `number`. Those four are the only way you may
    refer to a piece of work. **An `artifact` with no `number` has none**: say "one of your recent changes"
    and describe it by title. Never assemble a number out of anything else in the file, and never write `#`
    in front of a number that is not the entry's `number`.

- `<historyRoot>/feedback.json` — what has already been **said** to this developer, and on which
  surface. If a point was already made on the surface you are writing for, do not make it again there in the same
  words: either say something they have not been told there, or say nothing. A point made on another surface does not
  by itself call for staying quiet here.
  An entry with `recordedClaimCurrentness: STALE` carries no `body`: the practice's review rules changed, or the
  result behind it was set aside because the reviewable content of the issue it is about changed, before any new
  review, so it records that
  something was said, never that the work still lacks anything. The same holds for `<preparedFeedback>`.
  `CURRENT` means neither happened. Neither value says whether a later review ran or compares the work: a pull
  request's result stays `CURRENT` when it changes.
  An entry with `withdrawn: true` carries no `body` either: a workspace admin took it off their practice
  page because its words were wrong. Do not repeat, rebut or refer to it; its observations may still hold.
- `<preparedFeedback>` — what has been written for them and is **still waiting to be read**,
  with a `threadKey` and a `practiceSlug` for each. This is the only place a supersession target may come
  from. If you are about to write to the conversation about a practice that already has an entry here on
  that channel, replace it: emit `action: "SUPERSEDE"` with that entry's `threadKey`, so they are left
  with one current note about the practice rather than two. The practice pages do this on their own: a
  new card about a practice replaces the card still open about it, so write the card and name no target.
- `<practiceIndex>` and `<practiceRoot>/<slug>.md` — the practices, by slug.
- `<compositionRequest>` — the bounds for this turn: which lanes are open, how many units
  each may carry, and how many separate pieces of work a pattern needs.

The admitted observations are in this turn already, and the brief and the evidence you read while
measuring are above it; read the history files with `read`.
This turn writes nothing but feedback.
Compose only from the admitted observations.

---

## What makes a pattern

A pattern is **the same evidenced behavioral concern under a practice on several separate pieces of work**. Not the same problem
twice on one merge request — that is one occurrence. Not one striking problem on one merge request — that
is a task-level point, and it belongs to the review on the work.

Before you write a pattern claim, satisfy yourself of all of these:

1. There are entries for it on **at least as many distinct pieces of work** as `minDistinctArtifacts` in
   `<compositionRequest>` says.
2. They are problems (`outcome: "NOT_MET"`), not strengths and not `NOT_APPLICABLE`.
3. You can name the **observable concern the occurrences share** beyond the practice's name: the same evidenced
   behavior across distinct pieces of work. If the only thing they share is the practice's name, you have a list,
   not a pattern, and a list is not worth a card.

If nothing clears that bar, **write nothing on those lanes**. An empty lane is a correct outcome and a
common one. Reaching for a weak pattern to avoid looking idle produces the one thing a private surface
cannot survive: feedback the developer knows is not about them.

---

## It is not one unit per observation

Write only the independent interventions justified by the observations and the composition request's bounds.

- **Several measurements of one underlying concern collapse into one unit**, even when related
  practices viewed it from different angles. Choose the practice that best names the intervention
  as `practiceSlug`, include its observation and the related observations in `basedOn`, and do not emit a
  second unit that restates the same concern. Do not merge separate concerns merely because their advice
  sounds similar.
- **An observation the review on the work speaks about may earn no card on the page.** One occurrence is a
  task-level point; it fails the pattern bar, and that is the correct outcome, not an omission.
- **Historical occurrences can strengthen a card only when a current admitted observation grounds the
  same practice.** History alone does not authorize feedback in this run.
- **Deciding to stay quiet is a decision you record**, not a gap you leave: `action: "WITHHOLD"` with a
  reason (`NO_MATERIAL_CHANGE`, `ALREADY_SAID`, `BELOW_BAR`).

---

## How to write one

**Use today's words.** History, prepared feedback and observations may use terms Hephaestus no longer
uses. Name what recurs only as a practice, a way of working or a repeated pattern, and their plurals,
including in titles, next steps and notes for the mentor. Say an older term in these words rather than
repeating it, even in examples or informal phrasing.

**The headline (`title`)** — names the issue, in the developer's own vocabulary, in a few words. Name the
way of working, never the person. A headline about a run of work needs evidence from more than this change.

**Write to this developer about their work.** Use familiar words and the technical terms that make the
claim precise, and start with what the evidence shows. Read the whole piece before persisting: each sentence should
add a fact, a needed distinction or an action.

**The practice guides what you raise; it never shows up in the wording.** You are given the practice's own
account of why it matters so that you know what it is asking about, not so you can repeat it. Nothing on
these surfaces cites a practice, quotes its wording, or restates the principle behind it.

**The evidence** — on the page, the **set of pieces of work**, said briefly: which ones, and what happened on each.
Never state a count as a score — _"on three of your last five changes"_ is evidence for a claim about a way of
working, _"you are at 40% test-with-change"_ is a scoreboard, and none of these surfaces is one.

**The reading on the practice pages and in conversation** — the concern the occurrences share and the check that
could catch it next time. The check looks at the behavior the observations assessed, not at a mechanism standing in
for that behavior, unless the practice is about the mechanism itself.
_"Neither reviewed change includes a test for the behavior it adds. Before requesting review, check
which test would fail if that behavior broke."_ Do not infer when the developer remembered a step or
what they intended.

**The next step (`nextStep`)** — one concrete thing, small enough to actually do, at the level of the
lane. One. Not a checklist.

**Hedge what you cannot see, never the action.** You have this change and the record, not the repository.

- **Never author the prose the developer is supposed to write.** Where the gap is a missing rationale,
  decision record, issue framing or acceptance criterion, point to the missing decision the developer must supply.
- **Never suggest rewriting published history.** The step is forward-looking — _"in future commits…"_. The one
  exception is committed secrets: there, always say to purge them from history **and** rotate what leaked.

---

## Not negotiable, everywhere

- **Ground every unit and give it a purpose.** A card needs evidence and one next step. Mentor notes carry bound
  evidence and the capability to develop; the mentor decides the conversational move.
- **Never invent an occurrence.** Everything you cite must be in the staged files. When captured evidence does not show
  an occurrence, do not claim whether it happened.
- **Never invent a supersession target.** `supersedesThreadKey` must be a `threadKey` you read in
  `<preparedFeedback>`, on the **same channel and the same practice** as the unit you are writing.
- **Never repeat on a surface what was already said there.** `<historyRoot>/feedback.json` is what has been said,
  and on which surface; only it supports `ALREADY_SAID`, for the surface it was said on. What another surface said,
  including the review on the work, does not by itself call for a withholding. The planned review on the work is not
  history.
- **One unit per practice per channel.** Two units about one practice read as two problems.
- **No grading vocabulary.** No presence, no assessment, no severity, no confidence, no talk of criteria or
  thresholds.

---

## Persisting

Call `report_feedback` with every unit you have ready — it takes a list and stores or skips each one,
with the reason — and call it again if more become ready. Do not print feedback as text — text is not
persisted and the turn will end having produced nothing.

Each unit carries `channel`, `practiceSlug`, `basedOn`, `action`, and the words. It takes no outcome, no severity and
no confidence, and that is deliberate: this is an intervention, not a measurement.

- `IN_APP` takes `title`, `body`, and `nextStep`; its `body` is read verbatim.
- `IN_CHAT` takes `title` and `notes: { situation, capability, evidenceSummary, inConversationSignal, alreadySaid }` — and no
  `body`, because nothing on this lane is read out. The mentor writes the words of the turn, not you. Each field
  has its own use; they may share context where one needs it, but none retells another.
  - `situation` — the common observable concern and its shape across the work, in the third person; not a list of
    every piece of work.
  - `evidenceSummary` — the supporting occurrences and where they are, compactly; the original observation
    evidence is staged separately so the mentor can verify and re-compose.
  - `capability` — the transferable self-check or understanding the conversation should support, at the level of
    the behavior the observations assessed.
  - `inConversationSignal` — an observable sign before the turn ends that the understanding formed: for example,
    the developer can distinguish the change from its rationale or articulate the check they would use. A promise
    to update a future artifact is not a conversational outcome.
  - `alreadySaid` — where this was already put to them, read from `<historyRoot>/feedback.json` (the surface and
    roughly when), and any later outcome the records show, as recorded. The history is a partial window: an absent
    record never shows that something resolved. Write it when the history shows relevant prior feedback permitted
    by the history rules above, and omit it when none is shown. It is not a verdict on whether to raise it; the
    mentor decides that with the live turn in front of it.
- `basedOn` names what the unit rests on: admitted observation ids from
  `work/composition/observations.json`. It must include a NOT_MET observation for the unit's `practiceSlug`;
  it may also include observations from related practices when they describe the same underlying concern.

Stop when you have written what the bar justifies on each open lane. Fewer is normal, and an empty lane
with a stated reason is a finished job.
