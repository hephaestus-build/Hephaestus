---
"hephaestus": patch
---

Signed webhooks that Hephaestus does not handle no longer fill the inbox. The server reports extra and missing GitHub App subscriptions. In the App's **Subscribe to events** settings, uncheck unused events such as Workflow job and Check run so GitHub's delivery list leaves room for failed deliveries that need recovery.
