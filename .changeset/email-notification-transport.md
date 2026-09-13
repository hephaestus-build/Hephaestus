---
"hephaestus": minor
---

Hephaestus can now send email. Set `SPRING_MAIL_HOST` (plus `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD` for an authenticated relay) and `HEPHAESTUS_EMAIL_FROM` on the application
server to turn it on; leave the host unset and nothing changes. Authenticated SMTP requires TLS. Instance admins verify the relay with
**Instance settings → Email → Send test email**, which reports relay acceptance, configuration problems, or why a test was withheld.
People whose account has a verified email address receive a confirmation when they delete their
account, naming its scheduled deletion deadline. These confirmations are queued in the same transaction
as the account change and retried until that deadline when the relay is unavailable. Permanent SMTP
send rejections are not retried. SMTP can deliver
duplicates after a failed acknowledgement; relay acceptance does not guarantee inbox delivery.
Silent Mode withholds email through the shared outbound guard. The local development stack gains a Mailpit inbox at
`http://localhost:8025` by default (configurable with `MAILPIT_UI_PORT`). **Operators:** activating email makes the relay operator a recipient of
personal data; complete the processor checklist before setting the host.

Optional email subscriptions start off and belong to each account. Administrators can choose immediate
product-feedback alerts or a daily digest; people can choose product and research survey invitations
separately. Every optional email offers unsubscribe without sign-in. Survey invitations require an
explicit administrator action, respect current eligibility and keep relay acceptance separate from
in-app participation counts. Administrators may request one reminder after 72 hours and subscribe to
end-of-survey summaries without receiving individual answers. Workspace administrators can opt in to
Slack credential-revocation and GitHub suspension alerts, with recovery notices. Account linking,
unlinking and administrator-access changes also send
security notices to the affected account's verified address. Shared SMTP attempt budgets reserve
capacity for essential mail; configure them to fit your relay before inviting a large audience.
