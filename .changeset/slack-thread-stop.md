---
"hephaestus": patch
---

In a Slack thread with Hephaestus, Slack now shows that Hephaestus works, with a **Stop** control that stops only the answer in progress. It works also before Hephaestus writes its first words, and questions that wait after it keep their place. Hephaestus stops soon after, not always at once. To show the control, apply the current Slack app manifest, which subscribes the app to `agent_session_stopped` and needs no new OAuth scope. Until then, developers stop Hephaestus in the app as before.
