---
"hephaestus": patch
---

A model call whose connection closes before it starts now ends with its own reason and frees its model capacity. Before, such a call reported a provider failure and held a capacity slot until its lease ran out. Deleting a message while Hephaestus was about to answer could show this as a provider failure.
