# ADR 0105: One store app for each platform signs in to any instance by its address, through a claimed HTTPS redirect and a checked issuer

**Status:** Proposed
**Date:** 2026-11-13
**Authors:** Hephaestus maintainers

**Amends:** [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md): `InstalledClientKind` gains the two mobile apps, the official mobile apps are allowed by default (unlike the extension), a mobile session has its own absolute deadline, and every handoff response names its issuer.

**Builds on:** [ADR 0104](0104-the-mobile-apps-are-native-clients-of-the-server-api.md), [ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) (the public API contract), [ADR 0088](0088-a-person-signs-in-through-an-instance-login-provider-and-is-one-account.md)

## Context

Every Hephaestus instance is self-hosted, with its own origin and login providers.
A person installs one app from the App Store or Google Play, then connects it to the instance of their team.
The browser extension already works this way.
It reads `/.well-known/hephaestus.json`, refuses a server older than 1.0.0, and signs in with the PKCE handoff of [ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md).
That handoff has no consent step.

The forces:

- **The redirect decides which app gets the code.** PKCE stops a second app from redeeming a code that it intercepted. It does not stop a second app that starts its own flow with the public official client id, because that app holds its own verifier. RFC 8252 lets a server skip the person's consent only "when the identity of the client can be assured", and it names claimed HTTPS redirects as such proof (§8.6).
- **Many servers share one client, so mix-up is possible.** A malicious instance can forward the app's sign-in request to an honest instance. The honest instance returns its code to the shared redirect, and the app sends that code and its verifier to the malicious instance, which redeems them. RFC 9207 defends against this: the server names itself in the response with `iss`, and the client "MUST reject" a response from another issuer ([RFC 9207][rfc9207]).
- **A claimed HTTPS host is fixed in the app at build time.** iOS needs it in the app's Associated Domains, and Android needs a Digital Asset Links file on it. One store app cannot claim every self-hosted domain, but it can claim one host of the publishing organization. On Android, a verified host opens only the app that its Digital Asset Links file names.
- **The platform components for that redirect.** `ASWebAuthenticationSession` accepts an HTTPS callback from iOS 17.4. The Android Auth Tab accepts one from Chrome 137, after it checks Digital Asset Links, and it falls back to a Custom Tab on older browsers ([Auth Tab][auth-tab]).
- **A session ends at an absolute deadline**, 7 days by default for every session (`sessionMaxLifetime`). A phone that must sign in again each week, and that loses its push subscription each time, is not usable.
- **Native passkey APIs cannot serve arbitrary instances** for the same reason. In Hephaestus, a passkey protects only admin access (`docs/user/passkeys.mdx`).
- **Store review.** The app signs in through each instance's GitHub or GitLab provider. App Store guideline 4.8 exempts business apps that sign in with an existing enterprise account, and clients of a third-party service that sign in to that service directly. Whether App Review applies them here is not certain.
- **App and server versions drift.** The Artemis Android app, one app for many servers, documents that it does no server-version negotiation, so a removed endpoint fails without a message.

## Decision drivers

- One store build works with every instance in its support window, and an operator registers nothing at a login provider.
- One sign-in model for every installed client ([ADR 0048](0048-installed-clients-sign-in-with-a-pkce-handoff.md)).
- The instance knows which app gets a session, and the app knows which instance answered (RFC 8252 §8.6, RFC 9207).
- Every state can be undone: sign out, remove an instance, turn mobile sign-in off.

## Considered options

1. **A list of instances compiled into the app**, as Artemis has. Each new instance needs an app release, and a private instance still needs a free-form address.
2. **A private-use URI scheme redirect** (RFC 8252 §7.1). It works on every OS version. Any app can register the same scheme and impersonate the official app. The fix would be a confirmation page before every handoff, which people learn to click through.
3. **A claimed HTTPS redirect on one host of the publishing organization (chosen).** The operating system assures the instance that only the official app receives the redirect. The cost is an iOS floor of 17.4, a host that every instance's sign-in depends on, and a new root of trust for client identity.
4. **Native passkey sign-in.** Impossible for arbitrary relying parties, and not needed.
5. **A minimum app version that the server publishes, with forced updates**, as Artemis has. A self-hoster cannot know which app version is safe. iOS has no forced-update API, and the Play In-App Updates API works only for Play installs.

## Decision

Option 3.

1. **The person enters the instance address.** The app accepts only HTTPS. The app reads `/.well-known/hephaestus.json` and resolves a workspace host with the extension's rules. It then asks `GET /auth/client/configuration` whether the instance accepts its client id. Unlike the extension, it saves an instance only after both reads succeed. In production, mobile sign-in needs a configured HTTPS instance origin (`apexOrigin`). If `apexOrigin` differs from the entered origin, the app accepts it only as the apex of a workspace host, by the extension's rules, and otherwise refuses to save the instance. The app sends the sign-in request, the token request and every API call to that one origin. A debug or end-to-end build may enter a loopback HTTP server origin instead. It then uses that origin for everything, whatever discovery says. The server in its `local` and `e2e` profiles sends its issuer setting (`hephaestus.auth.issuer`) as `iss`, and the build must enter exactly that origin (`localhost` and `127.0.0.1` are different origins). Debug builds have their own client id and their own redirect host, which only those two profiles list. The publishing organization runs that host for builds signed with the debug key, so a debug signing key never appears on the official host.
2. **Sign-in is the ADR 0048 handoff with a claimed HTTPS redirect.** `InstalledClientKind` gains `MOBILE_IOS` and `MOBILE_ANDROID`, each with one exact redirect on the host of rule 4. iOS uses `ASWebAuthenticationSession` with the HTTPS callback, and Android uses the Auth Tab. No app opens a login page in an embedded web view. Client ids widen beyond the Chrome extension pattern, and a mobile client has no CORS origin, so the spec and the generated clients change.
3. **Every handoff response names its issuer (RFC 9207).** The redirect carries `iss` beside `code` and `state`, and also on an error. In production, `iss` is the same origin that discovery publishes as `apexOrigin`. The expected issuer is the origin that the client opened the sign-in request on and will send the code to, never a value that a server supplies. The app refuses a missing or different `iss` before it sends the code or the verifier anywhere. The extension refuses a different `iss` whenever one is present, whatever version a server reports. It accepts a missing `iss`, because servers before this change send none, and that residual risk ends as those servers upgrade. The Compatibility Policy lists `iss` among the handoff parameters.
4. **The redirect host is a guarded root of trust.** It is a static host of the publishing organization that serves only the Associated Domains file, the Digital Asset Links file and a fallback page. The two files deploy only from this repository through a protected workflow. The domain has a registrar lock and DNSSEC. A scheduled job compares the served files with the repository and alerts on a difference. The fallback page logs no query string, sends `Referrer-Policy: no-referrer`, and shows an "Open in the app" link.
5. **The official apps are allowed by default, and the operator can turn them off.** A chart value lists the allowed mobile clients, each with its kind, client id and exact HTTPS redirect, and it defaults to the official two. An empty list turns mobile sign-in off. Turning it off, or removing a client, ends every session of that client through `SessionRevocation` and deletes their push subscriptions. Then refresh fails as well as sign-in. The changeset that ships this tells operators that the official apps are allowed after the upgrade, and how to turn them off. An operator who publishes an own app build lists it with its own redirect host ([ADR 0108](0108-the-store-apps-receive-push-through-one-stateless-relay.md) § Decision, self-hoster choices).
6. **A mobile session has its own absolute deadline.** A chart value sets it, and it defaults to 90 days. The access-token lifetime, strict rotation and every revocation path stay as ADR 0048 sets them.
7. **Tokens stay on the device, and removal never waits for the network.** The tokens are in the iOS Keychain (this device only) and in storage that the Android Keystore encrypts. All local state is keyed by instance origin and account: tokens, caches, drafts and push keys. To remove an instance, the app tries `POST /auth/client/logout` once with a short timeout, then deletes all of that state, whatever the answer. If the instance cannot be reached, the session ends at its deadline, or the person ends it in the web app's session list.
8. **The apps show member features only.** Administration stays in the web app, behind its passkey step-up.
9. **[ADR 0103](0103-the-public-api-grows-only-by-addition-and-every-client-tolerates-what-it-does-not-know.md) owns compatibility, and this ADR adds the app's side.** The first server release that registers the mobile clients and sends `iss` is the mobile baseline. Each app release declares a minimum server version at or above the baseline. That minimum is no newer than the baseline or the oldest of three releases, whichever is newer. A release candidate counts as its release. The three releases are these:
   - The oldest server release published in the last 12 months.
   - The newest server release older than 12 months.
   - While the previous major gets security fixes, the last minor release of that major.

   On an older server, or on a major version that the app does not know, the app shows "Ask your admin to upgrade" or "Update the app". Then it calls nothing on that server except logout.
10. **No forced update.** The server publishes no minimum app version.

## Consequences

- Good: another app cannot get a session with the official client id, and a malicious instance cannot redeem an honest instance's code.
- Good: an operator does nothing to allow the official apps, and turning them off ends their sessions at once.
- Good: sign-in, rotation and revocation are the ADR 0048 code. The session list shows a phone like an extension.
- Bad: the redirect host is a root of trust for every instance that keeps the default. Whoever can change its two files can add an own app that passes the OS checks and gets sessions with the official client id. Recovery is slow: Apple's CDN fetches the file within 24 hours, devices check it about once a week, and Android 14 and earlier check it only on install or update.
- Bad: a mobile session lives up to 90 days, so a stolen, unlocked phone keeps access longer than a browser. The person or an admin ends it from the session list, and the Keychain entry stays on that device only.
- Bad: if the host's files are unreachable when the OS checks them, the app cannot sign in. On Android 14 and earlier, a failed check at install time lasts until the next app update.
- Bad: the apps need iOS 17.4 or later. A browser without Custom Tabs cannot sign in.
- Bad: ADR 0048 rotates strictly, so a refresh response lost on a mobile network signs the app out. The server measures this: a new session of the same account and client soon after a refresh whose successor secret was never presented.
- Bad: App Review may reject the app under guideline 4.8. The fallback, Sign in with Apple as an instance login provider, would change ADR 0088.
- Bad: after 2.0, an app release supports both majors for the 12 months in which 1.x still gets fixes.

## Confirmation

- `InstalledClientRegistryTest` fails when a mobile kind yields other than one exact HTTPS redirect, or when an empty list still accepts a mobile client.
- `ClientSessionIntegrationTest` covers the mobile kinds with the extension's handoff, reuse and revocation cases. It fails when a handoff response lacks `iss`, when a mobile session ignores its own deadline, or when a session of a removed client can still refresh.
- An extension test fails when the extension accepts a different `iss`, whatever version the server reports.
- An app test drives the Auth Tab and its Custom Tab fallback against a verified host.
- A debug-build test signs in to a loopback server with separate API and web app ports and an empty or different `apexOrigin`.
- An app unit test fails when the app sends a code to an instance whose `iss` differs from the origin of its sign-in request or is missing, when discovery's origin replaces the entered one outside the workspace-host rules, when a release build accepts a non-HTTPS address, or when the app calls anything but logout on a server below its minimum.
- The mobile release job fails when an app's minimum server version breaks rule 9. Its test covers releases before the baseline, the day after a new major, and a year without a release.
- The scheduled host check fails when a served verification file differs from the repository copy.
- Rule 8 is not enforced. Review catches an admin screen in an app.

## Revisit trigger

- A verification file on the redirect host changes without a release of this repository.
- The measured sign-out rate after a lost refresh response is material. This reopens the grace window that ADR 0048 option 4 declined.
- App Review rejects the app under guideline 4.8.
- Member features start to need a passkey.
- A shipped app has a defect that a store update cannot stop in time. Then price option 5.

## Sources

- IETF, [RFC 8252][rfc8252]: no embedded user agents (§8.12), PKCE for public clients (§8.1), and consent skipped only when "the identity of the client can be assured", for example by claimed HTTPS redirects (§8.6). This sets rule 2 and rules out option 2 without a consent page.
- IETF, [RFC 9207][rfc9207]: the server includes `iss` in every authorization response, and the client rejects a mismatch. This sets rule 3.
- Chrome for Developers, [Auth Tab][auth-tab]: data returns to the app through a callback, HTTPS redirects need Digital Asset Links, Chrome 137 or later, with a Custom Tab fallback. This sets the Android component.

[rfc8252]: https://www.rfc-editor.org/rfc/rfc8252
[rfc9207]: https://www.rfc-editor.org/rfc/rfc9207
[auth-tab]: https://developer.chrome.com/docs/android/custom-tabs/guide-auth-tab
