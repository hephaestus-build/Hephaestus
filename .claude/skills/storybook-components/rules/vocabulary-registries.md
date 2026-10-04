# Vocabulary registries and badges

## 1. One canonical registry per enum, and no component defines its own copy

The unit is the **enum**, not the file: `webapp/src/components/practice-vocabulary/review-status-defs.ts`
correctly holds two registries for two different enums (`REVIEW_STATUS_DEFS`, `SUMMARY_POST_DEFS`). Each is
`{ label, icon, badgeVariant, description }` per value, as a **total `Record` over the generated wire
union**, so a value the server adds fails `typecheck:webapp` rather than rendering blank. Badges, facet
options, select items and empty states all read that one entry.

A second copy of an enum's labels is how a filter dropdown ends up as grey text beside a table of
coloured tags. The copy is rarely a second `Record` — it is usually a `const labels = {…}` inside a
function body, which is a switch wearing a registry's clothes. Grep for the enum's values, not for the
registry's name.

**`icon` is required, not optional.** WCAG 2.2 SC 1.4.1:

*"Color is not used as the only visual means
of conveying information, indicating an action, prompting a response, or distinguishing a visual
element."*

Its Understanding page adds this information for content that relies on differentiating a color:

*"an additional visual indicator will be required regardless of the contrast ratio between those
colors"*

Thus, contrast is not an exception.
<https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html>

**No two entries in one enum share an icon**, because two values that look identical are one value.
The same reasoning applies across adjacent badges, although the rule covers one enum.
A place chip and an outcome chip with the same icon and near-identical words render one badge twice.

## 2. A second grammatical form is a registry field, never a local copy

If two call sites need the same words in a different form — plural, lower-cased, possessive — the
registry gets a second field or an accessor. It never gets a local map. This rule-1 violation hides easily.
Three copies of one vocabulary exist, and the third is lower-cased inline.
Adding a fourth enum value then updates nine call sites and silently misses one.

Lane-specific wording is the sanctioned exception, and it is done with an **override table plus a
stated invariant** — `webapp/src/components/practice-vocabulary/delivery-outcome-defs.ts` requires this invariant:

*"Each label must
begin with the label of the state it refines"*

One function taking the whole record resolves it.
That is an extension of the registry, not a copy of it.

## 3. Badge the exception, not the norm (house policy)

A signal-to-noise heuristic, not received practice: a badge on every row colours the baseline and hides
the one row that is different. `ObservationOriginBadge` renders nothing for `LIVE`.
`ClaimCurrentnessBadge` renders nothing for `CURRENT`. A tally of five delivery outcomes is a sentence,
not five badges.

**Rendering nothing must not make the fact unavailable.** If the norm is something a reader needs, it
stays in the row's accessible text — an `sr-only` span, a `title`, or the surrounding sentence.
"Silent because it is ordinary" is a visual decision. It is not permission to drop the information.

Whatever does render still takes its words from the registry. A count reading "not delivered" beside a
badge reading "Withheld" is rule 1 broken by the back door.
