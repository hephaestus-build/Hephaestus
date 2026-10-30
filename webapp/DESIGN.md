# Hephaestus design

Hephaestus should feel like a calm, precise view of engineering work. Developers come here to understand feedback, inspect its evidence, and choose what to do next. Quiet surfaces make the work easier to read. Emphasis belongs to an actual action, state, or useful distinction.

## Foundations

The existing system is the starting point:

| Fact | Authoritative owner |
| --- | --- |
| Application colors, light/dark roles and radius | `src/styles/theme-tokens.css` |
| Application fonts and integration palettes | `src/styles.css` |
| Component family and registry style | `components.json` |
| Control geometry and semantic variants | `src/components/ui/` |
| Brand artwork | `../docs/contributor/brand-assets.mdx` |
| Product language | `../docs/contributor/practice-feedback-language.md` |

These owners define the values. A CLI fallback preset or an external design reference does not replace them.

## Reading data

A table should let someone identify a row, interpret a figure, and inspect the associated work. Identity deserves readable space at a narrow width. A pinned name must still leave room to discover the scrolling figures. A truncated visible name retains its full accessible label.

Visible headers name visual data. Icons and color support those names. They should not require someone to guess what a figure represents. Zero, unknown, stale data and an empty result mean different things. Their existing domain display owners preserve those meanings.

Use the available page width for data and the existing prose measure for explanation. Group controls near what they affect. On a small screen, the primary content should follow the minimum explanation needed to interpret it. Compress spacing before removing meaning.

## Composition owners

| Need | Existing recipe |
| --- | --- |
| Standard page | `StandardPageSurface`, `PageLayout`, `PageHeader`, `Section` in `src/components/layout/` |
| Scrollable data | `Table` and the feature's table composition. The table owns horizontal scroll |
| Empty, error and pending regions | § Loading and errors |
| Contextual levels | § Drawer or route and § Panel regions |
| Practice surface emphasis | § Practice surfaces palette |
| Observable component states | Existing feature stories and `design-system-axes.stories.tsx` |

Standard page recipes apply where the route uses the standard surface. Fullscreen and existing specialized surfaces keep their own layout owners. Contextual navigation follows the individual level's supported contract. This document does not add URL or parent-navigation behavior.

## Styling

Compose Tailwind utilities with `import { cn } from "cn"` directly. Do not add a local wrapper.
`components.json` sets `aliases.utils` to `cn` so registry installs use the same package import.
Prefer a semantic token from the `--color-*` block in `src/styles/theme-tokens.css` (shared with the
Chrome extension. Provider colors stay in `src/styles.css`) over a hard-coded value.
`text-muted-foreground`, `text-foreground`, `bg-background`, and `border-border` carry most of the tree.

Read that block rather than guessing a name.

**A state variant loses to any other variant on the same property.**
`shadcn/tailwind.css` defines `data-open:`, `data-checked:`, `data-active:`, and their siblings as `:where()` selectors, as the registry expects.
Thus, they add no specificity.
`dark:bg-input/20` or `hover:bg-muted` beats `data-checked:bg-primary` regardless of class order. Compound the state the way upstream does —
`dark:data-checked:bg-primary`, `hover:not-data-checked:bg-muted/50`.

**A `*.module.css` is only for what a utility cannot express.**
The tree has two: `HephaestusMark` and the landing scene.
Each holds one or more of these:

- `@keyframes`.
- A generated `::before`.
- A `clip-path`.
- A grid whose descendants override their `grid-row` or `grid-column` at a breakpoint.
- `HephaestusMark`'s size, which a container's `[&_svg]:size-4` would outrank as a utility.

Anything that a utility can express stays a utility.
A module rule for one `letter-spacing` or `margin` is a utility in the wrong file.
It silently outranks the utility it duplicates.
Vite emits module CSS unlayered, while Tailwind sits in `@layer utilities`.

That inversion is the whole cost of the second system.
**Own a property in one place or the other, never both.**
If the module changes a margin at a breakpoint, that margin belongs to the module at every width.
It does not belong to `mt-8` at one width.

## Practice surfaces palette

The Practice profile, levels over it, and feedback cards share one 60-30-10 palette.

**60, grounds**: `bg-background`, `bg-card`, and `bg-sidebar`.
Nothing else paints a ground.

**30, structure**: `border` and `text-muted-foreground` carry lines, pills, rails, and secondary text.

**10, the accent**: `highlight` marks only what should attract attention.
The token is not named `accent`, because shadcn already uses that name for hover surfaces.
Use at most one `variant="highlight"` button per surface.
The accent marks new content, the current selection or sort, and links on hover or focus.
On Practices across the workspace, it also marks the reader: the **You** bracket over a bar and the pin on a tile.

A link is plain text at rest, except a count that opens its list.
That count uses `InlineLink tone="count"`, with a faint `decoration-border` underline at rest.
Thus, a row of figures shows which ones open.

Status colors (`success`, `warning`, `destructive`) convey meaning, not accent.
They reach a surface only through the registries.
This includes their badges and icons, and the rings, meters, and pressed responses drawn from them.
Never use them as colored prose.

A card's own ground has a wash in exactly two states: new, in the accent, and resolved by the work, in `success`.
A card that the reader marked as addressed or not applicable closes on the neutral ground.
`success` is the only status color that paints a surface.

The next step band inside a card is the card's call to action while the reader can still act on it.
On open feedback (`isOpenFeedback`: new and open), the band takes the accent wash, the accent edge, and the accent icon.
On a card that the work resolved, it takes the `success` wash.
On a marked or closed card, it keeps the neutral `bg-sidebar` ground and a muted icon.
The loading card does not know its state yet, so its band keeps the neutral ground.

## Drawer or route

A detail surface goes in a `DetailDrawerStack` level (`src/components/layout/detail-drawer/`) when
**all three** hold. Fail one and it is a route.

1. **Contextual** — the covered page is why the reader is here.
   The column left visible by the stack does useful work.
   At 320px, a drawer is full-width, so this criterion adds nothing there.
   It must be earned on the wide layout.
2. **Single-decision** — one primary action, reachable without hunting. A level with two competing
   primaries is two levels, or a page.
3. **Reversible** — either dismissal destroys nothing, or the level is **guarded**.

Length is not a criterion.
`DrawerBody` scrolls.
A form that is seven viewport-heights at 320px is seven viewport-heights on a page too.
The reader scrolls in either case.
The drawer keeps the entry's tree on screen during the scroll.

### Panel regions

A drawer level is three regions and nothing else: `DrawerHeader`, `DrawerBody`, `DrawerFooter`. Each
reaches both edges of the panel. Only `DrawerBody` scrolls.

- **A form's actions are the level's `DrawerFooter`.** Thus, the `<form>` is the level.
  It uses `flex
  min-h-0 flex-1 flex-col` and wraps its own `DrawerBody` and `DrawerFooter`.
  A sticky bar inside the body inherits that body's padding.
  It stops short of both edges and leaves dead space below it at the end of the scroll.
  Negative margins to cancel that padding create the same defect later.
- **The panel is the measure.** A `max-w-*` on the controls is a page-era convention. Inside a panel sized
  for the form it only strands the footer's buttons to the right of the fields. Cap *prose* instead,
  which is what `PracticeDefinitionPreview` does.
- **The header content row has at most two columns.** The second is the title block.
  At 320px, a drawer fills the viewport.
  Dismiss, padding, and a leading chip already use 40% of it.
  Text, such as a badge, status, or provenance, goes *below* the title inside that block.
  The row wraps as a fallback.
  A third column is a design mistake, not a wrap case.

  A picture summary, such as a ring and its counts, can sit beside the title.
  It wraps below the title below `sm`.

### Guarded levels

A level whose kind is in the host's `guardedKinds` closes **straight to the URL, without the exit
animation** the other levels get. Both editor stacks use it: `GUARDED_LEVEL_KINDS` (workspace
practices) and `GUARDED_CURATED_LEVEL_KINDS` (instance catalog).

This behavior has one specific reason.
`useUnsavedChanges` blocks the *navigation* to ask about the draft.
An exit animation first would unmount the form while the prompt remains on screen.
If the reader refuses navigation, the level then stays shut while its URL still holds it open.

**A guarded level is not a level that refuses to be dismissed.**
Escape, a press on the page, a swipe, and its own controls all close it, like every other level.
Silently refusing a gesture looks like a broken drawer.
The reader has no way to learn that rule.

The prompt protects a draft.
It appears on all four paths, and on none when there is nothing to lose.

Related trap: `useBlocker`'s `shouldBlockFn` sees `routeId`/`pathname`/`search`, and every drawer
navigation on one surface shares a route. A guard written as `() => isDirty` will block navigations
that do **not** unmount the form, so "Discard changes" discards nothing.

Delete a page that becomes a level instead of redirecting it.
Before 1.0, an address that only the app links to is not a contract.
A redirect kept for a bookmark becomes a route that nobody can remove later.
An address that people share outside the app, such as a member's profile, keeps a `beforeLoad` redirect into the stack.
Links in the app open a level with `DetailStackLink`, or with `detailSearch(entry)` from another
route.

## Loading and errors

Choose by **region**, not by feel.

| The thing that is loading | Show |
|---|---|
| A region whose shape you know at author time — list, table, card grid, form, page body, drawer body | A skeleton mirroring that shape, inside the region's real container |
| A control the reader just activated — button, switch, row action | `<Spinner />` inside the control, with the label changing ("Saving…") |
| Anything under ~1s | Nothing. Gate it with the shape of [`spin-delay`](https://www.npmjs.com/package/spin-delay), copied or installed. Do not hand-roll the timer. Its state machine must be discrete, or the effect re-arms. |
| A whole route transition | The router's `pendingComponent`. It already delays 1000ms with a 500ms floor |

Below 1s a reader perceives the result as immediate and
[needs no feedback at all](https://www.nngroup.com/articles/response-times-3-important-limits/).
A state that appears and vanishes inside that window reads as a fault.
[Polaris](https://polaris.shopify.com/components/feedback-indicators/spinner) restricts spinners to this exact source wording:

<!-- vale STE.Contractions = NO -->
"content that can't be represented with skeleton loading components".
<!-- vale STE.Contractions = YES -->

**Never** use these patterns:

- A bare centred spinner that stands in for a region.
- `min-h-*` on a loading wrapper. It guarantees the jump the skeleton exists to prevent.
  Take a row count from the caller instead. See `PracticeSkeletons.tsx`.
- `role="status"` on a container that mounts with its text already inside.
  [ARIA22](https://www.w3.org/WAI/WCAG22/Techniques/aria/ARIA22) needs the role to exist *before* the message.
- `role` or `aria-label` on a `<Spinner>` inside a control.
  A live region corrupts the button's own accessible name.

A spinner standing alone for a region opts in with those same plain attributes.
`Spinner` hides itself only when neither is present.

## Motion

280ms in, 200ms out. Enter is the longer half — it is the reader orienting. Exit is getting out of
the way ([Material](https://m2.material.io/design/motion/speed.html): 225/195 and "transitions that
exceed 400ms may feel too slow"). Split the curve by direction: decelerate in
(`cubic-bezier(0.05,0.7,0.1,1)`), standard out (`cubic-bezier(0.2,0,0.38,0.9)`). A dismissible side
panel takes standard out, not accelerate — it can come back.

Under `prefers-reduced-motion`, remove what triggers symptoms and keep what carries meaning.
Do not scale or travel the width of the viewport. Keep the fade so the arrival is still legible. Scope it
(`motion-reduce:` / a media block on the component). Never `* { transition: none }`.

**Only `transform`, `opacity` and `filter` may appear in a `transition-*` on an overlay.**
Anything resolving to `width`/`height`/inset must be constant, or scoped to the axis where it is constant.
A survivor's geometry must never depend on the element that is leaving.

**The Storybook suite runs under `prefers-reduced-motion: reduce`** — `vitest.config.storybook.ts`
sets it on the Playwright context so play functions are deterministic. Every `motion-reduce:` rule is therefore the branch under test.
No story can assert travel distance, an easing curve or a peek width.
Reduced motion zeroes those values.

Pin a motion regression on something reduced motion leaves alone.
Examples include the presence of `data-starting-style`, an attribute's sequence over frames, or the class list itself.

**A drawer that mounts already open never animates in.** Base UI's `useTransitionStatus` seeds `mounted` from `open`.
Thus, `open && !mounted` — the branch that sets `starting` — cannot run on the first render.
The popup gets no `data-starting-style` frame. Anything mounted by URL state hits
this.

Mount it closed and open it one layout effect later.
`useArrived` (`src/components/layout/detail-drawer/use-arrived.ts`) does this and explains why.

## Status vocabularies

Every enum a practice surface renders goes in `components/practice-vocabulary/` as `StatusDefs` and
renders through `StatusBadge`. The icon is required and unique within its enum because badge variants
collapse, and colour alone fails WCAG 2.2 SC 1.4.1. **Nothing else may hold words, a colour or an icon for an enum value**.
A bare `<Badge>` next to a registry badge reads as the same family.
Thus, a *setting* becomes indistinguishable from a *provenance state*.

A registry entry that would be identical for every value is not information. Say what to do about it
in a sentence instead.

## Field orientation

`Field` ships three orientations and the choice is about **the control's width**, not about taste:

| Control | Orientation |
|---|---|
| Switch, checkbox, radio — a fixed ~32px box | `horizontal`. It fits beside its label at 320px. |
| Select, input, anything with a `w-*` — a real column | `responsive`, inside a `FieldGroup`, and size it `w-full @md/field-group:w-56` |
| Textarea, editor, anything full-bleed | the default vertical |

`responsive` reads `@md/field-group`, and **only `FieldGroup` opens that container**.
A `responsive` field with no `FieldGroup` ancestor is stacked at every width.
This looks fine on a phone and wrong on a desktop. A `horizontal` field with a 14rem control is the opposite mistake: at 320px it leaves
the label about 60px and the row squashes.

Fixed widths on a control inside a responsive field must be breakpoint-scoped for the same reason —
`w-56` alone cannot stack.

## Evolving the system

Improve one readable composition at a time. Start from the actual screen and its existing Storybook owner. A new token, variant or shared recipe needs a named product constraint that the current system cannot express. Keep brand inspiration separate from that constraint.

Engineering, accessibility and verification requirements remain in `AGENTS.md` and its linked skills. Storybook is the living reference for how the real components compose. A second showcase route is unnecessary while those owners cover the need.
