# Mobile

The Hephaestus app for iOS and Android: Expo SDK 57, React Native with the New Architecture and
Hermes, Expo Router with native stacks and native tabs, TanStack Query over its own generated Hey API
client. `ios/` and `android/` are generated from `app.config.ts` and never committed.
`docs/contributor/mobile.mdx` owns how to run, test and release it; this file is the gotchas.

Load `/react-native-skills` before native UI, list, gesture, animation or Expo module work, and only
the references it points you to.

## Commands

| Task | Command |
|------|---------|
| Lint + type check | `vp run gate:mobile` — oxlint runs type-aware with `typeCheck`, as in the webapp |
| Tests | `vp run test:mobile` — pure modules only |
| Native flows | `vp run test:mobile:e2e --platform ios\|android --flow smoke\|journey` |
| Metro | `vp run dev:mobile` |
| SDK-compatible versions | `vp run check:mobile:deps` |
| API contract | `vp run gate:mobile-api`; `vp run generate:mobile-api-contract` when a version ships |
| Look around on a device | Optional: Callstack's `agent-device` CLI, installed outside the repo — `docs/contributor/mobile.mdx` § Tests |

## What a native change carries

A new native dependency, config plugin or `app.config.ts` change moves the native fingerprint: rebuild
the development client on both platforms, and know that it reaches people only in a new binary,
never an update. Anything newly kept on the device, or newly sent to a third party, gets its line in
the personal-data map and the privacy notice.

## Composition

Routes under `src/app/` own navigation, server state, loading and side effects. Feature hooks carry
reused or substantial orchestration; pure policy modules stay independent of views. Components outside
routes take data and actions through props, including account, workspace and feedback UI. Generated
DTO types are welcome; fetching, credential access, workspace selection, navigation and the app's side
effects (opening links, reporting, push, storage, sending to Heph) are not, except that a view may place
its route's `Stack` header slot. Oxlint enforces these imports in `.oxlintrc.json`, and the native
canaries in `scripts/check-lint-contract.ts` prove representative bypasses fail. The one exception is
`src/ui/Markdown.tsx`, which owns the HTTPS-only link policy for untrusted Markdown and says so where it
imports the browser. Visual state, accessibility, keyboard geometry and animation stay with the
component that renders them.

Compose existing `Section`, `Row`, `QueryStates` and feature components through props and children.
Add a context only for state descendants actually share, never to disguise a query inside a visual
component. Do not copy the web renderer, DOM rules or compiler configuration into native.

## Things that bite

- **Hermes is not Node.** Vitest runs on Node, which has every `Intl` constructor; Hermes has no
  `Intl.RelativeTimeFormat`, and a missing constructor at module scope takes every route that
  imports it down at launch. Use date-fns for dates. Hermes also lacks ES2023's change-array-by-copy
  methods — `toSorted`, `toReversed`, `toSpliced`, `with` — so sort an array the code has just made
  (`unicorn/no-array-sort` is off here for that reason; no polyfill). Prove a new runtime API on a
  simulator, not in a test.
- **Credentials have one path.** Only `src/session/` reads the secure store. Authenticated requests
  go through the generated client that `authority.ts` wires, which attaches the bearer only for the
  signed-in instance's origin; unauthenticated ones use `publicClient`. Never cookies: requests set
  `credentials: "omit"`, because `expo/fetch` shares one cookie jar.
- **A session change replaces the query cache.** Use `activeQueryClient()` or the hook, never a
  `QueryClient` held across renders from before a sign-in. Query keys already carry the base URL.
- **Opening in-app feedback delivers it.** Only the Practice feedback list reads it, while it is on
  screen, and a feedback page opened directly reads it once when the list was not loaded: no prefetch,
  no background refetch, no count or preview on the practice profile, nothing from a notification.
- **An answer names feedback the app loaded.** The observation page answers only the
  `feedbackResponse.feedbackId` its loaded observation carries, never an id from a route; the server
  checks ownership again. Once an answer changes, `useFeedbackAnswer` reads the workspace's observations
  and review runs again, wherever it was given; never the in-app list, whose read delivers feedback. A
  204 means "no answer" by its status: `expo/fetch` gives even an empty response a body stream.
- **Long lists virtualize.** A group's or practice's page previews two review runs; the whole history
  is its own `FlatList` route. Never append pages of a list inside `Screen`'s scroll view.
- **`src/api/` is generated**, from only the operations in `api-contract/operations.json`: a new
  call is a new entry there, then `vp run generate:api:client`, which regenerates this client and the
  webapp's. `importFileExtension: null`, because Metro resolves an import by the exact file it names.
- **Isolated installs.** A package the app imports must be its own dependency; `tsconfig.json`
  `paths` only point the type checker at peers React Native packages import without declaring.
  `expo start` and `expo install` may rewrite `tsconfig.json` or bump a pin past the lockfile's
  release-age window: run `vp run format`, keep pins exact and let `vp install` decide.
- **Metro under `CI=1` does not watch.** Restart it after a change, or run it without `CI`.
- **Colors.** `usePalette()` in `src/ui/theme.ts` gives platform semantic colors, which native
  markdown cannot resolve: `src/ui/Markdown.tsx` keeps its own light and dark hex values, so a theme
  change touches both. `primary` (the label colour) fills the one committing control — send, the
  main button, a chosen option; `accent` blue is for the tab tint, links, action rows and Heph's mark.
  Row icons are outline SF Symbols in the row's text colour, never coloured tiles; a filled or
  badged variant claims a state (selected, unread) the row does not have. Tab symbols are filled. A
  symbol beside text passes `withText` to `Icon` so it grows with the text size; one in a fixed-size
  control does not.
- **Sheets close with Close, commit with Done.** A sheet whose settings apply as they change (Account)
  has the system Close (`xmark`); Done belongs to a sheet that completes a task, paired with Cancel.
- **A conversation follows a reply only while its end is in sight.** `TranscriptView` passes
  `maintainScrollAtEnd={following}`. `follows` in `heph/conversation-end.ts` owns the rule: in sight
  resumes following, and only the reader's own drag or fling (`onScrollBeginDrag` to
  `onMomentumScrollEnd`) that takes the end out of sight stops it, so the list's own scrolls and the
  keyboard's never shake it off. LegendList's distance-from-end gate measures against the whole frame, so
  its threshold is opened wide and `following` decides. `maintainScrollAtEnd` is the one owner of
  following: Send and Send again only set `following` before the message is appended, and the append
  and every later layout scroll to the end against the current insets. Jump to latest sets it and makes
  the one explicit, plain `scrollToEnd`. The keyboard lifts content only `whenAtEnd` (never in an empty
  conversation, whose intro would rise under the header). Never freeze keyboard-controller (`freeze`,
  LegendList's `useKeyboardScrollToEnd`) while the keyboard can move: a frozen list drops keyboard
  events rather than deferring them, so the keyboard returning after an alert leaves the inset without
  it and the end out of reach. No scroll timers, frame-deferred reveals or second scroll on send.
- **"Is the end in sight" is ours to measure.** LegendList's `isAtEnd` and keyboard-controller's
  `onEndVisible` both judge against the whole frame, so a reply running on under the keyboard counts as
  seen. `heph/conversation-end.ts` judges against what the composer and keyboard cover, from the scroll
  view's own `onScroll`, `onContentSizeChange` and `onLayout`; it drives following and "Jump to latest". The chat list owns its edges: `contentInsetAdjustmentBehavior="never"`,
  the header as content padding and the home indicator as a static `contentInset`, because the
  libraries' insets never include iOS's automatic ones and the end would stay out of reach by that much.
- **How a reply ended has two records.** The chat's `onFinish` reports `isAbort` and `finishReason` at
  once; the server's record (`metadata.status`, `metadata.finishReason`, `metadata.error`) may be read
  back later, and wins where it says something. `completed` does not mean finished: a turn that ended in
  `error` is stored as `completed`. `interruptionOf` owns the policy — stopped, failed (`error`), or
  ended early (`length`, `content-filter`) — and the notice never shows the provider's error text.
- **Sending again is a new turn.** The server accepts each user message id once, so the AI SDK's
  `regenerate`, which resends the last user message under its id, is refused. "Send again" sends the
  question text as a new message through the same path as a typed one (`startTurn`), and appears only
  when `questionToResend` finds a question.
- **Feedback in a reply is shown, or the server is wrong about it.** The server records conversation
  feedback as delivered once a completed reply showed its words, which travel only in the reply's
  `data-observation` parts, never in its text. `textOf` in `heph/transcript.ts` shows them among the
  reply's text in part order, checked as the web's `shownFeedbackText` checks them (an observation id
  and text that is not blank), for streamed and stored replies alike. The bubble and a report of the
  reply both read `textOf`; there is no second rendering path.
- **Markdown is untrusted.** Feedback and Heph's replies render only through `src/ui/Markdown.tsx`,
  which turns images into links and HTML into text before the native renderer, which would load an
  image the moment it draws it, sees them.
- **SF Symbols stop at 4.2.** The native target is iOS 16.4, where a newer symbol draws nothing;
  `src/sf-symbols.d.ts` narrows every `SFSymbol` name (`Icon`, toolbar items, tab icons, header items)
  to SF Symbols 4.2 through `sf-symbols-typescript`'s `Overrides`, so the type checker rejects a newer
  one. Raise it with the deployment target, never around it.
- **Icons go through `src/ui/Icon.tsx`**, never `SymbolView` directly. On Android expo-symbols draws
  the symbol as text in a fixed box, so at large text sizes the glyph outgrows the box and is cut off;
  `Icon` sizes the box with the font scale and hides the icon from screen readers, because the text
  beside it already says what it means.
- **Heph speaks only from data.** Mentor wording on practice feedback and the Heph tab comes from
  `src/heph/mentor-voice.ts`, shown through `HephSays` and only where `speaks(access)` holds. Where Heph
  introduces itself, it says it is an AI mentor. It never interprets feedback or claims to have written
  it, guesses where it came from, claims something is new or unread, praises, reads an empty list as good
  work, calls the AI service or sends anything, and it never holds back feedback while its own access is
  looked up. Settings, errors, permissions and deletion keep the system's precise voice. Headings are
  sentence case.
- **States are not optional.** A screen goes through `QueryStates` or `StateView` and distinguishes
  loading, empty, failed, and stale data that could not be refreshed.
- **Tests.** Pure logic in `*.test.ts` beside it; screens are proven by the Maestro flows on real
  builds. There is no Jest or component renderer here.
