---
"hephaestus": patch
---

Hephaestus's replies in Slack and its weekly digest notes now go out through the same outbox as email and feedback. The server that streams a reply still finishes it. When that server is gone, another server finishes the reply within about two minutes after the turn ends: it posts a reply that nobody started, and it looks for a started one in the Slack history and never posts it twice. The separate Slack reconciler job is gone. Operators find how a reply ended in `mentor_entry.slack_delivery` of its question, a reply in progress in `agent_action` under `slack_reply:<question id>`, and can watch these posts in the `action.ended` metric with `kind=SLACK_POST`.
