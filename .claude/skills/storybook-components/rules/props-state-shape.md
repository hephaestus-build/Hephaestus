# Shape of the props

Each rule says where it came from. **House policy** means no source states the mechanism — argue
with it in review. A cited normative rule you may not.

## 1. An atom takes the domain object, not five scalars

`<StatusBadge def={…} />`, not `label` + `variant` + `icon`. A caller holding the pieces can combine
pieces that do not belong together — a "Delivered" label wearing the destructive variant — and no
type catches it. Where the answer depends on several fields at once, take the record:
`deliveryOutcome(feedback)` takes `Pick<ReviewFeedback, "channel"|"deliveryState"|"suppressionReason">`
because a state without its channel can produce a sentence that never happens. Use `Pick<WireType, …>`
so every read model with those fields fits as it is.

The quieter version is several scalars that are several spellings of one identity. If the caller
derives all of them from one record it already holds, take the record.

*"Many props is a signal that a component is solving too many problems or is too opinionated"* —
<https://github.com/Shopify/polaris/blob/main/polaris.shopify.com/content/contributing/components.mdx>

**Not this rule:** a prop count reduced by taking a bag named `options`. Only an object that is one
identity counts.

## 2. The component that owns the swapping region takes a discriminated union

`{ status: "loading" } | { status: "error"; error; onRetry } | { status: "empty"; filtered } |
{ status: "ready"; … }`. The container turns query flags into one value; the component renders one
branch. Parallel `isLoading` + `items` + `error` can express "loading with an error and three rows",
and a story then has to reproduce a combination production never sends.

**Scope, and it is narrow.** This is not "every component that renders during a fetch" — read that
way it condemns most of the tree. A **list shell** whose toolbar renders through every branch
legitimately takes the query result as three fields; forcing a page-level union would drag the filter
toolbar into every branch. Only the component that owns the region that *swaps* takes the union.

Exemplar: `ReviewSectionState` in `webapp/src/components/admin/practice-reviews/review-states.ts`
— four branches, and `onRetry` lives *inside* the error branch so no caller can hand you a retry with
nothing to retry. Anti-exemplar: `FeedbackResultsState` in the same directory's `FeedbackResults.tsx`,
a `loading | empty | ready` union with no error branch — this rule broken while appearing to follow it.

<https://react.dev/learn/choosing-the-state-structure> — *"Since `isSending` and `isSent` should never
be true at the same time, it is better to replace them with one `status` state variable that may take
one of three valid states"*

## 3. Enum over boolean; separate components over flag arguments

Two booleans are four states, of which you render two. `variant="compact" | "full"` reads at the call
site; `compact` + `showHeader` does not, and grows a third boolean next quarter. When the flag makes
the component do a *different job*, ship two components — a caller that must pass a literal `true` to
pick the behaviour is asking for a different function.

Fowler: *"My general reaction to flag arguments is to avoid them. Rather than use a flag argument, I
prefer to define separate methods."* The carve-out is his **"Boolean Setting Method"** paragraph, not
"Deriving the flag" (which argues the opposite — that the callee should derive it and take no flag):
*"If you pulling data from a boolean source, such as a UI control or data source, I'd rather have
`setSwitch(aValue)`"*. So: a flag a caller **derives** from data is fine; a flag a caller **types as a
literal** is not. <https://martinfowler.com/bliki/FlagArgument.html>

Curtis's *"Visibility props … declined severely in favor of controlling element presence and visibility
via slot composition"* is about **Figma** BOOLEAN props and slot migration, not React props — it is
supporting evidence for the direction, not a rule about our types.
<https://nathanacurtis.substack.com/p/configuration-collapse>

## 4. A value prop is never `on`-prefixed

`on*` is a callback everywhere in this kit. `onDrafts: boolean` sitting next to
`onDraftsChange: (v: boolean) => void` reads as two handlers at the call site. Name the value for what
it is: `includeDrafts` / `onIncludeDraftsChange`.

## 5. The accessible name is part of the props type (house policy)

A component with no visible label must **require** `aria-label` or `aria-labelledby` in its props —
not accept it, require it — so a caller cannot ship an unnamed control. Same for a name that has to
disambiguate two instances on one screen.

The obligation is normative: WCAG 2.2 SC 4.1.2 (Name, Role, Value). Putting it in the TypeScript type
is ours, and no source states that mechanism — argue with the mechanism in review, not with the SC.

## 6. Controlled or uncontrolled is a decision you state

Default to **controlled** for anything whose value the URL, a form, or a server mutation also holds —
which is nearly everything we own. Uncontrolled with a `defaultValue` only for state that never leaves
the component. Never both: a `value` that is ignored unless `onChange` is present is a bug waiting for
its story. Say which it is in the doc comment.
<https://react.dev/learn/sharing-state-between-components>

**Naming.** `value`/`onValueChange` **only** when the component is a thin wrapper over a Base UI
primitive that already uses that pair. Everything we write uses `value`/`onChange`. A blanket
`onValueChange` mandate has been proposed and rejected: nothing in the tree followed it, and a rule
nothing follows is not a rule.

## 7. A change callback has two sanctioned shapes, and you pick by who owns the value

- **Whole value** — `onChange: (next: Binding) => void`, for an editor of one object.
- **Patch** — `onPatch: (patch: Partial<Search>) => void`, for URL-backed search state, where the
  screen merges into the router's search params.

Not a third spelling. `onSearchChange` taking a patch is the patch shape wearing the whole-value name.

## 8. The filter component never touches `page`

Pagination reset belongs to the screen that owns the URL, not to the toolbar that emits a patch. Where
a directory carries both contracts under one prop name, the screen wins.

## 9. No prop the component can derive from a prop it already has

If the component takes `search`, it does not also take `hasFilter` — it calls `hasFilter(search)`.
Making the caller compute it invites two callers to compute it differently. Same argument as
react.dev's "Avoid redundant state", one level out.

## 10. A prop needs two real call sites, or it dies (house policy)

No design system publishes a number; two is ours. One caller usually means the value belongs inline at
that caller, where it can be read. A `variant`, a `display`, a `size` earns its place when two screens
genuinely disagree — otherwise it is a fork you pay to keep open in every story, snapshot and type.
The same test applies to a whole component: delete it and inline it.

**Three carve-outs.**
1. `className` / the remaining DOM props / `ref` are a stability contract, not a knob
   (`composition-and-slots.md`), and are exempt.
2. One caller justifies a prop when that caller *derives* the value from data rather than typing a
   literal — deleting it just moves the branch somewhere worse.
3. **Registry code is out of scope.** `webapp/src/components/ui/` is re-vendored from upstream, so this
   rule cannot fire there however many call sites a prop has. Curtis, cited for the direction,
   explicitly *retains* `state`/`appearance`/`size` as *"the component's essential, top-level API"* —
   a two-call-site test applied to `size` contradicts its own source.

<https://kentcdodds.com/blog/inversion-of-control>
