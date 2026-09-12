#### 🔴 Impersonation replaced by read-only user views

Remove clients of `POST /auth/impersonate` and `POST /auth/impersonate:exit` and the `X-Impersonation-Allow-Writes` header. Drop `hephaestus.auth.impersonation-max-lifetime`. Replace `HEPHAESTUS_AUTH_RATE_LIMIT_IMPERSONATE_CAPACITY` / `_PERIOD` with `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_CAPACITY` / `_PERIOD` where you override the defaults. Administrators who were inside an impersonation session sign in again.
