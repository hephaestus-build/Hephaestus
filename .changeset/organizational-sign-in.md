---
"hephaestus": minor
---

Sign in with an organizational OpenID Connect account, or connect it to an existing account from Settings. Instance operators can approve exact issuer URLs with the optional `HEPHAESTUS_AUTH_OIDC_ALLOWED_ISSUERS` setting and configure providers in Instance admin. Separate realms remain separate identities, and connected-account controls distinguish provider instances and protect the last available sign-in method.

Organizational sign-in does not by itself grant workspace access or provision GitHub memberships.

Institutional and secondary providers stay off the public sign-in picker and remain available from authenticated account linking. Account confirmation offers only the exact linked provider instances, not other GitLab deployments or institutional realms.
