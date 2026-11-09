---
"hephaestus": minor
---

Hephaestus now runs a conversation's commands in a tools Pod of their own. The conversation loop reaches it over the network, and the worker no longer opens a shell in any sandbox Pod: the worker's Role in the sandbox namespace drops `pods/exec`. The chart adds a network policy there, so that only the conversation loop reaches a tools Pod, and a tools Pod still reaches nothing. The release's agent image and server change together, as every release's do.
