---
"hephaestus": minor
---

An instance admin can add an OpenID Connect login provider, such as a university's single sign-on, and people sign in through it. Hephaestus keys each person on the provider's issuer and subject and never joins accounts by email. A signed-in person connects and disconnects the provider in Settings. Upgrade to this release only from the release before it, and set an OpenID Connect provider in the environment only after every server runs this release.
