---
"hephaestus": minor
---

Product feedback drafts use the server's model gateway, with shared capacity and egress checks. A busy model delays a draft without spending a retry. Each provider call records its frozen price and usage in the instance ledger. Drafts have up to three failed-call attempts. Queued drafts expire after ten minutes. Recovery of an in-flight claim can take longer.

**Operators:** Keep a verified pre-upgrade database backup for recovery. If your firewall limits server egress, allow the provider of the instance model selected for product feedback. Drafts need the agent runtime enabled on the server.
