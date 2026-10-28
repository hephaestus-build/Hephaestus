---
"hephaestus": minor
---

One long Hephaestus answer no longer keeps every other question waiting. An answer stops after five minutes or 40 model calls. In Slack, a question that has not started after 10 seconds gets a short notice instead of silence. When Hephaestus cannot open its files for a reply, it says what it could not check instead of trying again and again.

The Hephaestus timeout of a model assignment now accepts at most 300 seconds. A stored Hephaestus timeout above 300 seconds acts as 300 without any change, and a request that sets a longer one is refused.
