---
title: Accessibility audit record, 2026-10-03
description: Dated evidence for the WCAG 2.2 AA evaluation of the Hephaestus web application. Automated and keyboard checks are complete; screen readers are not tested.
---

# Accessibility audit record, 2026-10-03

Filled from the [record template](./accessibility-audit-record-template.md), following the
[audit plan](./accessibility-audit-plan.md).

Outcome: partial. Every route was scanned with axe in both colour schemes and walked with Tab in
Chromium, Firefox and WebKit, and three complete processes were run by keyboard alone. Nobody has run NVDA or
VoiceOver, so every screen-reader cell below is `Not tested` and this record supports no WCAG 2.2 AA
conformance claim. The [accessibility statement](/user/accessibility) says so.

The evaluation found 16 barriers (F1 to F16) and one gap in the test coverage (F17). Each is fixed in
the same change, and each has a story, a test or a check in the accessibility spec that fails without
the fix. Findings are tracked in
[#1601](https://github.com/hephaestus-build/Hephaestus/issues/1601).

## Audit

| Field | Value |
| --- | --- |
| Dates | 2026-10-03 |
| Revision and release | `main` at `20dd2fca7`, which includes the instance overview readiness card ([#2416](https://github.com/hephaestus-build/Hephaestus/pull/2416)), plus this change; the code evaluated is commit `83a841624`. No release: the version string is `0.0.0-development`. |
| Scope and sample rationale | Every route in the generated route tree that renders a page of its own, 51 in all. Redirect-only routes (`/landing`, `/w/:slug`, `/w/:slug/user/*`, `/w/:slug/admin/onboarding`) and the transient `/auth/callback` have no page to evaluate. The structured sample is therefore the whole route tree and no random sample was drawn. States that need data the evaluation deployment did not hold are listed as `Not tested` below. |
| Deployment and test data | The production build of the SPA (`vp build`, served by `vp preview`) against the server run with the `e2e` profile and an empty PostgreSQL database seeded by `webapp/e2e/seed.sql`: one workspace, the shipped practice catalog, no synced work, no review runs and no configured language-model provider. Accounts: `e2e` (instance administrator and workspace administrator) and `e2e-plain` (workspace member). |
| Evaluator | Scripted checks only: `webapp/e2e/accessibility.spec.ts`, the Storybook suite and a one-off member-role scan. No person used an assistive technology. |
| WCAG-EM report | Not produced. The report needs screen-reader results for its sample, and there are none. |
| Storybook report, revision and browser | `vp run --filter webapp test:storybook` at `83a841624`, Chromium 153.0.8010.12 with reduced motion: 313 files and 2,155 tests pass. axe-core 4.13.0 at `error` for every story. |
| Finding query | [Issues and pull requests that mention #1601](https://github.com/hephaestus-build/Hephaestus/issues?q=%231601). |

## Environments

| ID | Operating system | Browser | Assistive technology | Versions and relevant settings | Run |
| --- | --- | --- | --- | --- | --- |
| E1 | Ubuntu 24.04, Linux 6.8 | Chromium 153.0.8010.12 (Playwright 1.63.0), 1280×720 | None | axe-core 4.13.0 through `@axe-core/playwright` 4.13.0, tags `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`, `wcag22aa`, `best-practice`; light then dark scheme; the Storybook suite runs with `prefers-reduced-motion: reduce` | Automated, keyboard |
| E2 | Ubuntu 24.04, Linux 6.8 | Firefox 155.0 (Playwright 1.63.0), 1280×720 | None | The same spec and the same axe configuration | Automated, keyboard |
| E3 | Ubuntu 24.04, Linux 6.8 | WebKit 26.6 (Playwright 1.63.0), 1280×720 | None | The same spec and the same axe configuration. This is the WebKit engine, not Safari on macOS. | Automated, keyboard |
| E4 | Windows | Firefox | NVDA | Not set up | Not tested: no Windows machine or NVDA tester was available |
| E5 | macOS | Safari | VoiceOver | Not set up | Not tested: no macOS machine or VoiceOver tester was available |

## Findings

Each row is a failure the evaluation found. The *Fix* column names the code that changed; the
*Coverage* column names what fails without it. Everything is checked against the callers of a shared
component, listed where the component is shared.

| Finding | Surface | Criterion | Barrier | Fix | Coverage |
| --- | --- | --- | --- | --- | --- |
| F1 | 18 routes: landing, about, imprint, privacy, both sign-in pages, sign-in problem, consent, callback, create workspace (3), user settings, integration callback, teams, mentor, choose your AI, not found | 2.4.2 Page Titled (A) | The tab, the history and a screen reader's page announcement all said only "Hephaestus". The unsubscribe page used a different separator from every other title. | `pageHead` in `webapp/src/lib/page-title.ts` on each route; the unmatched-address page is now the `routes/$.tsx` route, because only a route can set a title | Every surface test asserts its exact title |
| F2 | Every page of the signed-in application | 2.4.7 Focus Visible (AA), 2.4.11 Focus Not Obscured (AA), 2.4.1 Bypass Blocks (A) | The skip link used `sr-only focus:not-sr-only`, which resets `position`, so a focused link fell into the page flow behind the fixed sidebar. A keyboard user pressed Tab and saw nothing. | `SkipToContent.tsx` is parked above the viewport and moves down on focus | Story `ShownAboveTheSidebar`; the skip-link test and the Tab walk on every surface |
| F3 | Account menu in the header | 4.1.2 Name, Role, Value (A), 1.3.1 Info and Relationships (A) | `Activity` and `Settings` menu items sat inside links, so axe reported `aria-required-parent` and `aria-required-children` and a screen reader met a menu whose items were not its children. | `Header.tsx` renders each menu item as the link (`render=`) instead of wrapping it | Story `AccountMenu`; the menu test |
| F4 | Practice setup, with 13 groups | 4.1.2 (A), 2.5.8 Target Size (AA) | A tooltip trigger, itself a button, sat inside each accordion trigger. Nested buttons are invalid, the badge was 20px tall, and its explanation was reachable by hover only. | `CatalogOriginBadge.tsx` is plain text; the sentence is written out on the practice in `CatalogOriginNote`. The badge has callers in `PracticeCatalog` and `WorkspacePracticePanel`, both checked. | Stories `WithCatalogProvenance` and `FromTheCatalog`; the Practices surface test |
| F5 | Every page with the sidebar (an axe `region` finding on every signed-in route) | 1.3.1 (A), 2.4.1 (A) | The sidebar was a stack of `div`s, so a screen reader's landmark list held no navigation. | `ui/sidebar.tsx` renders the panel as `nav` named "Primary", on desktop and in the mobile sheet. Its one caller is `AppSidebar`. | Story `RegularUser` asserts the landmark; every signed-in surface test runs axe with `region` on |
| F6 | Practice setup, practice catalog, person data, instance overview (the readiness card from [#2416](https://github.com/hephaestus-build/Hephaestus/pull/2416), merged while this change was open, had the same defect) | 1.3.1 (A) | An accordion trigger is a heading, and Base UI fixes it at level 3, so each sat directly under the page's level-1 heading. The shared empty state fixed its title at level 3 as well. | `AccordionTrigger` takes `headingLevel`; `EmptyState` and `NoWorkspace` take a required `headingLevel`. Callers: `SortableCatalogTree` (practice setup and instance catalog), the readiness card, `InstancePersonDataPage`, the mentor routes and the six no-workspace pages. | Stories `Populated` and `ActionRequired` assert the levels; the surface tests run axe `heading-order` |
| F7 | No-workspace page, Heph conversation page | 1.3.1 (A), 2.4.6 Headings and Labels (AA) | The page had no heading at all. | `NoWorkspace` is a level-1 heading on a page of its own; the conversation routes carry a visually hidden level-1 heading | Story `Default` of `NoWorkspace`; the mentor thread route tests; the sign-in process test runs axe on the no-workspace page |
| F8 | Practice profile, instance audit log (any tab panel with nothing focusable inside) | 2.4.7 Focus Visible (AA) | Base UI makes such a panel a tab stop, and upstream's `outline-none` left it with no sign of focus. | `ui/tabs.tsx` draws a focus ring on `TabsContent`. Callers: seven components in the practice profile, review and feedback screens, all checked. | Story `KeyboardReachesThePanel`; the Tab walk on every surface |
| F9 | Every signed-in page (sidebar mark), the landing page | 2.2.2 Pause, Stop, Hide (A) | The Heph mark in the sidebar floated, wobbled and blinked without end, and the landing figure beside its text floated without end. | The idle motion in `HephIcon.module.css` and `LandingVisuals.module.css` plays once and ends inside five seconds. Only the streaming ping, which says Heph is working, repeats. | Every surface test reads `document.getAnimations()` for a loop that is not a spinner or skeleton |
| F10 | Every dialog with a scrolling body (create group, edit group and others) | 2.4.3 Focus Order (A) | Focus opened on the body, an unnamed scroll box and the dialog's first tab stop, instead of on the first field. | `ui/dialog.tsx` opens `DialogContent` on the first control in the body; a body with none keeps the default | Story `OpensOnTheName`; the dialog test and the group process test |
| F11 | Members | 1.4.10 Reflow (AA), checked at 640px as well as 320px | The toolbar of search, team filter and columns ran 37px past the window at 640 CSS pixels, the width of a 1280px window at 200% zoom. | `WorkspaceMembersTable.tsx` lets the toolbar wrap | Story `AtTwoHundredPercentZoom`; every surface test checks 640px |
| F12 | Every signed-in page, review observations | 1.4.10 Reflow (AA) | In Firefox the header ran 3px past a 320px window, and the filter toolbar's actions ran 7px past it. Chromium fitted both exactly. | `Header.tsx` narrows its gaps at small widths; `FilterToolbar.tsx` lets its actions wrap. The toolbar has eight callers. | Stories `Mobile` of `Header` (room to spare) and `ActionsAtReflowWidth`; the Firefox run of the spec |
| F13 | Sidebar workspace switcher | 1.4.12 Text Spacing (AA) | At the spacing the criterion sets, a workspace name ended in an ellipsis in the only place it is shown. | `WorkspaceSwitcher.tsx` lets the name wrap | Story `LongWorkspaceName` |
| F14 | Review settings, landing page, dark theme | 1.4.3 Contrast (AA) | The chosen autonomy rung's description was 4.19:1 on its tint, and the landing page's "Merged" pill was 3.88:1. | `AutonomyLadder.tsx` and `LandingVisuals.tsx` | Stories `FullInDarkMode` and `WorkStatesInDarkMode`; the Review settings surface test in dark |
| F15 | Every pressed outline button in the dark theme | 1.4.1 Use of Color (A), 1.4.11 Non-text Contrast (AA) | `dark:bg-input/30` outranked `aria-pressed:bg-muted`, so a pressed button looked like an unpressed one. | `ui/button.tsx` | Story `PressedTogglesLookSelectedInDarkMode` |
| F16 | Every page change | 4.1.3 Status Messages (AA), not confirmed | The page changes without a load, so a screen reader said nothing when a link was followed. This is a barrier by design review: no screen reader has confirmed it. | `RouteAnnouncer.tsx` speaks the new page's title through a polite live region and keeps focus where it is. It stays silent for the first page and for a change of search. | Unit test `RouteAnnouncer.test.tsx`; the announcer test in the spec. A tester must confirm what NVDA and VoiceOver say. |
| F17 | Dark theme, whole Storybook suite | 1.4.3 (AA) | Stories run in the light theme only, so a dark-theme defect passes unless a story asks for it. | The dark stories of F14 and F15. Every story was also run in the dark theme once, with `globals: { theme: "dark" }` added to each file's meta by a temporary edit that was not kept: 313 files and 2,155 tests pass. A first attempt through `initialGlobals` failed 79 stories; the 54 that were not contrast or F15 failed because the theme provider remounts a story when the global changes, and each passes with per-story globals. | The dark stories above |

One more result is a tool's, not the application's. axe 4.13.0 reports `scrollable-region-focusable`
for an ARIA menu taller than the window, because its items are `tabindex="-1"` and reached by arrow
keys. axe fixed this after 4.13.0 ([pull request 5364](https://github.com/dequelabs/axe-core/pull/5364)).
The spec filters that one result for `role="menu"` and says why beside the filter; remove the filter
when the axe pin moves.

## Surfaces

`Automated` is the axe run in the light and dark scheme, plus the page title, reflow at 320 and 640
CSS pixels, and looping motion. `Keyboard` is a Tab walk of the surface: order, focus indicator, focus
not entirely hidden, and no trap. `Pass` means it passed in E1, E2 and E3, the environments that ran it.
The generated rows ran as the instance administrator, who is also a workspace administrator, on a
database with no synced work, so a page that lists work shows its empty state. The evidence for each row is its test
in `webapp/e2e/accessibility.spec.ts`.

| Surface | Route, state and role | Automated | Keyboard | NVDA | VoiceOver | Evidence or issue |
| --- | --- | --- | --- | --- | --- | --- |
| Landing | `/`, signed out | Pass | Pass | Not tested | Not tested | Surface test `Landing` |
| About | `/about`, signed out | Pass | Pass | Not tested | Not tested | Surface test `About` |
| Imprint | `/imprint`, signed out | Pass | Pass | Not tested | Not tested | Surface test `Imprint` |
| Privacy | `/privacy`, signed out | Pass | Pass | Not tested | Not tested | Surface test `Privacy` |
| Sign in | `/login`, signed out | Pass | Pass | Not tested | Not tested | Surface test `Sign in` |
| Workspace sign in | `/w/e2e/login`, signed out, workspace sign-in | Pass | Pass | Not tested | Not tested | Surface test `Workspace sign in` |
| Sign-in problem | `/auth/error`, signed out, no error code | Pass | Pass | Not tested | Not tested | Surface test `Sign-in problem` |
| Unsubscribe | `/unsubscribe`, signed out, no token | Pass | Pass | Not tested | Not tested | Surface test `Unsubscribe` |
| Not found | `/no-such-page`, signed out; any unmatched address | Pass | Pass | Not tested | Not tested | Surface test `Not found` |
| New workspace | `/workspaces/new`, signed in | Pass | Pass | Not tested | Not tested | Surface test `New workspace` |
| New GitHub workspace | `/workspaces/new/github`, signed in | Pass | Pass | Not tested | Not tested | Surface test `New GitHub workspace` |
| New GitLab workspace | `/workspaces/new/gitlab`, signed in | Pass | Pass | Not tested | Not tested | Surface test `New GitLab workspace` |
| Integration callback | `/integrations`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Integration callback` |
| Settings | `/settings`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Settings` |
| Practice profile | `/w/e2e/practice-profile`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Practice profile` |
| Activity | `/w/e2e/activity`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Activity` |
| Workspace activity | `/w/e2e/workspace-activity`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Workspace activity` |
| Teams | `/w/e2e/teams`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Teams` |
| Mentor | `/w/e2e/mentor`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Mentor` |
| Onboarding | `/w/e2e/onboarding`, signed in | Pass | Pass | Not tested | Not tested | Surface test `Onboarding` |
| Members | `/w/e2e/admin/members`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Members` |
| Teams admin | `/w/e2e/admin/teams`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Teams admin` |
| Practices | `/w/e2e/admin/practices`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Practices` |
| Practice updates | `/w/e2e/admin/practices/releases`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Practice updates` |
| Review settings | `/w/e2e/admin/practices/review`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Review settings` |
| Practice reviews | `/w/e2e/admin/practices/reviews`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Practice reviews` |
| Review runs | `/w/e2e/admin/practices/reviews/runs`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Review runs` |
| Review observations | `/w/e2e/admin/practices/reviews/observations`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Review observations` |
| Review feedback | `/w/e2e/admin/practices/reviews/feedback`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Review feedback` |
| Reviewed work | `/w/e2e/admin/practices/reviews/work`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Reviewed work` |
| AI models | `/w/e2e/admin/models`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `AI models` |
| AI usage | `/w/e2e/admin/usage`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `AI usage` |
| Integrations | `/w/e2e/admin/integrations`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Integrations` |
| Source control | `/w/e2e/admin/integrations/scm`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Source control` |
| Outline | `/w/e2e/admin/integrations/outline`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Outline` |
| Slack | `/w/e2e/admin/integrations/slack`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Slack` |
| Workspace settings | `/w/e2e/admin/settings`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Workspace settings` |
| Audit log | `/w/e2e/admin/audit`, workspace admin | Pass | Pass | Not tested | Not tested | Surface test `Audit log` |
| Instance overview | `/admin`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Instance overview` |
| Instance users | `/admin/users`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Instance users` |
| Instance workspaces | `/admin/workspaces`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Instance workspaces` |
| View as user | `/admin/workspaces/e2e/users`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `View as user` |
| Instance audit log | `/admin/audit`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Instance audit log` |
| Practice catalog | `/admin/catalog`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Practice catalog` |
| Instance AI models | `/admin/models`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Instance AI models` |
| Instance AI usage | `/admin/usage`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Instance AI usage` |
| Instance settings | `/admin/settings`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Instance settings` |
| Login providers | `/admin/login-providers`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Login providers` |
| Feedback inbox | `/admin/feedback`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Feedback inbox` |
| Surveys | `/admin/surveys`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Surveys` |
| Person data | `/admin/person-data`, instance admin | Pass | Pass | Not tested | Not tested | Surface test `Person data` |
| Member role | The workspace member's pages (practice profile, activity, workspace activity, teams, mentor, settings), as `e2e-plain` | Pass | Not tested | Not tested | Not tested | A one-off scan in E1 in both schemes found nothing. The Tab walk was not run as a member. |
| Permission denied | A member opens an administrator route (`/w/e2e/admin/members`, `/admin`) | Pass | Not tested | Not tested | Not tested | The application redirects to the practice profile, which has a title and passes. It shows no message that access was refused. |
| Heph conversation | Greeting, transcript, composer, copilot panel | Pass | Not tested | Not tested | Not tested | Component stories only. The evaluation deployment has no language-model provider, so the live page shows "Heph isn't set up in this workspace yet". The conversation rebuilt in [#2389](https://github.com/hephaestus-build/Hephaestus/pull/2389) is not evaluated against a server. |
| Populated pages | Practice profile with feedback, reviewed-work feedback page, review runs, observations, traces, activity, teams, usage charts, audit entries, connected integrations | Pass | Not tested | Not tested | Not tested | Component stories only. The deployment held no synced work or review runs. |

## Complete processes

A process is complete when a person finishes it from the first step to the last, including the error
they make on the way. These ran by keyboard alone.

| Process | Roles and states | Environment | Success path | Error recovery | Evidence or issue |
| --- | --- | --- | --- | --- | --- |
| A new person signs in and accepts the terms | Signed out, then a new account | E1 | Pass | Not applicable: the dev sign-in accepts any name, and the checkbox keeps Continue disabled until it is ticked, so no step reports an error | Spec test "a new person signs in and accepts the terms". Sign-in through GitHub or GitLab leaves the application; that handoff and return are not tested. |
| A new person signs in and accepts the terms | Signed out, then a new account | E2 | Pass | Not applicable, as above | The same test |
| A new person signs in and accepts the terms | Signed out, then a new account | E3 | Pass | Not applicable, as above | The same test |
| Create and delete a practice group | Workspace administrator | E1 | Pass | Not applicable: Create stays disabled until the name is typed and deletion asks first. A failed request was not induced. | Spec test "a practice group is created, shown and deleted" |
| Create and delete a practice group | Workspace administrator | E2 | Pass | Not applicable, as above | The same test |
| Create and delete a practice group | Workspace administrator | E3 | Pass | Not applicable, as above | The same test |
| Share an idea with the instance administrators | Workspace administrator | E1 | Pass | Not applicable: Send stays disabled until there is a message. A failed request was not induced. | Spec test "an idea is sent to the instance administrators" |
| Share an idea with the instance administrators | Workspace administrator | E2 | Pass | Not applicable, as above | The same test |
| Share an idea with the instance administrators | Workspace administrator | E3 | Pass | Not applicable, as above | The same test |
| Create a workspace from a provider | Signed in | E1, E2, E3 | Not tested: the evaluation deployment has no GitHub App or GitLab login | Not tested | Only the pages before the provider are in the surface table |
| Respond to feedback on work | Signed in, with feedback | E1, E2, E3 | Not tested: no feedback exists in the deployment | Not tested | |
| Talk with Heph | Workspace member | E1, E2, E3 | Not tested: no language-model provider | Not tested | |
| Change settings and delete an account | Signed in | E1, E2, E3 | Not tested | Not tested | The settings page passes axe and the Tab walk; the destructive steps were not run |
| Every process above | Any role | E4, E5 | Not tested | Not tested | No screen reader has run any process |

## Remaining work

An AA conformance claim stays out of reach until every item here is closed.

NVDA with Firefox on Windows (E4) and VoiceOver with Safari on macOS (E5). On both, for the
surfaces in the table above and every process in the second table:

1. Open the landmark list and the heading list on each page. Expect one navigation named "Primary", a
   main region and a banner, and one level-1 heading.
2. Follow a sidebar link and listen. Expect the new page's title, once. Confirm the announcement from
   `RouteAnnouncer` is neither missing nor doubled with the screen reader's own.
3. Press Tab from the top of a page. Expect "Skip to main content" first, and the same order as the
   keyboard walk. Activate it and confirm the reader lands in the page.
4. Open the account menu, a dialog and the workspace switcher, and close each with Escape. Expect the
   name of the widget on opening and focus back on the control on closing.
5. On Practice setup, expand a group and move a practice with the Move up and Move down menu items.
   Expect each group announced as a level-2 heading and the move announced.
6. Open a tab panel with nothing in it, a table (Members, Audit log) and a toast.
7. Complete each process in the second table, including the steps it lists as not tested.

Surfaces the deployment could not show. Run the same checks, and the Tab walk, on a deployment with
synced work, review runs and a language-model provider: the populated rows of the surface table, a
Heph conversation (greeting, streaming reply with its status line, retry after a failure, the composer),
the reviewed-work feedback page, and the connected integration screens.

Roles. Run the keyboard walk as a workspace member, and complete the keyboard processes in each
role the application supports.

The record itself. Choose a random sample once the above exist and produce the WCAG-EM report.

## Observations that fail no criterion

- Several forms keep their submit button disabled until a required field is filled, and nothing says
  which field. A disabled native button leaves the tab order and says nothing. This does not fail an
  AA criterion, and a visible message on submit would be easier to recover from.
- The header environment badge (`Local`, `Staging`) is a tooltip trigger that a keyboard cannot reach.
  Its text is on the page, so the tooltip adds nothing that is not already there.
- The imprint and privacy pages render two level-1 headings when the operator has not configured the
  text. Headings are in order and no criterion limits their number.

## Repeating this evaluation

```bash
# Server (profile e2e), a database seeded with webapp/e2e/seed.sql, and a production build of the SPA
vp run dev:server:e2e
vp -C webapp build && vp -C webapp preview --port 4200

vp run --filter webapp test:e2e accessibility.spec.ts    # axe, keyboard and processes, Chromium
vp run --filter webapp test:storybook                    # axe on every story
```

The spec runs in the existing end-to-end job in CI, on Chromium. The Firefox and WebKit runs used a
throwaway Playwright configuration with a `firefox` or `webkit` project and the same file.
