---
"hephaestus": minor
---

`hephaestus-keys.sh --rotate` now replaces a key whose rotation needs only a new value and a restart. For `HEPHAESTUS_AUTH_STATE_COOKIE_KEY`, it puts a new value in the release Secret and restarts the server. For `WEBHOOK_ROUTING_SECRET`, it keeps the current value as `WEBHOOK_ROUTING_PREVIOUS_SECRET`, puts a new value in place, and restarts the server and the webhook role. It refuses a second routing rotation while a previous value is set, because that would stop the hooks that the previous value signed. Every other key keeps its own procedure on the Secret custody page.
