# ADR 0104: The mobile apps are native SwiftUI and Jetpack Compose clients of the server API

**Status:** Proposed
**Date:** 2026-11-13
**Authors:** Hephaestus maintainers

**Builds on:** [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md) (installed clients), [ADR 0076](0076-reads-are-shaped-like-the-screen.md) (reads are shaped like the screen), [ADR 0100](0100-the-server-serves-the-web-app.md) (one API under `/api`), [ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) (the public API contract)

**Relates to:** [ADR 0105](0105-one-store-app-signs-in-to-any-instance-by-its-address.md), [ADR 0106](0106-the-mobile-apps-release-with-the-stores-own-tools.md), [ADR 0107](0107-a-push-is-one-web-push-message-from-the-instance.md)

## Context

Hephaestus plans mobile apps for developers and mentors.
The first app shows a developer's practice feedback and their conversation in Ask Hephaestus, with live answers.

The web app and the browser extension share one model: the server owns the domain, and each client is generated from `server/openapi.yaml` (AGENTS.md § Wire contract).
Answers stream as server-sent events (SSE) from `GET /api/workspaces/{workspaceSlug}/mentor/events`.
That route accepts `Authorization: Bearer` from a native client (`docs/contributor/unified-pi-runtime.mdx` § Live updates).

Coding agents write most of the code, and maintainers review every change.
In 2026, Shopify rebuilt the Shopify app in Swift and Kotlin in 12 weeks. The app had run on React Native with the New Architecture.
A one-shot translation of a whole app produced code that Shopify could not ship. Small checkpoints with parity checks and architecture rules in CI did work ([Shopify Engineering][shopify-rebuild]).
That rebuild had about 30 engineers, so its review capacity does not transfer to a small team.

A trial on 2026-11-13 generated a Swift client and a Kotlin client from the current spec and compiled them.
Two spec defects stop both generators: three repeated tag definitions, and one `ETag` response header without a schema.
Apple's generator also skips the 80 nullable `anyOf` properties without an error, such as `item` in the delivered feedback.
Both generators make closed enums, but the public contract makes most response enums open.
With the defects fixed, free-form objects mapped to `JsonElement`, and one Kotlin model (`ProblemDetail`) patched by hand, both clients compiled in full. The trial proves that the approach can work. Compiling the generated output without edits is a prerequisite that the API contract work owns.

## Decision drivers

- **One wire contract.** An endpoint change shows as one diff in the spec and in every generated client.
- **The domain stays on the server.** A client that computes review, standing or delivery rules can show a claim that the server would not make.
- **Some platform code is native under every option:** the sign-in browser component, Keychain and Keystore storage, and the notification extension that decrypts a push ([ADR 0108](0108-the-store-apps-receive-push-through-one-stateless-relay.md)).
- **Review load is the real constraint.** A maintainer reads every line that an agent writes.
- **Few runtimes and few dependencies** ([ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md)).

## Considered options

1. **Native thin clients: SwiftUI on iOS and Jetpack Compose on Android, generated from the spec (chosen).** Each platform has its own UI and a small state layer. Cost: two languages and two toolchains, and every view and state change is written and reviewed twice. The client logic is duplicated too: refresh coordination, the version gate, SSE decoding and push decryption.
2. **React Native with Expo.** A new app starts on the New Architecture, with no legacy migration. One TypeScript codebase holds the views, the state, refresh coordination, the version gate and SSE decoding. The generated TypeScript client and its zod schemas are reused, and Expo modules wrap secure storage and the sign-in browser. Native code remains for the iOS notification extension, the Keychain group that it shares with the app, the HTTPS sign-in callback, and the adapters to each. Costs: a JavaScript runtime in the app, the React Native and Expo packages and their upgrade cadence, and a native-module boundary that a maintainer must also review.
3. **Kotlin Multiplatform for a shared core, with native UI.** Google uses it for Docs on iOS. It would share the duplicated client logic of option 1 and keep native views. It adds Kotlin/Native to the iOS build, whose build speed JetBrains names as a common concern.
4. **A Rust core with UniFFI bindings.** It fits a heavy client engine, such as sync, storage or cryptography. Hephaestus has no such engine in the client.
5. **Skip, or the Swift SDK for Android.** One Swift codebase that also produces a Compose app. It is free and open source, and Swift 6.3 has an official Android SDK. It is new, and the app would depend on how closely Skip follows SwiftUI.
6. **A native shell over the web app.** Hotwire Native expects server-rendered HTML, but the web app is a single-page app. A wrapped website is also a review risk under App Store guideline 4.2. [ADR 0107](0107-a-push-is-one-web-push-message-from-the-instance.md) uses the installed web app as a complement for push.

Option 2 writes most of the code once, and that is its strong case.
We choose option 1 for three reasons.
The apps stay thin, because the server owns the domain.
Agents make the second copy cheap to write.
The apps also keep platform-native UI and one runtime fewer.
We accept about twice the view and state code to review.
The review-wait trigger watches that cost, and the trigger for duplicated defects watches the shared logic that option 3 would hold.

## Decision

Option 1.

1. **Two apps in the monorepo:** `ios/` (Swift 6, SwiftUI, Swift Package Manager modules) and `android/` (Kotlin, Jetpack Compose, Gradle). Each tree has an `AGENTS.md`, and a `CLAUDE.md` that imports it.
2. **The apps hold no domain rules.** They show server DTOs and send what the person did. When a screen needs data that no endpoint gives, the server gets a read shaped like that screen ([ADR 0076](0076-reads-are-shaped-like-the-screen.md)), which the web app can use too.
3. **Both generators read `server/openapi.yaml` without a transform.** iOS runs [swift-openapi-generator][swift-oag] in its command-line mode, not as a build plugin. Android uses the OpenAPI Generator `kotlin` generator with kotlinx.serialization ([options][oag-kotlin]). `vp run generate:api` writes both outputs, and the repository commits them. A construct that a generator cannot read faithfully is a defect of the API contract. [ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) fixes it in the spec, and it owns evolution, open enums, absent values, deprecation and the tolerant reader that each app follows.
4. **The live conversation uses the existing stream and its resume rules.** The stream route has no operation in the spec. Owned code on each platform reads the raw events and decodes each `data` by its event name (`snapshot`, `turn`, `entry`, `read`) into the generated DTO. It follows the resume algorithm of `docs/contributor/unified-pi-runtime.mdx` § Live updates. It skips an unknown event name ([ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) rule 5), and it reads again after an event that it cannot decode. The server adds no second streaming protocol.
5. **An app calls only the public contract.** When an app release ships, each method and path that it calls is in `server/public-api-operations.json` or in the documented routes outside OpenAPI: discovery, browser sign-in and the conversation stream. No operation is promised before an app ships.
6. **One pull request changes both platforms.** A user-visible feature lands on iOS and Android together. If it cannot, the pull request opens an issue for the other platform.
7. **Each app has one command surface, and most iOS tests run on Linux.** `vp run` starts the build, the unit tests and the snapshot tests of each app, and the app's `AGENTS.md` names them. The iOS client, stream and view-model packages depend only on Foundation, Observation and the OpenAPI runtime. Their tests inject the byte stream, so they also run in a Linux Swift container. Only the app target binds the URLSession transport and needs macOS.

## Consequences

- Good: a spec change shows its effect on all four clients in one diff.
- Good: the apps stay thin. A new server rule reaches them without a client change.
- Good: each app uses the platform's own UI, storage, sign-in and notification components.
- Bad: two codebases. Maintainers review Swift and Kotlin, and the iOS app target builds only on macOS.
- Bad: the client logic exists twice, so a defect can be fixed on one platform and stay on the other.
- Bad: the apps wait for the API contract fixes that let both generators read the spec faithfully.
- Bad: rule 6 slows a feature when one platform is harder.

## Confirmation

- A CI job generates both clients from the unchanged spec and compiles them. It fails on a diff with the committed sources, and on a generator warning that drops a property or a schema.
- A CI job builds both apps and runs their unit and snapshot tests. The Android job and the iOS package tests run on Linux, and the iOS app job runs on macOS.
- A contract job starts the packaged server JAR with the end-to-end seed. It runs each app's client tests against it: the sign-in handoff, the feedback list, and a stream snapshot of the seeded answered exchange (AGENTS.md § What we will not compromise on, item 4). The Android tests run on Linux, and the iOS tests run against the same JAR in the macOS job.
- A test fails when an app calls a method and path outside the public contract of rule 5.
- Rules 2 and 6 are not enforced. Review catches a domain rule in an app, and a one-platform feature without an issue.

## Revisit trigger

- A defect in the duplicated client logic is fixed on one platform and found again on the other. Then price option 3.
- The median review wait of mobile pull requests is more than a week for a quarter. Then price options 2 and 3.
- The apps need client logic beyond a thin view, such as offline sync or local search.
- Skip or the Swift SDK for Android ships a production app of similar size.

## Sources

- Shopify Engineering, [How we rebuilt the Shopify app in 12 weeks][shopify-rebuild] (2026): with agents, two native codebases were affordable. A one-shot port failed, gated small steps with CI-enforced parity worked, and review became the bottleneck. This sets rule 6 and the review-wait trigger.
- Apple, [swift-openapi-generator][swift-oag]: a typed Swift client from the spec, with OpenAPI 3.1 support. This makes rule 3 possible on iOS.
- OpenAPI Generator, [Kotlin generator options][oag-kotlin]: kotlinx.serialization, and a fallback case for unknown enum values. This makes rule 3 possible on Android, once the spec is fixed.

[shopify-rebuild]: https://shopify.engineering/shopify-app-rebuild
[swift-oag]: https://github.com/apple/swift-openapi-generator
[oag-kotlin]: https://openapi-generator.tech/docs/generators/kotlin
