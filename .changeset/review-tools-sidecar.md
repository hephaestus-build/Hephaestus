---
"hephaestus": minor
---

A review's commands and custom practice programs now run in their own containers, without its credential and with no way to reach the network. Only the review's own process holds the credential, and it can no longer start a process. A repository search shows that something is absent only when Hephaestus has confirmed it, so a result that rests on a search it could not confirm is withheld.

**Operators:** a custom agent image must be rebuilt from this release: the runtime contract is now 10.
