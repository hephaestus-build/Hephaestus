# Chrome extension

A Chrome MV3 extension (WXT, React 19, Tailwind 4) that puts a practice review report into the page
of the pull request, merge request or issue open in the current tab, and previews one row of a
repository's list on request. Desktop Chrome only. The architecture record is ADR 0046; operator and
user docs are `docs/admin/browser-extension.mdx` and `docs/user/browser-extension.mdx`.

## Where things live

| Path | What |
|---|---|
| `src/entrypoints/` | WXT entrypoints: `background.ts` (service worker), `provider.content.ts` (runtime-registered content script), `inline/` (the report frame), `action/` (the confirmation window) and `options/` pages |
| `src/background/` | Everything with a credential or a network call: session store, generated-client calls, sign-in, context resolution, pending review actions, site access |
| `src/content/` | Provider report slots and list-row hooks (`anchors.ts`), the report's generic host around its frame, and the list rows' inspect control; no data, no RPC |
| `src/shared/` | Pure contracts: the RPC union and sender policy, the provider URL grammar, instance URL rules, polling |
| `src/components/` | Presentational components with stories; data arrives as props |
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
| `test:e2e` | Playwright; root `test:extension:e2e` builds the bundle first |
| `generate:api` | Regenerate `src/api` |

`vp run check:extension` runs the tree's gates: type-aware lint and types (`vp -C extension check`),
format, and the shared presentational-component and story-prose scanners. `tsc -p tsconfig.json` is
the type check on its own. Lint holds tests to the web app's Vitest policy: narrow with
`src/testing/required.ts`, and move a branching page or mock callback to module scope.

## Invariants — each one is a security boundary

- **Only the worker holds credentials and talks to the network.** Tokens live in `storage.session`
  with `TRUSTED_CONTEXTS`; `storage.local` holds the instance preferences only. Every
  call is `credentials: "omit"` with a bearer. No view or content script may `fetch`.
- **Messages are a closed union** (`src/shared/rpc.ts`), checked for shape and then for sender
  (`sender-policy.ts`): only options may change extension state; the report frame may only read about
  its own tab and ask for the confirmation window; a content script may send nothing. No method or
  header field; the worker derives the work from the sender's tab at request time, and checks every
  record it returns is about that exact work.
- **A list row is a selector, never an authority.** A preview frame names its row's work in its URL
  (`WORK_PARAMETER`), which the page can forge. The worker accepts it only on `get-context` and
  `get-work-feedback`, only as a canonical work address of the repository and kind of the list the
  tab actually shows, and re-reads the tab after every await: another list, filter or page is stale.
  Observations and review requests stay the work page's own.
- **A review change is confirmed only in the extension's own window.** The provider page can cover or
  move the report frame, so the frame never sends one. `action.html` is a top-level page that is not
  web-accessible; the worker binds one pending intent to the session generation, the source tab and
  work, and the confirmation tab it created, consumes it atomically, and sends at most once.
  An unanswered request is reported as unknown, never retried. Asking for a review is the only change;
  cancelling a run, retrying its results and approving feedback stay in the web app.
- **Nothing private enters the provider page.** The page gets a generic host whose collapsed height is
  the same in every state; everything the report says lives in the extension-origin frame. The page and
  frame exchange readiness, size and theme messages, correlated to one mounting. Nothing the page
  passes can open or configure the report: the reader's choices are kept by the worker per tab. A list
  row's preview shows at once, since the page could frame one itself, so it is one line that never
  wraps: its height follows the reader's font size, never what it says.
- **Only the reader's own records, and no feedback text.** Everyone, admins included, sees the
  comments Hephaestus recorded posting on the work for them (`getOwnDeliveredWorkFeedback`) — counted
  once per provider comment, linked only to an anchor on this work's own pages — and, once the report
  is open, their own observations. No feedback body is ever read or rendered; the comment is read on
  the provider. The provider's comment handles stay in the worker. Every developer's records, the
  review's delivery and its runs are the web app's, one **Review details** link away for an admin.
- **A generation guards everything shown.** Sign-in, sign-out, a rejected refresh, an instance change
  or a revoked site move it; late results are dropped and views reset in the same tick.
- **Links go to the web app origin, never the API server.** Locally they differ (bare Spring server vs.
  Vite); production serves the API under `/api` of the web app origin.
- **Production has no `key`, no loopback, no host permission until granted.** Check the built
  `manifest.json` after touching `wxt.config.ts`.
- **Never call `GET …/practices/feedback/in-app`** — reading it delivers feedback.
- **Lookups start only after the reader allows the exact site.** On an allowed site, each recognized
  work page's canonical address is sent to the instance when it opens; on a repository's list, only
  the row whose Hephaestus button the reader presses; no other page, and never page content. The
  onboarding, options, privacy docs and store listing say so.
- **List rows are found by the provider's own title hooks** (`LIST_TITLE` in `anchors.ts`), never by
  any link in a list item: a row can carry other links, and the page other lists. The inspect control
  goes after the title's heading, stops the row's own click and key handlers, and is reconciled on
  every sync, since providers reuse rows for other work.

## Vocabulary

Labels, icons and sentences come from the webapp's registries, imported by path through `@/`
(`webapp/src/components/practice-vocabulary/*-defs.ts`, `webapp/src/lib/artifact-kind-slugs.ts`). `@/api/*`
type-checks against this tree's own generated client. Do not copy a registry here; a missing one is a
webapp change. The glossary is `docs/contributor/practice-feedback-language.md`.

## End-to-end

The provider pages are fixtures of the real report slots (`e2e/provider-pages.ts`, mirroring
`src/content/anchors.ts`); the Hephaestus server is real. Follow
`docs/contributor/browser-extension.mdx` § Run the end-to-end suite locally for profiles, account
creation and seed order. CI uses `e2e,playwright` with the GitLab adapter enabled, so the suite needs
no review-runtime image. Without a server the server-backed project fails its readiness check; the
provider-page project can be run separately and makes no server or authentication claim.

A persistent Chrome profile can keep an old service worker running after the bundle on disk changes.
Prove a new build in a fresh, disposable profile; a matching file hash in a reused profile is not proof
of the code that ran.
