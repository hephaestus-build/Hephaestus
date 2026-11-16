---
"hephaestus": patch
---

Ask Hephaestus answers again. Since conversation turns moved into conversation sandboxes, every question failed with "Hephaestus could not finish this reply" before Hephaestus answered: the server prepared the conversation's context while it held the turn's claim, so the sandbox lost the claim before it could start. The server now prepares the context without holding the claim.
