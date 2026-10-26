# Webapp

React 19 SPA on TanStack Router/Query, Tailwind 4, shadcn primitives over **Base UI**
(`@base-ui/react`) — not Radix. Vitest + Storybook for tests, oxlint for lint and oxfmt for format,
Vite for the build. React Compiler runs at build time.

[DESIGN.md](DESIGN.md) owns the visual intent and the surface rules.
Read it before you change styling, a practice surface, a drawer level, a loading or error state, motion, a status badge, or a `Field`.

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
Running the registry again **overwrites** the file, so an edit is a fork that you reapply at every re-vendor.
Check a change against upstream first, and never make an incidental change while you work on something else.

A deliberate divergence is recorded in the file it lives in, so the next re-vendor restores it instead
of silently reverting a fix. `alert-dialog.tsx` and `dialog.tsx` head theirs with
`⚠️ Diverges from the shadcn registry` and enumerate what re-vendoring drops. Smaller ones are noted
where they sit, like `accordion.tsx`'s header sizing or `select.tsx` requiring `items` where Base UI
leaves it optional.

The payoff is that an upstream defect gets fixed here once, with the note attached, instead of being
worked around at every call site.

A missing registry `cn-font-*` marker is not a divergence: the CLI removes it because this project declares no `--font-heading`.

A variant with one caller is still a variant if it names a system axis: tone, size, shape, or edge.
`shadcn/no-restyle` forbids radius, spacing, or border composition on a primitive at the call site.
Upstream documents `rounded-full` or `border-dashed` as `className` composition.
Here, use `shape="pill"` and `variant="outlined"` instead.
Each axis has one home, not one per screen.

## Linting

oxlint lints and oxfmt formats.
Each restriction explains itself in its diagnostic at the call site.
`docs/contributor/local-verification.mdx` § oxlint traps has what the diagnostics do not say.
It explains how to suppress a rule and why a hooks suppression silences a whole component.

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

A split into `admin/instance/` and `admin/workspace/` was rejected: the shared components would still need a `shared/` directory.
The prefix identifies the scope in imports and in the Storybook sidebar.

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
It ignores event handlers, effects, and `useState` initializers, because the AST cannot see a function's callers.
A separate rule bans `Date.now` everywhere under `src/**`, so a handler may use `new Date()` but not `Date.now()`.

## Name a component for the concept, not for the wire

A component, its file, its props type, and its story title all name the same concept.
Use the name in `docs/contributor/practice-feedback-language.md`, which is normative.
A word it retires is retired in code, types and story titles as well as in copy.

The record of reviewing one practice for one item is an **observation**.
One located problem under a Not met observation is a **problem**.
What a developer receives is **feedback**.
Its § Observations records the one exception, for observation identifiers.

If a concept changes its name, rename in this order: wire contract, route, component file, exported symbols, props type, story title, story export names, copy.
A copy-only rename leaves the old word where a search for screen text will not find it.
In copy, drop the class noun where the thing's own name is on screen: "No practices here", not "No practices in this group".

## Component API and Storybook

Both live in the **`storybook-components` skill** (`.claude/skills/storybook-components/`). Load it
when you design a component's props, write or review a `*.stories.tsx`, or grade a webapp diff. It has
a routing table on the front page. Its `RUBRIC.md` is the grading instrument for review.

## Routing

Declare routes with `createFileRoute`. Keep loaders side-effect free and prefer
`context.queryClient.query(...)` with the generated options. Put shared data (query client,
auth) on the router context. Never hand-edit `routeTree.gen.ts` — there is no `tsr` CLI here. The
TanStack Router Vite plugin regenerates it when the dev server runs.

The route's `workspaceSlug` is the only source for workspace-scoped data. There is no store.

The app chrome reads `chromeWorkspaceSlug`: on global settings and instance-admin routes, the validated
`chromeWorkspace` search parameter preserves the selected member workspace. A path slug takes
precedence. An unknown member context selects none. Bare global URLs fall back to the first workspace.
Only those global destinations retain this search parameter. It never substitutes for scoped data.

## Role-based gating (OWNER > ADMIN > MEMBER)

Client-side gating is a UX affordance only — the server enforces authorization on every endpoint.
Never re-invent `role === "ADMIN"`. Use the shared pieces:

- **Whole admin surfaces — gate by route, not by a check.** Put workspace-admin pages under
  `src/routes/_authenticated/w/$workspaceSlug/admin/`. Its `route.tsx` layout carries the `beforeLoad`
  role guard, so every route in the directory inherits it.
  A file that maps to an `/admin` URL outside that layout silently skips the gate.
  `admin/-route.test.ts` drives every admin URL through the real router as a MEMBER to catch it. Do not weaken it.
- **Individual controls**: `useWorkspaceAccess()` returns `role` and `isAdmin`. The role math is
  `hasMinimumWorkspaceRole` (`src/lib/workspace-roles.ts`).
  Fetch membership only through `workspaceMembershipQueryOptions` (`src/runtime/auth/guard.ts`), so every caller shares one cache entry.
- **Hide rather than disable** — specifically for permissions.
  If the user could still unlock a control, disabling it is the better default.
  A control that the role never unlocks is hidden
  ([UX Tigers](https://www.uxtigers.com/post/inactive-buttons)).
  A native `disabled` button is also unreachable by keyboard, so the user cannot read its explanation in a tooltip.
- **Workspace role ≠ instance role.** `useWorkspaceAccess().isAdmin` is membership in *this* workspace.
  `useAuth().isAppAdmin` is instance-wide (ADR 0017). A workspace-role gate on a surface with no active
  workspace is always false.
- There is **no `<RequireRole>` wrapper component**.
  The route tree gates whole surfaces.
  For the rest, the route reads a boolean once.
  Do not pass that flag through several components to reach one leaf.
  Render the gated leaf in the route and pass it down as a node.
  The practice profile route does this with `TraceRefusalAlert`.
  When a role-assignment UI arrives, its mutation must invalidate the membership query key.

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

A `format: date-time` field comes back from an SDK call as a real `Date`: `scripts/api-generator/webapp.config.ts` sets
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

## Search params that are UI state

A filter, a toggle, an open panel on the page you are already on is not a navigation. Write it with `useSearchState` (`lib/search-params.ts`), which passes `resetScroll: false`.
The router resets scroll on **every** commit, including a search-only one.
Without this option, a control halfway down a long page throws the reader back to the top.
