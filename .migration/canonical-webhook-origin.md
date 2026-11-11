#### 🔴 Use the canonical webhook receiver base

**Who is affected:** Operators whose previous GitLab or Outline receiver base differs from the API issuer origin must update their configuration. Compare the full base URL, including any path prefix. This includes explicit overrides and the previous implicit `hephaestus.host-url` default.

**Action:** Remove `WEBHOOK_EXTERNAL_URL` and `hephaestus.webhook.external-url`. Registration uses the API issuer origin, which is the chart host. Receiver paths begin with `/webhooks/`. They do not include `/api` or another proxy prefix.

For example, a previous base of `https://api.example.test/hooks` differs from the canonical base `https://api.example.test`, even though their origins match.

If the previous full GitLab receiver base differs:

1. Confirm that the canonical base routes `/webhooks/` to this installation's receiver.
2. Stop the application roles through the deployment runbook before changing GitLab registrations.
3. Record this installation's GitLab group hooks at the previous base, with their workspace and connection associations.
4. Remove only those recorded group hooks. Do not remove another installation's registration.
5. Remove the retired settings, upgrade, and start the roles.
6. Run full GitLab workspace synchronization for each affected connection to recreate registrations at the canonical base.
7. Verify provider delivery and confirm that the recorded hooks at the previous base are gone.

If delivery cannot be verified, stop the new roles. Restore the previous release and configuration. Recreate the recorded GitLab hooks.

If the full GitLab receiver base already matches, remove the settings and upgrade without manual hook removal. GitLab leaves hooks at another base in place, so the cleanup above is required even for a different path on the same origin.

Outline needs no manual subscription removal or separate stop step. After upgrading, run Outline collection synchronization for each affected connection. It replaces subscriptions whose full address differs and removes the previous subscription. Replacement subscriptions can have new route IDs and signing credentials.

GitHub App webhook registration still uses `/webhooks/github`. Slack keeps its Events API and separately routed interactivity addresses.

#### 🔴 Use a root HTTPS API issuer in production

**Who is affected:** Every production server whose `hephaestus.auth.issuer` is absent, uses HTTP, or contains a path, credentials, query or fragment. Chart installations already supply a root HTTPS issuer.

**Action:** Before upgrading, set `HEPHAESTUS_AUTH_ISSUER` or `hephaestus.auth.issuer` to the public API root origin, for example `https://api.example.test`. Keep a public API prefix in `hephaestus.auth.api-base-path`, not in the issuer. The production server refuses startup until the issuer passes this check, even when GitLab, Outline and Slack are disabled.

Changing the issuer changes the JWT `iss` claim. Existing access tokens are rejected, so users must sign in again. Verify API routing, then register the canonical Slack callback before upgrading if Slack connections are enabled. A process that runs only the webhook or worker role does not require this issuer check.
