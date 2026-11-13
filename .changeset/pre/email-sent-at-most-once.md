---
"hephaestus": minor
---

Email is sent at most once. Each notification email keeps one Message-ID across retries. If a mail server connection breaks after the message was handed over, or a server stops while it sends, Hephaestus no longer sends a second copy: the email ends as not confirmed. A retry now happens only when the mail server proved that it did not take the message. Before every retry, Hephaestus checks again that the recipient still wants the email. The test email in **Instance settings → Email** can report the new outcome **Not confirmed**. Operators can watch every delivery in the `action.ended` metric and in the new `agent_action` table, which deleting an account clears.
