#### 🔴 Sentry environment for server errors

Server errors in Sentry now use the value of `SENTRY_ENVIRONMENT` as their environment.
Before, every server reported `prod`.
The webapp already used `SENTRY_ENVIRONMENT`.
When it is unset, the webapp and the server report `local`.

If you do not set `SENTRY_DSN`, do nothing.
Otherwise, before you upgrade:

1. Set `SENTRY_ENVIRONMENT` to the name of the deployment, such as `production`.
   On a host with one environment file per stack, set it in each file.
2. In Sentry, change each alert rule and saved search that filters on `prod` to use that name.
