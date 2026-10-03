# Chrome extension

This Chrome MV3 extension uses WXT, React 19, and Tailwind 4.
It puts a practice review report into the current tab's pull request, merge request, or issue page.
On request, it previews one row of a repository's list.
It supports desktop Chrome only. The architecture record is ADR 0049. Operator and
user docs are `docs/admin/browser-extension.mdx` and `docs/user/browser-extension.mdx`.

## Where things live

| Path | What |
|---|---|
| `src/entrypoints/` | WXT entrypoints: `background.ts` (service worker), `provider.content.ts` (runtime-registered content script), `inline/` (the report frame), `action/` (the confirmation window) and `options/` pages |
| `src/background/` | Everything with a credential or a network call: session store, generated-client calls, sign-in, context resolution, pending review actions, site access |
| `src/content/` | Provider report slots and list-row hooks (`anchors.ts`), the report's generic host around its frame, and the list rows' inspect control. No data, no RPC |
| `src/shared/` | Pure contracts: the RPC union and sender policy, the provider URL grammar, instance URL rules, polling |
| `src/components/` | Presentational components with stories. Data arrives as props |
| `src/views/` | Report, confirmation and settings containers that own queries and extension settings changes |
| `src/api/` | Generated from `server/openapi.yaml` by `generate:api` — never edit |
| `e2e/` | Playwright against the built `e2e` bundle, with fixture provider pages and `seed.sql` |

## Commands (from `extension/`, or `vp run --filter extension <script>`)

| Script | Does |
|---|---|
| `build` / `zip` | Production bundle `.output/chrome-mv3` / reproducible `.output/hephaestus-<version>-production-chrome.zip` |
| `build:dev` / `zip:dev` | Development bundle `.output/chrome-mv3-dev`: fixed id `ijkajblcbajjpjbknfgdiiiljipafiko` (public key in `dev-key.txt`), loopback HTTP allowed |
| `build:e2e` | `development` plus pre-granted fixture origins, for Playwright |
| `test` | Vitest unit project (`src/**/*.test.ts`) |
| `test:storybook` | Every story as a browser test, axe at `error` |
| `test:e2e` | Playwright. Root `test:extension:e2e` builds the bundle first |
| `generate:api` | Regenerate `src/api` |

`vp run check:extension` runs the tree's gates: type-aware lint and types (`vp -C extension check`),
format, and the shared presentational-component and story-prose scanners. `tsc -p tsconfig.json` is
the type check on its own. Lint holds tests to the web app's Vitest policy: narrow with
`src/testing/required.ts`, and move a branching page or mock callback to module scope.

## Invariants — each one is a security boundary

- **Only the worker holds credentials and talks to the network.** Tokens live in `storage.session`
  with `TRUSTED_CONTEXTS`. `storage.local` holds the instance preferences only. Every
  call is `credentials: "omit"` with a bearer. No view or content script may `fetch`.
- **Messages are a closed union** (`src/shared/rpc.ts`), checked for shape and then for sender
  (`sender-policy.ts`). Only options may change extension state.
  The report frame may read only about its own tab.
  It may ask for the confirmation window. A content script may send nothing. No method or
  header field. The worker derives the work from the sender's tab at request time.
  It checks that every returned record concerns that exact work.
- **A list row is a selector, never an authority.** A preview frame names its row's work in its URL
  (`WORK_PARAMETER`), which the page can forge. The worker accepts it only on `get-context` and `get-work-feedback`.
  It must be a canonical work address for the repository and list kind that the tab shows.
  After every await, the worker reads the tab again.
  Another list, filter, or page makes the request stale.
  Observations and review requests stay the work page's own.
- **A review change is confirmed only in the extension's own window.**
  The provider page can cover or move the report frame.
  Thus, the frame never sends a review change. `action.html` is a top-level page that is not
  web-accessible. The worker binds one pending intent to the session generation, source tab and work, and its confirmation tab.
  It consumes the intent atomically.
  It sends at most once.
  The worker reports an unanswered request as unknown.
  It never retries that request.
  A review request is the only change.
  Run cancellation, result retries, and feedback approval stay in the web app.
- **Nothing private enters the provider page.** The page gets a generic host whose collapsed height is
  the same in every state. Everything the report says lives in the extension-origin frame. The page and
  frame exchange readiness, size and theme messages, correlated to one mounting. Nothing from the page can open or configure the report.
  The worker keeps the reader's choices per tab.
  A list row's preview shows at once, because the page could frame one itself.
  Thus, it is one line that never wraps.
  Its height follows the reader's font size, never its text.
- **Only the reader's own records, and no feedback text.**
  Everyone, including admins, sees the comments Hephaestus recorded posting on the work for them (`getOwnDeliveredWorkFeedback`).
  The extension counts each provider comment once.
  It links only to an anchor on this work's own pages.
  When the report opens, readers also see their own observations and named own-review trace.
  No feedback body is ever read or rendered. The comment is read on
  the provider. The provider's comment handles stay in the worker. Every developer's records, the review's delivery, and its runs stay in the web app.
  An admin reaches them through one **Review details** link.
- **A generation guards everything shown.** Sign-in, sign-out, a rejected refresh, an instance change
  or a revoked site move it. Late results are dropped and views reset in the same tick.
- **Links go to the web app origin, never the API server.** Locally they differ (bare Spring server vs.
  Vite). Production serves the API under `/api` of the web app origin.
- **Production has no `key`, no loopback, no host permission until granted.** Check the built
  `manifest.json` after touching `wxt.config.ts`.
- **Never call `GET …/practices/feedback/in-app`** — reading it delivers feedback.
- **Lookups start only after the reader allows the exact site.** On an allowed site, each recognized
  work page's canonical address is sent to the instance when it opens. On a repository's list, only
  the row whose Hephaestus button the reader presses. No other page, and never page content. The
  onboarding, options, privacy docs and store listing say so.
- **List rows are found by the provider's own title hooks** (`LIST_TITLE` in `anchors.ts`).
  Never use any link in a list item.
  A row can carry other links, and a page can carry other lists.
  The inspect control goes after the title's heading.
  It stops the row's own click and key handlers.
  Every sync reconciles it, because providers reuse rows for other work.

## Vocabulary and shared components

Labels, icons and sentences come from the webapp's registries, imported by path through `@/`
(`webapp/src/components/practice-vocabulary/*-defs.ts`, `webapp/src/lib/artifact-kind-slugs.ts`), and
so do the brand marks and the `ui/spinner` and `ui/skeleton` primitives. `@/api/*` type-checks
against this tree's own generated client. Do not copy a registry or component here. A missing one is
a webapp change. The glossary is `docs/contributor/practice-feedback-language.md`.

Tailwind scans only this tree, so a webapp module whose classes the extension renders needs an
`@source` in `src/ui/styles.css`. Every webapp file the extension imports is an extension input, including indirect imports through another webapp file.
These inputs belong in `scripts/check-affected.ts` and both `extension` filters of `.github/workflows/cicd.yml`.
`scripts/check-affected.test.ts` derives the list from the imports and names a missing file.

## End-to-end

The provider pages are fixtures of the real report slots (`e2e/provider-pages.ts`, mirroring
`src/content/anchors.ts`). The Hephaestus server is real. Follow
`docs/contributor/browser-extension.mdx` § Run the end-to-end suite locally for profiles, account
creation and seed order. CI uses `e2e,playwright` with the GitLab adapter enabled, so the suite needs
no review-runtime image. Without a server the server-backed project fails its readiness check. The
provider-page project can be run separately and makes no server or authentication claim.

A persistent Chrome profile can keep an old service worker running after the bundle on disk changes.
Prove a new build in a fresh, disposable profile. A matching file hash in a reused profile is not proof
of the code that ran.
