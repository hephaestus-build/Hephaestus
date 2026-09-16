# Composition and slots

## 1. `children` before a prop, a prop before context

A `title` prop that only ever receives a `<span>` should have been `children`. A "props drilled three
levels" problem is usually one `children` away from not existing — the owner renders the leaf once and
passes it down as JSX. Context is the last rung, for what genuinely has no owner in the tree.

react.dev's own escalation, verbatim: *"Start by passing props… Extract components and pass JSX as
children to them… If neither of these approaches works well for you, consider context."*
<https://react.dev/learn/passing-data-deeply-with-context>

Test for the last rung: name the common ancestor that renders both consumers. If one exists, context
is premature.

## 2. Make the common case configurable and the uncommon case composable

The ladder runs primitives → composed parts → a configured component; move up it only when a real
second caller disagrees. The counterweight is honest: every step toward composition moves the
accessibility work onto the consumer, and a kit of parts with no configured default is a kit where
every screen re-derives the same aria wiring slightly differently. If the product makes the decision
once, configure it.

Curtis names the target: *"Make the common configurable, make the uncommon composable"*
(<https://nathanacurtis.substack.com/p/configuration-collapse>). Atlassian reaches for the pre-built
component first, and its stated motive is **maintenance** — *"These pre-built solutions will be the
easiest to create and maintain"* (<https://atlassian.design/get-started/develop/composition/>), *not*
accessibility. The accessibility-cost argument is Capozzi's alone
(<https://maecapozzi.com/blog/composition-vs-configuration/>).

## 3. A compound API is right when the parts must agree, and it costs Controls

Reach for `<Thing.Part>` when several sibling declarations must stay in sync and nothing in the type
system connects them — a facet declared once as a control and again as an applied pill is the shape:
a facet with a control and no pill is invisible on a phone, and no type catches it.

**Do not** reach for it when the structure is derived from one record. A `<DeliveryTrace.Step>` API
would let a caller build a trace that contradicts the record it was built from. A deliberately
*closed* structure — `FeedbackResultsState`, a union in which a filtered empty state cannot exist
without the callback that clears the filter — is a design, and a slot API reopens what it closed.

**The local cost, which decides borderline cases.** A `subcomponents` entry buys extra tabs in the
ArgTypes doc block; the Controls panel is driven by the story's `args`, which belong to the main
`component`. So a part that needs its own controls stays a prop. A component with no story file pays
nothing, which is often what makes the call.

Prefer the cheap version first: a `const FACETS = [...]` descriptor array mapped twice removes the
duplication with no context and no subcomponents. The compound API is the follow-on refinement.

`/composition-patterns` has the generic pattern; do not restate it here.

## 4. Slots go through Base UI's `render=`, never `asChild`

This kit is Base UI (`@base-ui/react`), not Radix. `<Item render={<Link to="…" />}>`,
`<PopoverTrigger render={<Button …/>} />`. Anything copied from a Radix-based registry — including
most "shadcn Timeline" snippets — will not drop in; port the markup and rewire the slot.

base-ui.com documents whatever Base UI released last, which is not necessarily what is installed. A
`render={(props, state) => …}` copied from the live docs may not exist at the pinned version — read the
pin in `webapp/package.json`, or the installed `.d.ts`, before copying.

Zero `asChild` in the tree is the baseline, not an achievement — `react/forbid-component-props` in
`webapp/.oxlintrc.json` fails the build on the prop and says so in the message. Do not credit it in
review.

## 5. A slotted element keeps four obligations, and syntax is the easy one

The primitive hands you its behaviour and steps back; what it hands over is *unrendered*, so your
element must:

- **(a)** forward its `ref`;
- **(b)** spread **every** prop it receives onto the real DOM node — dropping `aria-*`, `role`, `id`
  or the handlers is how a trigger stops announcing its popup;
- **(c)** render exactly one root element, never a fragment (house policy — true, but on neither
  source page);
- **(d)** stay the element type the primitive expects. A `div` where a `button` was expected loses
  Enter/Space and the tab stop.

(a) and (b) are verbatim Base UI: *"The custom component must forward the `ref`, and spread all the
received props on its underlying DOM node."* (<https://base-ui.com/react/handbook/composition>).
(d) is Radix: *"If you do decide to change the underlying element type, it is your responsibility to
ensure it remains accessible and functional."*
(<https://www.radix-ui.com/primitives/docs/guides/composition>)

The story that proves it queries the slotted element by **role and accessible name** after the slot,
which fails if `aria-*` was dropped.

## 6. `className`, the remaining DOM props and `ref` reach the root — in the kit and in shared atoms

A stability contract, not a configuration knob: a screen that needs one margin here must not fork the
component. `React.ComponentProps<"div">` (or of the primitive being wrapped), minus the props you own,
is the type. Exempt from the two-call-site rule.

**Scope.** `webapp/src/components/ui/**`, plus the shared atoms in `webapp/src/components/common/`
and `webapp/src/components/practice-vocabulary/`. **Not** page shells — the rule is deliberately
narrow, and most of the tree sits outside it. Carbon, the source, says *"Where possible, the following
should be placed on the outermost, parent, or root element"* — *where possible*, and it documents the
case where `...rest` goes on an interior element instead. Carbon is also writing about a published kit
with external consumers, so its stability argument (*"Consumers rely on the placement of these within
the DOM"*) has no force for a component with one in-repo caller.
<https://github.com/carbon-design-system/carbon/blob/main/docs/style.md>

Copy `webapp/src/components/common/StatusBadge.tsx`.

## 7. Before you build anything, read `webapp/src/components/ui/`

The kit has no `timeline`, no `stepper` and no `data-table`, and none of the three is worth a
dependency — a vertical rail of steps is border and rounded spans
(`webapp/src/components/practice-vocabulary/DeliveryTrace.tsx`).

Some primitives are vendored *and* locally modified, and each such file says so in a header comment
so the next re-vendor restores the change instead of dropping it. Find them with
`grep -rl "Diverges from the shadcn registry" webapp/src/components/ui` before you work around a
defect — the divergence is fixed there once rather than hand-rolled at each call site. Editing
anything under `ui/` is gated by `webapp/AGENTS.md` § Ask first.
