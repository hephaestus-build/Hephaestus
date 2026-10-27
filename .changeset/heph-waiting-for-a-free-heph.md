---
"hephaestus": minor
---

When Heph is answering as many questions as it can at once, a question that waits now says so in the app and in Slack. The server learns that number from the new optional `HEPHAESTUS_MENTOR_TURN_CAPACITY`. The Helm chart sets it from `worker.replicas` and `worker.hephTurns`. Without the chart, set it on the server to the Heph turns that all your workers run at once. If it is not set, a waiting question does not say why it waits.
