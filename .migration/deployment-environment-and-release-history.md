#### 🔴 `DEPLOYMENT_ENVIRONMENT` replaces `SENTRY_ENVIRONMENT`

The name of a deployment, such as `production` or `staging`, is now a Hephaestus setting.
The webapp, the **Release** card, error reports and exported traces read it from `DEPLOYMENT_ENVIRONMENT`.
`SENTRY_ENVIRONMENT` is no longer read.
Before, every server reported its Sentry errors as `prod`.

Before you upgrade:

1. In `.env`, rename `SENTRY_ENVIRONMENT` to `DEPLOYMENT_ENVIRONMENT`.
   On a host with one environment file per stack, set `DEPLOYMENT_ENVIRONMENT` in each file.
   Without it, the instance reports `local`.
2. Use lowercase letters, digits, `.`, `_` or `-`, at most 64 characters.
   Hephaestus refuses to start with another value.
3. If you send errors to Sentry, change each alert rule and saved search that filters on `prod` to use the new name.
