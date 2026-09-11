---
---

Preview deployments now serve their API under the app's own hostname at `/api`, the way production does, instead of on a sibling `api.` hostname. The session and CSRF cookies carry the `__Host-` prefix and so bind to a single host, which left a preview unable to sign out or perform any other action that changes something.

No effect on a released instance: this changes `docker/preview/`, which only the project's own preview deployments run. Operators of a Hephaestus instance have nothing to do.
