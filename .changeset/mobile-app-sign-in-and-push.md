---
"hephaestus": minor
---

Hephaestus can now serve the Hephaestus app for iOS and Android. The app opens with hephaestus.build, with another instance available as a separate choice. Developers sign in with the instance's GitHub or GitLab login through their phone's browser — the first sign-in creates their account — see where they stand in each practice group their workspace reviews, down to the evidence behind each observation, read and answer their practice feedback, and talk to Heph. App sessions follow the instance's existing session limits, appear among a developer's signed-in devices, and end with sign-out, device revocation or account deletion like browser sessions.

Existing deployments need no configuration changes. Optional settings, described in the mobile app guide: `HEPHAESTUS_AUTH_NATIVE_REDIRECT_URIS` lists the app builds allowed to sign in (the store build by default); `HEPHAESTUS_AUTH_NATIVE_MINIMUM_APP_VERSION` asks older app versions to update; `HEPHAESTUS_PUSH_EXPO_ACCESS_TOKEN` turns on push notifications that say only that new feedback is waiting, never what it says; it takes the Expo token of the project that published the app your developers use, so it applies to the publisher's instances and to organisations that publish their own build. Push sends device tokens to Expo, Apple and Google, so review the processor checklist before turning it on.
