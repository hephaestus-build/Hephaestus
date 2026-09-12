---
"hephaestus": minor
---

Hephaestus can now send email. Set `SPRING_MAIL_HOST` (plus `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`,
`SPRING_MAIL_PASSWORD` for an authenticated relay) and `HEPHAESTUS_EMAIL_FROM` on the application
server to turn it on; leave the host unset and nothing changes. Instance admins verify the relay with
**Instance settings → Email → Send test email**, which reports relay acceptance, configuration problems, or why a test was withheld.
People whose account has a verified email address receive a confirmation when they delete their
account, naming its scheduled deletion deadline. These confirmations are queued in the same transaction
as the account change and retried until that deadline when the relay is unavailable. SMTP can deliver
duplicates after a failed acknowledgement; relay acceptance does not guarantee inbox delivery.
Silent Mode withholds email through the shared outbound guard. The local development stack gains a Mailpit inbox at
`http://localhost:8025` by default (configurable with `MAILPIT_UI_PORT`). **Operators:** activating email makes the relay operator a recipient of
personal data; complete the processor checklist before setting the host.
