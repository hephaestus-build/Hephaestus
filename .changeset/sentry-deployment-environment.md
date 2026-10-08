---
"hephaestus": minor
---

Server errors in Sentry now carry the deployment environment from `SENTRY_ENVIRONMENT`, the same one the webapp reports, instead of `prod` for every deployment. Staging and production errors no longer mix. Exported traces now name the running version.

**Operators:** if you send errors to Sentry, update alerts and saved searches that filter on `prod`, and set `SENTRY_ENVIRONMENT` in each stack's environment file.
