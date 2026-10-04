# Composition and slots

## 1. `children` before a prop, a prop before context

A `title` prop that only ever receives a `<span>` should have been `children`. A "props drilled three
levels" problem is usually one `children` away from not existing — the owner renders the leaf once and
passes it down as JSX. Context is the last rung, for what genuinely has no owner in the tree.

react.dev's own escalation, verbatim:

*"Start by passing props… Extract components and pass JSX as
children to them… If neither of these approaches works well for you, consider context."*

<https://react.dev/learn/passing-data-deeply-with-context>

Test for the last rung: name the common ancestor that renders both consumers. If one exists, context
is premature.

## 2. Make the common case configurable and the uncommon case composable

The ladder runs primitives → composed parts → a configured component. Move up it only when a real
second caller disagrees. Every step toward composition moves accessibility work onto the consumer.
Without a configured default, every screen re-derives the same aria wiring slightly differently. If the product makes the decision
once, configure it.

Curtis names the target:

*"Make the common configurable, make the uncommon composable"*

<https://nathanacurtis.substack.com/p/configuration-collapse>

Atlassian uses the pre-built component first.
Its stated motive is **maintenance**:

*"These pre-built solutions will be the
easiest to create and maintain"*

<https://atlassian.design/get-started/develop/composition/>

Its motive is *not* accessibility.
The accessibility-cost argument is Capozzi's alone
(<https://maecapozzi.com/blog/composition-vs-configuration/>).

## 3. A compound API is right when the parts must agree, and it costs Controls

Use `<Thing.Part>` when sibling declarations must stay in sync but the type system does not connect them.
For example, a facet appears as both a control and an applied pill.
A facet with a control but no pill is invisible on a phone.
No type catches that error.

**Do not** reach for it when the structure is derived from one record. A `<DeliveryTrace.Step>` API
would let a caller build a trace that contradicts the record it was built from. A deliberately *closed* structure is a design.
For example, `FeedbackResultsState` prevents a filtered empty state without the callback that clears the filter.
A slot API reopens what that union closed.

**The local cost, which decides borderline cases.** A `subcomponents` entry buys extra tabs in the
ArgTypes doc block. The Controls panel is driven by the story's `args`, which belong to the main
`component`. So a part that needs its own controls stays a prop. A component with no story file pays
nothing, which is often what makes the call.

Prefer the cheap version first: a `const FACETS = [...]` descriptor array mapped twice removes the
duplication with no context and no subcomponents. The compound API is the follow-on refinement.

`/composition-patterns` has the generic pattern. Do not restate it here.

## 4. Slots go through Base UI's `render=`, never `asChild`

This kit is Base UI (`@base-ui/react`), not Radix. `<Item render={<Link to="…" />}>`,
`<PopoverTrigger render={<Button …/>} />`. Anything copied from a Radix-based registry — including
most "shadcn Timeline" snippets — will not drop in. Port the markup and rewire the slot.

base-ui.com documents whatever Base UI released last, which is not necessarily what is installed. A
`render={(props, state) => …}` copied from the live docs may not exist at the pinned version — read the
pin in `webapp/package.json`, or the installed `.d.ts`, before copying.

Zero `asChild` in the tree is the baseline, not an achievement — `react/forbid-component-props` in
`webapp/.oxlintrc.json` fails the build on the prop and says so in the message. Do not credit it in
review.

## 5. A slotted element keeps four obligations, and syntax is the easy one

The primitive hands you its behaviour and steps back. What it hands over is *unrendered*, so your
element must:

- **(a)** forward its `ref`.
- **(b)** spread **every** received prop onto the real DOM node.
  Dropping `aria-*`, `role`, `id`, or handlers can stop a trigger from announcing its popup.
- **(c)** render exactly one root element, never a fragment (house policy — true, but on neither
  source page).
- **(d)** stay the element type the primitive expects. A `div` where a `button` was expected loses
  Enter/Space and the tab stop.

(a) and (b) are verbatim Base UI:

*"The custom component must forward the `ref`, and spread all the
received props on its underlying DOM node."*

<https://base-ui.com/react/handbook/composition>
(d) is Radix:

<!-- vale STE.Words = NO -->
*"If you do decide to change the underlying element type, it is your responsibility to
ensure it remains accessible and functional."*
<!-- vale STE.Words = YES -->

(<https://www.radix-ui.com/primitives/docs/guides/composition>)

The story that proves it queries the slotted element by **role and accessible name** after the slot,
which fails if `aria-*` was dropped.

## 6. `className`, the remaining DOM props and `ref` reach the root — in the kit and in shared atoms

A stability contract, not a configuration knob: a screen that needs one margin here must not fork the
component. `React.ComponentProps<"div">` (or of the primitive being wrapped), minus the props you own,
is the type. Exempt from the two-call-site rule.

**Scope.** `webapp/src/components/ui/**`, plus the shared atoms in `webapp/src/components/common/`
and `webapp/src/components/practice-vocabulary/`. **Not** page shells — the rule is deliberately
narrow, and most of the tree sits outside it. Carbon, the source, says

*"Where possible, the following
should be placed on the outermost, parent, or root element"*

The qualification is *where possible*.
Carbon documents a case where `...rest` goes on an interior element instead.
Carbon also describes a published kit with external consumers.
Its stability argument is:

*"Consumers rely on the placement of these within
the DOM"*

That argument has no force for a component with one in-repo caller.
<https://github.com/carbon-design-system/carbon/blob/main/docs/style.md>

Copy `webapp/src/components/common/StatusBadge.tsx`.

## 7. Before you build anything, read `webapp/src/components/ui/`

The kit has no `timeline`, no `stepper`, and no `data-table`.
None is worth a dependency.
A vertical rail of steps is a border and rounded spans (`webapp/src/components/practice-vocabulary/DeliveryTrace.tsx`).

Some primitives are vendored *and* locally modified.
Each such file states this in a header comment.
Thus, the next re-vendor restores the change instead of dropping it. Find them with
`grep -rl "Diverges from the shadcn registry" webapp/src/components/ui` before you work around a
defect — the divergence is fixed there once rather than hand-rolled at each call site. Editing
anything under `ui/` is gated by `webapp/AGENTS.md` § Ask first.
