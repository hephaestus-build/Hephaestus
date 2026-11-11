#### 🔴 Use a root HTTPS API issuer in production

**Who is affected:** Every production server whose `hephaestus.auth.issuer` is absent, uses HTTP, or contains a path, credentials, query or fragment. Chart installations already supply a root HTTPS issuer.

**Action:** Before upgrading, set `HEPHAESTUS_AUTH_ISSUER` or `hephaestus.auth.issuer` to the public API root origin, for example `https://api.example.test`. Keep a public API prefix in `hephaestus.auth.api-base-path`, not in the issuer. The production server refuses startup until the issuer passes this check, even when GitLab, Outline and Slack are disabled.

Changing the issuer changes the JWT `iss` claim. Existing access tokens are rejected, so users must sign in again. Verify API routing, then register the canonical Slack callback before upgrading if Slack connections are enabled. A process that runs only the webhook or worker role does not require this issuer check.
