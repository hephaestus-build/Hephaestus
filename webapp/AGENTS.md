# Webapp

All prose follows the [writing standard](../docs/contributor/simplified-technical-english.md).
It has two profiles.
UI text, user-facing responses, and user docs use the product voice.
Admin docs, contributor docs, and repository instructions use STE.

React 19 SPA on TanStack Router/Query, Tailwind 4, shadcn primitives over **Base UI**
(`@base-ui/react`) — not Radix. Vitest + Storybook for tests, oxlint for lint and oxfmt for format,
Vite for the build. React Compiler runs at build time.

This file explains constraints that are easy to miss.
It omits conventions that the tree or a lint message at the call site already explains.

## Commands

| Task | Command |
|------|---------|
| Dev server | `vp run dev:webapp` — port 4200, `strictPort`, overridable with `WEBAPP_PORT` |
| Lint + format + type check | `vp run check:webapp` — oxlint runs with `typeCheck` on, so the TypeScript errors come out of the lint. `typecheck:webapp` is a pointer to the same gate. |
| Tests | `vp run test:webapp` |
| Storybook | `vp run --filter webapp storybook:dev` |
| Story tests | `vp run --filter webapp test:storybook` |

## Ask first

Ask before you add a UI library dependency, add a global style, or change `src/components/ui/` (below).
Each is easy to add and expensive to reverse.
None has a gate.

## `src/components/ui/` is a registry install, not a directory you own

`components.json` pins the shadcn style `base-nova` over Base UI.
If you run the registry again, it **overwrites** the file it manages.
These files are editable, but an edit is a fork.
You must reapply it whenever you re-vendor the primitive.
It is not a permanent change.

Thus, the rule concerns provenance, not permission.
First, check a change against upstream.
Never make an incidental change while you work on something else.

A deliberate divergence is recorded in the file it lives in, so the next re-vendor restores it instead
of silently reverting a fix. `alert-dialog.tsx` and `dialog.tsx` head theirs with
`⚠️ Diverges from the shadcn registry` and enumerate what re-vendoring drops. Smaller ones are noted
where they sit, like `accordion.tsx`'s header sizing or `select.tsx` requiring `items` where Base UI
leaves it optional.

The payoff is that an upstream defect gets fixed here once, with the note attached, instead of being
worked around at every call site.

A registry `cn-font-*` marker is not a divergence.
Where the project's CSS declares `--font-heading`, the CLI's `transform-font.ts` replaces the marker with `font-heading`.
Otherwise, it removes the marker, as it does in this project.

A variant with one caller is still a variant if it names a system axis: tone, size, shape, or edge.
`shadcn/no-restyle` forbids radius, spacing, or border composition on a primitive at the call site.
Upstream documents `rounded-full` or `border-dashed` as `className` composition.
Here, use `shape="pill"` and `variant="outlined"` instead.
Each axis has one home, not one per screen.

## File naming

`unicorn/filename-case` accepts both cases, so it cannot make the choice for you. What decides it is
what the file exports:

- **`PascalCase.tsx`** — a file whose export is a React component. The filename is the component
  name, so `WorkspaceLlmUsagePage.tsx` exports `WorkspaceLlmUsagePage`. Its `.test.tsx` and `.stories.tsx`
  siblings inherit the name. An acronym is a word, not a run of capitals: `LandingFaqSection`, not
  `LandingFAQSection`.
- **`kebab-case.ts`** — everything else: helpers, schemas, formatters, hooks, fixtures. A `.tsx` that
  exports a small helper component alongside its formatters is still a helper module.

Colocation does not change the rule: a helper next to the component that uses it is named the same
way as one in `src/lib/`. `src/routes/**` is exempt from the rule entirely, because TanStack Router
derives URL segments from the filenames there and the router owns that naming.

## Linting

**oxlint lints, oxfmt formats.**
The rule set has layers, as root `AGENTS.md` § Lint and format explains.
`oxlint.app.jsonc` owns what this tree shares with the extension, including compiler, Vitest, and Storybook rules.
This tree's `.oxlintrc.json` holds design-system checks and decisions that differ, each with its reason.
Each restriction explains itself in its diagnostic at the call site.

This file does not repeat those rules.
The following constraints do not appear in diagnostics.

- **Suppress with `// oxlint-disable-next-line <rule> -- <why>`**, above the line named by the diagnostic.
  Spell the rule as the **diagnostic** prints it: `plugin(rule)` becomes `plugin/rule`.
  A directive that suppresses nothing fails the build.
  `options.reportUnusedDisableDirectives` is `error`.
  Thus, the build detects misspelled rules, nonexistent rules, and suppressions whose reason no longer applies.
  The next case can still hide the original diagnostic.
- **A hooks suppression silences the whole component.** oxlint routes every React-hooks diagnostic through one directive name.
  `// oxlint-disable-next-line react-hooks/rules-of-hooks` can appear **anywhere in a component body**.
  It also suppresses `react(set-state-in-effect)` and `react(no-deriving-state-in-effects)` for that whole component.
  This happens regardless of the line below the directive.
  Neighboring components still report diagnostics, so the file does not appear disabled.

  The build still fails because it reports the directive as unused.
  It reports the directive, not the effect, so the diagnostic you intended to silence disappears.
  The two effect rules report on the `setState` line *inside* the effect, not on `useEffect`.
  Thus, a directive above the effect never covers them.
  Fix the effect.
- **The config validator catches three shapes and misses a fourth.** oxlint refuses to start in these cases:
  - `jsPlugins` names a module that it cannot load.
  - `plugins` names an unknown plugin.
  - A rule names a rule that its plugin does not define, including a namespace with no provider.

  It does **not** detect a rule whose plugin is a built-in omitted from `plugins`.
  It drops that rule with no diagnostic and a zero exit.
  Check this with one command.
  A config of `{"plugins":["typescript"],"rules":{"unicorn/prefer-node-protocol":"error"}}` passes a file that imports `"path"`.
- **`react/forbid-dom-props` sees a DOM element and not a component.** `data-testid` on a `<div>` fails
  the build. The same attribute handed to `<Button>` or `<Textarea>` and spread onto the DOM from
  inside does not fail.
  Thus, the ban does not cover the component layer.
  Do not treat that gap as permission.
- **A nullable string, number or boolean in a condition names its falsy case.**
  `typescript/strict-boolean-expressions` reports the truthiness check, but not its replacement.
  Use the applicable check:
  - For a string, `hasText(s)` (`@/lib/text`).
  - For a count, `n !== undefined && n > 0`.
  - For a boolean, `flag === true`.
  - For a `ReactNode` slot, `rendersContent(node)` (`@/lib/react-node`).
    Here, `0` renders and `false` does not.

  A `ReactNode` slot typed `ReactElement | undefined` needs none of these checks.
- **The one exception `jsx-a11y/no-autofocus` earns** is the first field of an overlay the user just
  opened. Suppress that case inline with the reason. Everything else is the bug the rule describes.
- **Before you trust or change an off rule's reason, re-derive its findings.**
  From the repository root, run `vp -C webapp lint -A all -D <rule> --report-unused-disable-directives-severity=off .`.
  `-A all` excludes the rest of the rule set from the result.
  The severity flag excludes suppressions that those excluded rules left unused.
  A nested `overrides` entry still applies.
  Thus, a rule that an override disables for some files stays off there.
- **An `off` entry pins a decision and carries its reason.** The root `.oxlintrc.json` header owns the base policy.
  If a category enables the rule, explain why it does not apply here.
  Otherwise, explain why a rule that someone could select is wrong here.
  Thus, the decision survives a category change in an oxlint release.
  If this tree disables a rule enabled by the base, name the different constraint, not a preference.
  Examples are the browser target and the React Compiler.

## Which admin console a component belongs to

`src/components/admin/**` holds two different consoles, and most directories serve both — `usage`,
`audit`, `practices`, `practice-editor` are imported by instance and workspace routes alike. Only
the two LLM consoles have a directory each, `admin/instance-llm/` and `admin/workspace-llm/`. So
the **component name** is what carries the scope:

- `Admin*` or `Instance*` — instance-wide. Configures the deployment for every workspace.
- `Workspace*` — scoped to one workspace.
- **Unprefixed — shared by both, so it must not assume a scope.**
  An `Admin*` dialog *and* a `Workspace*` dialog each render `LlmConnectionFields` and `LlmModelFields`.
  Adding a scope-specific field, permission check, or copy string to either component silently breaks the other console.
  Nothing in the file says it has two callers.

Name a new component for its scope.
Before you edit an unprefixed component, check its caller list.

A split into `admin/instance/` and `admin/workspace/` was considered and rejected.
The shared components in the third bullet have no scope.
A split would still need a `shared/` directory.
It would move every file in the tree without making the path identify the scope.
The prefix identifies the scope and appears in imports and the Storybook sidebar.

## Container/presentation split

- **Routes** (`src/routes/**`): data fetching, loaders, auth guards, side effects.
- **Hooks** (`src/hooks/use-*.ts`): a route's data fetching.
  Extract it when more than one route needs it, or when the fetching code has outgrown the route.
- **Components** (`src/components/**`): presentational, with no exception for a "cohesive section".
  They take their data as props and never import the query layer.

`scripts/check-presentational-components.ts` enforces it, and `vp run check` runs it. Two halves,
because they fail differently:

- **A component may not import the generated query layer or call a TanStack query hook.**
  `@/api/types.gen` is pure types and stays allowed everywhere.
  Put `useQuery` in the route file.
  Pass plain props down.
  If two routes need the same call, move it to `src/hooks/use-*.ts`.
- **A story file may not install MSW handlers.** Autodocs mounts every story of a file into one document.
  `msw-storybook-addon` installs on a single global worker.
  Thus, the last story's handlers answer for the whole page.
  One error story silently breaks its siblings' Docs page.
  Every isolated story, test, and snapshot still passes.

The allowlist inside that script is **shrink-only** — an entry that scans clean fails the build, so it
cannot go stale. Fix the file, do not add to it.

**A story renders with no network at all.**
A story proves that the component renders what it receives.
A wire contract is not a rendering fact and does not belong in a story mock.
Examples include which search params a filter produces and whether a facet reaches the query key.
Assert the contract in a route test, which owns the query.

Storybook is where you inspect the component.
The route test checks what it requests.

## Seeding a form from props

Put the form body in its own component.
`key` it on the subject being edited.
Thus, a subject switch remounts it with fresh initial state.
Never copy props into state from an effect.
Between the prop change and the effect, the form shows the *previous* subject's values under the new subject's title.

## Data fetching

Spread the generated options. Never hand-write a `queryKey`:

```typescript
const documentQuery = useQuery(getDocumentOptions({ path: { id } }));
queryClient.setQueryData(getDocumentQueryKey({ path: { id } }), updated);
```

Do not duplicate loading or error state in local state.
Do not hand-roll fetch mocks in tests.
MSW supplies the network (`src/mocks/handlers.ts`, installed by `src/test/setup-msw.ts`). A bare `fetch` or
`XMLHttpRequest` fails the build under `src/**`. The handful of calls that are not server data are
suppressed at the call site with their reason.

| State | Where |
|---|---|
| Server data | TanStack Query |
| Form state | React state / controlled components |
| URL state | TanStack Router search params |

## React Compiler

`vite.shared.ts` turns it on for every build of app source, so `useMemo`, `useCallback`, `memo` and
`forwardRef` are not imports you may take from `react` — the import line says so and why.

**A `useMemo` that survives is load-bearing, and deleting it breaks something that still type-checks.**
Only three cases justify a suppression.
Each names its case on the line above the import:

1. The value is a **dependency of an effect**.
   Its identity determines whether the effect reruns.
2. A library that the component uses **opts it out of the compiler**.
   Thus, nothing memoizes for it.
   `react/incompatible-library` identifies this case where possible.
3. A **library keys a cache on the value's identity**.
   The compiler memoizes this value as an optimization, not a promise.

TanStack Table is the third case.
`useTable` compares `options.columns` and `options.data` with `!==`.
If either changes, it rebuilds the column model and every downstream row model.
Thus, a table here memoizes them by hand.
The compiler exists to remove every other memo.

## The time of day

**Reading a clock or the RNG during render fails the build.**
A render that reads a moving value never gives the same answer twice.
Two components mounted in one commit disagree, and a story or snapshot never repeats.
Only these two readings are permitted:

- **Component code takes the time from `useNow`** (`@/components/common/use-now`).
  Every subscriber on the page shares this ticking `useSyncExternalStore` clock.
  Thus, a phrase derived from it ages instead of freezing at mount.
  It intentionally returns milliseconds, not a tick count.
  A counter is not an input to the phrase.
  Thus, the compiler would memoize the phrase and leave it unchanged on screen while the counter ticks.
- **A story takes it from `STORY_NOW` and the relative helpers beside it** (`@/stories/story-clock`).
  These are read once per module load.
  A hard-coded literal is not an alternative.
  As the calendar moves, it becomes "8 months ago".
  It puts every "expires in …" branch permanently in the past.

`hephaestus/no-nondeterministic-render` reports a reading at module scope or inside a component or hook.
It intentionally ignores readings in event handlers, effects, and `useState` initializers.
The AST can find the nearest enclosing function, but not its callers.
Reporting those readings would be incorrect for most of them here.

A separate rule makes `Date.now` unavailable everywhere under `src/**`.
Thus, the two rules intentionally differ for a handler: `new Date()` is permitted there, but `Date.now()` is not.

## Name a component for the concept, not for the wire

A component, its file, its props type, and its story title all name the same concept.
Use the name in `docs/contributor/practice-feedback-language.md`.
It is the product's word, not the server's or the screen's previous word. The unit a review detects is an **observation**. The unit delivered
to a developer is **feedback**. Neither is a "finding" or a "message".

Changing only the copy is not sufficient.
The next component inherits a name that stays in the import list.
If a concept changes its name, update these surfaces in this order:

1. Wire contract.
2. Route.
3. Component file.
4. Exported symbols.
5. Props type.
6. Story title.
7. Story export names.
8. Copy.

If you change only the copy, old vocabulary remains where a search for screen text will not find it.

## Component API and Storybook

Both live in the **`storybook-components` skill** (`.claude/skills/storybook-components/`). Load it
when you design a component's props, write or review a `*.stories.tsx`, or grade a webapp diff. It has
a routing table on the front page. Its `RUBRIC.md` is the grading instrument for review.

## Routing

Declare routes with `createFileRoute`. Keep loaders side-effect free and prefer
`context.queryClient.query(...)` with the generated options. Put shared data (query client,
auth) on the router context. Never hand-edit `routeTree.gen.ts` — there is no `tsr` CLI here. The
TanStack Router Vite plugin regenerates it when the dev server runs.

The route's `workspaceSlug` is the only source for workspace-scoped data. There is no store. The app
chrome renders on routes whose URL names none, so it reads `chromeWorkspaceSlug`, which falls back to
the first workspace.

## Role-based gating (OWNER > ADMIN > MEMBER)

Client-side gating is a UX affordance only — the server enforces authorization on every endpoint.
Never re-invent `role === "ADMIN"`. Use the shared pieces:

- **Whole admin surfaces — gate by placement, not by a check.** Put workspace-admin pages under
  `src/routes/_authenticated/w/$workspaceSlug/admin/`. Its `route.tsx` layout carries the `beforeLoad`
  role guard, so every route in the directory inherits it.
  A file that maps to an `/admin` URL outside that layout silently skips the gate.
  `admin/-route.test.ts` detects that defect.
  It drives every admin URL in the generated route tree through the real router as a MEMBER.
  The leading `-` marks the file as a test, not a route.
  Do not weaken it.
- **Individual controls**: `useWorkspaceAccess()` returns `role` and `isAdmin`. The role math is
  `hasMinimumWorkspaceRole` (`src/lib/workspace-roles.ts`).
  Pure role predicates live in `src/lib/`.
  QueryClient-coupled resolvers (`resolveWorkspaceMembership`, `workspaceMembershipQueryOptions`) live in `src/runtime/auth/guard.ts`.
  Fetch membership only through `workspaceMembershipQueryOptions`.
  Thus, every caller shares one cache entry and one `staleTime`.
- **Hide rather than disable** — specifically for permissions.
  If the user could still unlock a control, disabling it is the better default.
  The source recommends hiding a feature that a role or license prevents the user from ever using:

  <!-- vale STE.SentenceLength = NO -->
  <!-- vale STE.ProcedureLength = NO -->

  ["hiding is recommended in cases where the user will never be
  able to use that feature due to their role or license"](https://www.uxtigers.com/post/inactive-buttons)

  <!-- vale STE.ProcedureLength = YES -->
  <!-- vale STE.SentenceLength = YES -->

  That is the case here.
  A disabled control is also a poor explanation.
  A native `disabled` button is unreachable by keyboard, so the user cannot read its explanation in a tooltip.
- **Workspace role ≠ instance role.** `useWorkspaceAccess().isAdmin` is membership in *this* workspace.
  `useAuth().isAppAdmin` is instance-wide (ADR 0017). A workspace-role gate on a surface with no active
  workspace is always false.
- There is **no `<RequireRole>` wrapper component**.
  Route placement covers whole surfaces.
  For the rest, the route reads a boolean once and passes it down.
  From the practice profile route, `canAdminister` reaches `RefusalFixLink` through three components:
  - `PracticeGroupDetailDrawer`.
  - `ProfileReviewLevel`.
  - `TraceSignalTimeline`.

  These are three hops for one leaf.
  Copy the shape of the refusal beside it instead.
  The route renders `TraceRefusalAlert` and passes it down as a node.
  Before you pass a flag further down, render the gated leaf in the route.
  Pass it down as `children`.
  When a role-assignment UI arrives, its mutation must invalidate the membership query key.

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
The tree has two: `HephIcon` and the landing scene.
Each holds one or more of these:

- `@keyframes`.
- A generated `::before`.
- A `clip-path`.
- A grid whose placement descendants override at a breakpoint.
- `HephIcon`'s size, which a container's `[&_svg]:size-4` would outrank as a utility.

Anything that a utility can express stays a utility.
A module rule for one `letter-spacing` or `margin` is a utility in the wrong file.
It silently outranks the utility it duplicates.
Vite emits module CSS unlayered, while Tailwind sits in `@layer utilities`.

That inversion is the whole cost of the second system.
**Own a property in one place or the other, never both.**
If the module changes a margin at a breakpoint, that margin belongs to the module at every width.
It does not belong to `mt-8` at one width.

### Practice surfaces palette

The Practice profile, levels over it, and feedback cards share one 60-30-10 palette.

**60, grounds**: `bg-background`, `bg-card`, and `bg-sidebar`.
Nothing else paints a ground.

**30, structure**: `border` and `text-muted-foreground` carry lines, pills, rails, and secondary text.

**10, the accent**: `mentor` marks only what should attract attention.
Use at most one `variant="mentor"` button per surface.
The accent marks new content, the current selection or sort, and links on hover or focus.
On Practices across the workspace, it also marks the reader: the **You** marker on a bar and the pin on a tile.

A link is plain text at rest, except a count that opens its list.
That count uses `InlineLink tone="count"`, with a faint `decoration-border` underline at rest.
Thus, a row of figures shows which ones open.

Status colors (`success`, `warning`, `destructive`) convey meaning, not accent.
They reach a surface only through the registries.
This includes their badges and icons, and the rings, meters, and pressed responses drawn from them.
Never use them as colored prose.

A card has a wash in exactly two states: new, in the accent, and resolved by the work, in `success`.
A card that the reader marked as addressed or not applicable closes on the neutral ground.
`success` is the only status color that paints a surface.

## Testing

`getByRole` > `getByLabelText` > `getByText`, and the ladder ends there: a `data-testid` skips past the
accessible name a screen reader needs anyway.

**`@testing-library/jest-dom` is not registered in the Vitest unit project** — `vite.config.ts` lists
one setup file, `./src/test/setup-msw.ts`, and it does not import the matchers. So
`expect(el).toBeInTheDocument()` in a `*.test.tsx` throws `Invalid Chai property` at runtime with `tsc`
perfectly happy. `vitest/no-restricted-matchers` catches the common ones before you run anything and
names the replacement in its message. For one it does not list, assert on the plain value.

The matchers **are** available in stories, because `expect` from `storybook/test` ships them. Copying
an assertion out of a story into a route test is exactly how this bites.

A test that holds a response open until an assertion has run uses `deferred()` from `@/test/async`
— `Promise.withResolvers()` in all but name, for the reason beside `"lib"` in `tsconfig.json`.
`pending()`, `sleep()`, and `nextFrame()` sit beside it.
`ObserverStub` (`@/test/observers`) replaces jsdom's missing `IntersectionObserver`/`ResizeObserver`.
`precedes()` (`@/test/dom`) reads document order without spelling the bitmask.

## Type checking

**A dot-directory is invisible to a `**/*` include.** `tsconfig.json`'s `**/*.ts` does not match
`.storybook/`, which is why the directory is named explicitly in `include`. A new dot-directory
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
  Fixtures served through **MSW** are JSON and use ISO strings. Type those with `Wire<T>` from
  `@/lib/dates`, which turns every nested `Date` in a generated view into a `string`.
- **`asDate()`** (also `@/lib/dates`) takes `Date | string | null | undefined` and returns a `Date` or
  `undefined` — never an Invalid Date, which renders as the literal text "Invalid Date".
  Use it for optional fields.
  Also use it when a timestamp can reach a component as a string despite its `Date` type.

`transformer: true` is load-bearing and invisible to `tsc`: without it the types still say `Date` while
the client hands back strings, so `.toLocaleDateString()` crashes at runtime.
`src/test/response-transformers.test.ts` is the guard. It calls the real SDK, so it fails whenever that
wiring is lost.

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

Mount it closed and open it a frame later (`DetailDrawerStack`'s `useArrived`). An effect is
not enough, because a passive effect can be flushed before the browser paints the closed state.

## Search params that are UI state

A filter, a toggle, an open panel on the page you are already on is not a navigation. Write it with `useSearchState` (`lib/search-params.ts`), which passes `resetScroll: false`.
The router resets scroll on **every** commit, including a search-only one.
Without this option, a control halfway down a long page throws the reader back to the top.

## Vocabulary

`docs/contributor/practice-feedback-language.md` is normative, and a word it retires is retired in
code, types and story titles as well as in copy. Prefer dropping the class noun where the thing's own
name is already on screen: "No practices here", not "No practices in this group".

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
