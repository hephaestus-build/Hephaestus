---
"hephaestus": patch
---

A trace now follows a provider event from the webhook request through the message broker to the server that handles it. Before, each provider call that the handling made started a trace of its own, so a slow sync or webhook could not show which calls it waited for. The server opens a `process <stream>` span around each event, and the provider calls of that event are its children.
