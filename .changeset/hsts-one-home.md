---
"hephaestus": patch
---

Every response now carries one `Strict-Transport-Security` policy. The server sends it on its own responses, webhooks and health checks included, and the chart's route adds it only to the web app's pages. The policy that browsers keep stays 30 days.
