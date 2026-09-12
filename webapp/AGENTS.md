# Webapp

React 19 SPA on TanStack Router/Query, Tailwind 4, shadcn primitives over **Base UI**
(`@base-ui/react`) — not Radix. Vitest + Storybook for tests, oxlint for lint and oxfmt for format,
Vite for the build. React Compiler runs at build time.

This file is the gotchas. Conventions that a look at the tree, or a lint message at the call site,
already tells you are not here.

## Commands

| Task | Command |
|------|---------|
| Dev server | `vp run dev:webapp` — port 4200, `strictPort`, overridable with `WEBAPP_PORT` |
| Type check | `vp run typecheck:webapp` |
| Lint + format | `vp run check:webapp` — does **not** type-check; that is the separate leg above |
| Tests | `vp run test:webapp` |
| Storybook | `vp run --filter webapp storybook:dev` |
| Story tests | `vp run --filter webapp test:storybook` |

## Ask first

A new UI library dependency · a new Zustand store · a global style · a change under
`src/components/ui/` (below). Each of these is cheap to add and expensive to reverse, and none of them
has a gate.

## `src/components/ui/` is a registry install, not a directory you own

`components.json` pins the shadcn style `base-nova` over Base UI, and re-running the registry
**overwrites** the file it manages. So these files are editable — but an edit is a fork you re-apply
every time the primitive is re-vendored, not a change that stays made. That makes the rule about
provenance rather than permission: check a change against upstream first, and never make one in
passing while you are there for something else.

A deliberate divergence is recorded in the file it lives in, so the next re-vendor restores it instead
of silently reverting a fix. `alert-dialog.tsx` and `dialog.tsx` head theirs with
`⚠️ Diverges from the shadcn registry` and enumerate what re-vendoring drops; smaller ones are noted
where they sit, like `accordion.tsx`'s header sizing or `select.tsx` requiring `items` where Base UI
leaves it optional.

The payoff is that an upstream defect gets fixed here once, with the note attached, instead of being
worked around at every call site.

## File naming

`unicorn/filename-case` accepts both cases, so it cannot make the choice for you. What decides it is
what the file exports:

- **`PascalCase.tsx`** — a file whose export is a React component. The filename is the component
  name, so `AdminLlmUsagePage.tsx` exports `AdminLlmUsagePage`. Its `.test.tsx` and `.stories.tsx`
  siblings inherit the name. An acronym is a word, not a run of capitals: `LandingFaqSection`, not
  `LandingFAQSection`.
- **`kebab-case.ts`** — everything else: helpers, schemas, formatters, hooks, fixtures. A `.tsx` that
  exports a small helper component alongside its formatters is still a helper module.

Colocation does not change the rule: a helper next to the component that uses it is named the same
way as one in `src/lib/`. `src/routes/**` is exempt from the rule entirely, because TanStack Router
derives URL segments from the filenames there and the router owns that naming.

## Linting

**oxlint lints, oxfmt formats.** `.oxlintrc.json` is the whole rule set, every rule it turns off
carries the reason beside it, and every restriction states itself at the call site when it fires. None
of that is repeated here. What follows is what no diagnostic will ever tell you.

- **Suppress with `// oxlint-disable-next-line <rule> -- <why>`**, above the line the diagnostic
  points at, spelling the rule the way the **diagnostic** prints it — `plugin(rule)` becomes
  `plugin/rule`. A directive that suppresses nothing fails the build —
  `options.reportUnusedDisableDirectives` is `error` — so a misspelt rule, a rule that does not exist
  and a suppression that outlived its reason are all caught for you. One shape escapes that, next.
- **A hooks suppression silences the whole component.** oxlint routes every React-hooks diagnostic
  through one directive name, so `// oxlint-disable-next-line react/rules-of-hooks` placed **anywhere
  in a component body** also suppresses `react(set-state-in-effect)` and
  `react(no-deriving-state-in-effects)` for that whole component, whichever line it sits above.
  Neighbouring components in the same file keep reporting, so the file does not look disabled. The
  build still fails — the directive is reported as unused — but it fails complaining about the
  directive rather than about the effect, so the diagnostic you meant to silence is simply gone.
  The two effect rules report on the `setState` line *inside* the effect, not on the `useEffect`, so
  a directive above the effect never covers them. Fix the effect.
- **The config validator catches three shapes and misses a fourth.** oxlint refuses to start when
  `jsPlugins` names a module it cannot load, when `plugins` names a plugin it does not know, or when
  a rule names a rule its plugin does not define — including a namespace nothing provides. It does
  **not** catch a rule whose plugin is a built-in that `plugins` omits: that rule is dropped with no
  diagnostic and a zero exit. Check it in one command — a config of
  `{"plugins":["typescript"],"rules":{"unicorn/prefer-node-protocol":"error"}}` passes a file that
  imports `"path"`.
- **`react/forbid-dom-props` sees a DOM element and not a component.** `data-testid` on a `<div>` fails
  the build; the same attribute handed to `<Button>` or `<Textarea>` and spread onto the DOM from
  inside does not, so the ban has a hole the size of the component layer. Do not read that hole as
  permission.
- **The one exception `jsx-a11y/no-autofocus` earns** is the first field of an overlay the user just
  opened. Suppress that case inline with the reason; everything else is the bug the rule describes.
- **Re-derive an off rule's findings before trusting or changing the reason beside it** — but run it
  from the **repo root**, `oxlint -A all -D <rule> webapp`. Started from inside `webapp/` this config
  becomes the one oxlint treats as the root, and `options` is only ever read from that config: this
  file declares none, so `typeAware` goes off and every type-aware rule reports nothing while
  exiting 0. `-A all` is what keeps the rest of the rule set out of the answer.
- **An `off` entry only means something when a category would otherwise switch the rule on.** Every
  category but `correctness` and `suspicious` is off here, so `"off"` on a `pedantic`, `perf` or
  `style` rule documents a decision the config does not need to make. Confirm before adding one:
  delete the entry, re-run, and see whether anything reports.

## Which admin console a component belongs to

`src/components/admin/**` holds two different consoles, and **the directory name does not tell you
which**: `admin/llm/` is the instance-wide LLM console, `admin/ai/` is the per-workspace one. The
**component name** is what carries the scope, and it is the only thing that does:

- `Admin*` or `Instance*` — instance-wide. Configures the deployment for every workspace.
- `Workspace*` — scoped to one workspace.
- **Unprefixed — shared by both, so it must not assume a scope.** `LlmConnectionFields` and
  `LlmModelFields` are each rendered by an `Admin*` dialog *and* a `Workspace*` one. Adding a
  scope-specific field, permission check or copy string to one of these breaks the other console
  silently, because nothing in the file says it has two callers.

Name a new component for its scope, and check the caller list before editing an unprefixed one.

Splitting these into `admin/instance/` and `admin/workspace/` has been considered and rejected: the
shared components in the third bullet have no scope to be filed under, so a split would need a
`shared/` directory anyway and the path still would not answer the question — while moving every file
in the tree. The prefix answers it, and it also shows up in imports and in the Storybook sidebar.

## Container/presentation split

- **Routes** (`src/routes/**`): data fetching, loaders, auth guards, side effects.
- **Hooks** (`src/hooks/use-*.ts`): a route's fetching, factored out when more than one route needs it.
- **Components** (`src/components/**`): presentational, with no exception for a "cohesive section".
  They take their data as props and never import the query layer.

`scripts/check-presentational-components.ts` enforces it, and `vp run check` runs it. Two halves,
because they fail differently:

- **A component may not import the generated query layer or call a TanStack query hook.**
  `@/api/types.gen` is pure types and stays allowed everywhere. Put the `useQuery` in the route file
  and pass plain props down; when two routes need the same call, move it to `src/hooks/use-*.ts`.
- **A story file may not install MSW handlers.** Autodocs mounts every story of a file into one
  document and `msw-storybook-addon` installs on a single global worker, so the last story's handlers
  answer for the whole page — one error story silently breaks its siblings' Docs page while every
  isolated story, and therefore every test and every snapshot, stays green.

The allowlist inside that script is **shrink-only** — an entry that scans clean fails the build, so it
cannot go stale. Fix the file, do not add to it.

**A story renders with no network at all.** A story's job is to prove the component renders what it is
given. A wire contract — which search params a filter turns into, whether a facet reaches the query key
— is not a rendering fact and does not belong in a story mock; assert it in a route test, which owns
the query. Storybook is where you look at the component; the route test is where you check what it
asks for.

## Seeding a form from props

Put the form body in its own component and `key` it on the subject being edited, so switching subjects
remounts it with fresh initial state. Never copy props into state from an effect: between the prop
change and the effect running, the form shows the *previous* subject's values under the new subject's
title.

## Data fetching

Spread the generated options; never hand-write a `queryKey`:

```typescript
const documentQuery = useQuery(getDocumentOptions({ path: { id } }));
queryClient.setQueryData(getDocumentQueryKey({ path: { id } }), updated);
```

Do not duplicate loading or error state in local state, and do not hand-roll fetch mocks in tests —
the network is MSW (`src/mocks/handlers.ts`, installed by `src/test/setup-msw.ts`). A bare `fetch` or
`XMLHttpRequest` fails the build under `src/**`; the handful of calls that are not server data are
suppressed at the call site with their reason.

| State | Where |
|---|---|
| Server data | TanStack Query |
| UI preferences | Zustand (`src/stores/**`) |
| Form state | React state / controlled components |
| URL state | TanStack Router search params |

## React Compiler

`vite.shared.ts` turns it on for every build of app source, so `useMemo`, `useCallback`, `memo` and
`forwardRef` are not imports you may take from `react` — the import line says so and why.

**A `useMemo` that survives is load-bearing, and deleting it breaks something that still type-checks.**
There are only three shapes that earn the suppression, and each names its own on the line above the
import: the value is a **dependency of an effect**, so its identity is what decides whether the effect
re-runs; the component is **opted out of the compiler** by a library it uses, so nothing memoises for
it — `react/incompatible-library` names that case where it can; or a **library keys a cache on the
value's identity**, which the compiler memoises as an optimisation rather than promises. TanStack
Table is the third: `useTable` compares `options.columns` and `options.data` with `!==` and rebuilds
the column model, and every row model downstream, when either changes — so a table here memoises
them by hand. Anything else is the memo the compiler exists to remove.

## The time of day

**Reading a clock or the RNG during render fails the build**, because a render that reads a moving
value never answers the same way twice: two components mounted in one commit disagree, and a story or a
snapshot never repeats. There are two sanctioned readings, and no third:

- **Component code takes the time from `useNow`** (`@/components/common/use-now`) — a ticking
  `useSyncExternalStore` clock shared by every subscriber on the page, so a phrase derived from it ages
  on its own instead of freezing at mount. It returns milliseconds rather than a tick count on purpose:
  a counter is not an input to the phrase, so the compiler would memoise the phrase and strand it on
  screen while the counter ticked underneath.
- **A story takes it from `STORY_NOW` and the relative helpers beside it**
  (`@/components/common/story-clock`), read once per module load. A hard-coded literal is not the
  alternative — it drifts into "8 months ago" as the calendar moves and puts every "expires in …"
  branch permanently in the past.

`hephaestus/no-nondeterministic-render` reports a reading at module scope or inside a component or
hook, and deliberately says nothing about one in an event handler, an effect or a `useState`
initializer: the AST can find the nearest enclosing function but not who calls it, and reporting those
would be wrong about most of the readings here. `Date.now` is separately unavailable anywhere under
`src/**`, so the two rules disagree about a handler on purpose — `new Date()` there is fine and
`Date.now()` is not.

## Name a component for the concept, not for the wire

A component, its file, its props type and its story title all name the same thing, and that name is
the one in `docs/contributor/practice-feedback-language.md` — the product's word, not the server's and
not the one the screen used to use. The unit a review detects is an **observation**; the unit delivered
to a developer is **feedback**. Neither is a "finding" or a "message".

Renaming the copy alone does not hold: a name that survives in the import list is the one the next
component gets named after. When a concept is renamed, the sweep is wire contract, route, component
file, exported symbols, props type, story title, story export names, then copy — in that order.
Stopping at copy leaves the old vocabulary somewhere a grep for the screen text will not find it.

## Component API and Storybook

Both live in the **`storybook-components` skill** (`.claude/skills/storybook-components/`). Load it
when you design a component's props, write or review a `*.stories.tsx`, or grade a webapp diff. It has
a routing table on the front page; its `RUBRIC.md` is the grading instrument for review.

## Routing

Declare routes with `createFileRoute`. Keep loaders side-effect free and prefer
`context.queryClient.query(...)` with the generated options. Put shared data (query client,
auth) on the router context. Never hand-edit `routeTree.gen.ts` — there is no `tsr` CLI here; the
TanStack Router Vite plugin regenerates it when the dev server runs.

The route's `workspaceSlug` is the only source for workspace-scoped data; there is no store. The app
chrome renders on routes whose URL names none, so it reads `chromeWorkspaceSlug`, which falls back to
the first workspace.

## Role-based gating (OWNER > ADMIN > MEMBER)

Client-side gating is a UX affordance only — the server enforces authorization on every endpoint.
Never re-invent `role === "ADMIN"`; use the shared pieces:

- **Whole admin surfaces — gate by placement, not by a check.** Put workspace-admin pages under
  `src/routes/_authenticated/w/$workspaceSlug/admin/`; its `route.tsx` layout carries the `beforeLoad`
  role guard, so every route in the directory inherits it. A file that maps to an `/admin` URL without
  nesting under that layout silently skips the gate — that is the bug class `admin/-route.test.ts`
  exists to catch, by driving every admin URL in the generated route tree through the real router as a
  MEMBER (the leading `-` marks the file as a test rather than a route). Do not weaken it.
- **Individual controls**: `useWorkspaceAccess()` returns `role` and `isAdmin`; the role math is
  `hasMinimumWorkspaceRole` (`src/lib/workspace-roles.ts`). Pure role predicates live in `src/lib/`;
  QueryClient-coupled resolvers (`resolveWorkspaceMembership`, `workspaceMembershipQueryOptions`) live
  in `src/integrations/auth/guard.ts`. Fetch membership only via `workspaceMembershipQueryOptions` so
  every caller shares one cache entry and one `staleTime`.
- **Hide rather than disable** — for permissions specifically. Disabling is the better default for a
  control the user could still unlock, but ["hiding is recommended in cases where the user will never be
  able to use that feature due to their role or license"](https://www.uxtigers.com/post/inactive-buttons),
  which is this case. A disabled control would also be a poor explanation: a native `disabled` button is
  unreachable by keyboard, so a tooltip saying why can never be read.
- **Workspace role ≠ instance role.** `useWorkspaceAccess().isAdmin` is membership in *this* workspace;
  `useAuth().isAppAdmin` is instance-wide (ADR 0017). A workspace-role gate on a surface with no active
  workspace is always false.
- There is **no `<RequireRole>` wrapper component**. Route placement covers whole surfaces, and the
  rest is a boolean the route reads once — which then gets drilled: `canAdminister` reaches
  `RefusalFixLink` through `TracePage` and either `TraceRefusalAlert` or `TraceSignalTimeline`, two
  hops for one leaf. Before adding a third, render the gated leaf in the route and pass it down as
  `children`. When a role-assignment UI lands, its mutation must invalidate the membership query key.

## Styling

Compose Tailwind utilities with `import { cn } from "cn"` directly; do not add a local wrapper.
`components.json` sets `aliases.utils` to `cn` so registry installs use the same package import.
Prefer a semantic token from the `--color-*` block in `src/styles.css` over a hard-coded value;
`text-muted-foreground`, `text-foreground`, `bg-background` and `border-border` carry most of the tree.
Read that block rather than guessing a name.

**A `*.module.css` is for what a utility cannot express, and for nothing else.** There are two in the
tree — `HephIcon` and the landing scene — and each holds `@keyframes`, a generated `::before`, a
`clip-path`, or a grid whose placement descendants override at a breakpoint. Anything a utility can
say stays a utility: a module rule that is one `letter-spacing` or one `margin` is a utility in the
wrong file, and it silently outranks the utility it duplicates, because Vite emits module CSS
unlayered while Tailwind sits in `@layer utilities`. That inversion is the whole cost of the second
system, so **own a property in one place or the other, never both** — a margin the module changes at
a breakpoint belongs to the module at every width, not to `mt-8` at one of them.

## Testing

`getByRole` > `getByLabelText` > `getByText`, and the ladder ends there: a `data-testid` skips past the
accessible name a screen reader needs anyway.

**`@testing-library/jest-dom` is not registered in the Vitest unit project** — `vite.config.ts` lists
one setup file, `./src/test/setup-msw.ts`, and it does not import the matchers. So
`expect(el).toBeInTheDocument()` in a `*.test.tsx` throws `Invalid Chai property` at runtime with `tsc`
perfectly happy. `vitest/no-restricted-matchers` catches the common ones before you run anything and
names the replacement in its message; for one it does not list, assert on the plain value.

The matchers **are** available in stories, because `expect` from `storybook/test` ships them. Copying
an assertion out of a story into a route test is exactly how this bites.

## Type checking

**A dot-directory is invisible to a `**/*` include.** `tsconfig.json`'s `**/*.ts` does not match
`.storybook/`, which is why the directory is named explicitly in `include`; a new dot-directory
needs its own entry. Confirm with `tsc -p webapp/tsconfig.json --listFiles` before assuming a config
file is covered.

## Generated files

`src/api/**` and `src/routeTree.gen.ts` are generated — the repo-root `AGENTS.md` has the commands.
Regenerating **empties** `src/api/`, so nothing hand-written survives there, not even a test about the
generated client. `src/test/response-transformers.test.ts` lives outside that directory for exactly
this reason.

### Dates from the API

A `format: date-time` field comes back from an SDK call as a real `Date`: `openapi-ts.config.ts` sets
`transformer: true` on the `@hey-api/sdk` plugin, which wires the generated response transformers into
every SDK call. Two consequences:

- **Fixtures passed straight to a prop use `new Date(…)`** — that is the shape production sends.
  Fixtures served through **MSW** are JSON and use ISO strings; type those with `Wire<T>` from
  `@/lib/dates`, which turns every nested `Date` in a generated view into a `string`.
- **`asDate()`** (also `@/lib/dates`) takes `Date | string | null | undefined` and returns a `Date` or
  `undefined` — never an Invalid Date, which renders as the literal text "Invalid Date". Reach for it
  wherever a timestamp can reach a component as a string despite its type saying `Date`, and for
  optional fields.

`transformer: true` is load-bearing and invisible to `tsc`: without it the types still say `Date` while
the client hands back strings, so `.toLocaleDateString()` crashes at runtime.
`src/test/response-transformers.test.ts` is the guard; it calls the real SDK, so it fails whenever that
wiring is lost.

## Drawer or route

A detail surface goes in a `DetailDrawerStack` level (`src/components/core/detail-drawer/`) when
**all three** hold. Fail one and it is a route.

1. **Contextual** — the covered page is why the reader is here, and the column the stack leaves
   showing is doing work. At 320px a drawer is full-width, so this criterion buys nothing there:
   it has to be earned on the wide layout.
2. **Single-decision** — one primary action, reachable without hunting. A level with two competing
   primaries is two levels, or a page.
3. **Reversible** — either dismissal destroys nothing, or the level is **guarded**.

Length is not a criterion. `DrawerBody` scrolls, and a form that is seven viewport-heights at 320px
is seven viewport-heights on a page too — the scroll happens either way, and the drawer keeps the
tree the entry belongs to on screen while it happens.

### Panel regions

A drawer level is three regions and nothing else: `DrawerHeader`, `DrawerBody`, `DrawerFooter`. Each
reaches both edges of the panel; only `DrawerBody` scrolls.

- **A form's actions are the level's `DrawerFooter`,** which means the `<form>` is the level: `flex
  min-h-0 flex-1 flex-col`, wrapping its own `DrawerBody` and `DrawerFooter`. A sticky bar written
  inside the body instead inherits that body's padding — it floats short of both edges and leaves a
  strip of dead space beneath it at the end of the scroll. Negative margins to cancel the padding are
  the same bug with a longer fuse.
- **The panel is the measure.** A `max-w-*` on the controls is a page-era habit; inside a panel sized
  for the form it only strands the footer's buttons to the right of the fields. Cap *prose* instead,
  which is what `PracticeDefinitionPreview` does.
- **The header content row is two columns at most**, and the second is the title block. A drawer is
  the full viewport at 320px and the dismiss, the padding and a leading chip already spend 40% of it.
  Anything that is itself text — a badge, a status, provenance — goes *below* the title, inside that
  block. The row wraps as a backstop, but a third column is a design mistake, not a wrap case.

### Guarded levels

A level whose kind is in the host's `guardedKinds` closes **straight to the URL, without the exit
animation** the other levels get. Both editor stacks use it: `GUARDED_LEVEL_KINDS` (workspace
practices) and `GUARDED_CURATED_LEVEL_KINDS` (instance catalog).

That is the whole of it, and the reason is narrow: `useUnsavedChanges` blocks the *navigation* to ask
about the draft, so animating out first unmounts the form while the prompt is still on screen, and a
refused navigation then leaves the level shut with the URL still holding it open.

**A guarded level is not a level that refuses to be dismissed.** Escape, a press on the page, a swipe
and its own controls all close it, exactly like every other level — refusing a gesture silently is
indistinguishable from a broken drawer, and the reader has no way to learn the rule. What protects a
draft is the prompt, which appears on all four paths and on none of them when there is nothing to
lose.

Related trap: `useBlocker`'s `shouldBlockFn` sees `routeId`/`pathname`/`search`, and every drawer
navigation on one surface shares a route. A guard written as `() => isDirty` will block navigations
that do **not** unmount the form, so "Discard changes" discards nothing.

Paths that predate a level are kept as `beforeLoad` redirects into the stack — they were linked and
bookmarked. `admin/practices/new.tsx` is the shortest example.

## Loading and errors

Choose by **region**, not by feel.

| The thing that is loading | Show |
|---|---|
| A region whose shape you know at author time — list, table, card grid, form, page body, drawer body | A skeleton mirroring that shape, inside the region's real container |
| A control the reader just activated — button, switch, row action | `<Spinner />` inside the control, with the label changing ("Saving…") |
| Anything under ~1s | Nothing. Gate it — [`spin-delay`](https://www.npmjs.com/package/spin-delay) is the shape to copy or install; do not hand-roll the timer, its state machine has to be discrete or the effect re-arms |
| A whole route transition | The router's `pendingComponent`; it already delays 1000ms with a 500ms floor |

Below 1s a reader perceives the result as immediate and
[needs no feedback at all](https://www.nngroup.com/articles/response-times-3-important-limits/) — a
state that appears and vanishes inside that window reads as a fault.
[Polaris](https://polaris.shopify.com/components/feedback-indicators/spinner) restricts spinners to
"content that can't be represented with skeleton loading components".

**Never** — a bare centred spinner standing in for a region · `min-h-*` on a loading wrapper (that
guarantees the jump the skeleton exists to prevent — take a row count from the caller instead, see
`PracticeSkeletons.tsx`) · `role="status"` on a container that mounts with its text already inside
([ARIA22](https://www.w3.org/WAI/WCAG22/Techniques/aria/ARIA22) needs the role to exist *before* the
message) · `role` or `aria-label` on a `<Spinner>` inside a control, where a live region corrupts
the button's own accessible name. A spinner standing alone for a region opts in with those same
plain attributes; `Spinner` hides itself only when neither is present.

## Motion

280ms in, 200ms out. Enter is the longer half — it is the reader orienting; exit is getting out of
the way ([Material](https://m2.material.io/design/motion/speed.html): 225/195 and "transitions that
exceed 400ms may feel too slow"). Split the curve by direction: decelerate in
(`cubic-bezier(0.05,0.7,0.1,1)`), standard out (`cubic-bezier(0.2,0,0.38,0.9)`). A dismissible side
panel takes standard out, not accelerate — it can come back.

Under `prefers-reduced-motion`, cut what triggers symptoms and keep what carries meaning: no scaling,
no travelling the width of the viewport, but the fade stays so the arrival is still legible. Scope it
(`motion-reduce:` / a media block on the component); never `* { transition: none }`.

**Only `transform`, `opacity` and `filter` may appear in a `transition-*` on an overlay.** Anything
resolving to `width`/`height`/inset must be constant, or scoped to the axis where it is constant —
a survivor's geometry must never depend on the element that is leaving.

**The Storybook suite runs under `prefers-reduced-motion: reduce`** — `vitest.config.storybook.ts`
sets it on the Playwright context so play functions are deterministic. Every `motion-reduce:` rule is
therefore the branch under test, and no story can assert travel distance, an easing curve or a peek
width: those are exactly the values reduced motion zeroes. A motion regression has to be pinned on
something reduced motion leaves alone — the presence of `data-starting-style`, an attribute's
sequence over frames, or the class list itself.

**A drawer that mounts already open never animates in.** Base UI's `useTransitionStatus` seeds
`mounted` from `open`, so `open && !mounted` — the branch that sets `starting` — cannot run on the
first render, and the popup gets no `data-starting-style` frame. Anything mounted by URL state hits
this. Mount it closed and open it a frame later (`DetailDrawerStack`'s `useArrived`); an effect is
not enough, because a passive effect can be flushed before the browser paints the closed state.

## Search params that are UI state

A filter, a toggle, an open panel on the page you are already on is not a navigation. Write it with
`useSearchState` (`lib/search-params.ts`), which passes `resetScroll: false` — the router resets
scroll on **every** commit, including a search-only one, so without it a control halfway down a long
page throws the reader back to the top.

## Vocabulary

`docs/contributor/practice-feedback-language.md` is normative, and a word it retires is retired in
code, types and story titles as well as in copy. Prefer dropping the class noun where the thing's own
name is already on screen: "No practices here", not "No practices in this group".

## Status vocabularies

Every enum a practice surface renders goes in `components/practice-vocabulary/` as `StatusDefs` and
renders through `StatusBadge`. The icon is required and unique within its enum because badge variants
collapse, and colour alone fails WCAG 2.2 SC 1.4.1. **Nothing else may hold words, a colour or an
icon for an enum value** — a bare `<Badge>` next to a registry badge reads as the same family, so a
*setting* becomes indistinguishable from a *provenance state*.

A registry entry that would be identical for every value is not information. Say what to do about it
in a sentence instead.

## Field orientation

`Field` ships three orientations and the choice is about **the control's width**, not about taste:

| Control | Orientation |
|---|---|
| Switch, checkbox, radio — a fixed ~32px box | `horizontal`. It fits beside its label at 320px. |
| Select, input, anything with a `w-*` — a real column | `responsive`, inside a `FieldGroup`, and size it `w-full @md/field-group:w-56` |
| Textarea, editor, anything full-bleed | the default vertical |

`responsive` reads `@md/field-group`, and **only `FieldGroup` opens that container** — a `responsive`
field with no `FieldGroup` ancestor is stacked at every width, which looks fine on a phone and wrong
on a desktop. A `horizontal` field with a 14rem control is the opposite mistake: at 320px it leaves
the label about 60px and the row squashes.

Fixed widths on a control inside a responsive field must be breakpoint-scoped for the same reason —
`w-56` alone cannot stack.
