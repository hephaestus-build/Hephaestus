---
"hephaestus": minor
---

Hephaestus now runs a conversation's commands in a tools Pod of their own. The conversation host reaches it over the sandbox network, and the worker no longer opens a shell in any sandbox Pod: the worker's Role in the sandbox namespace drops `pods/exec`. The chart adds two network policies there, so that only a conversation host reaches a tools Pod, and a tools Pod still reaches nothing. The release's agent image and server change together, as every release's do.
