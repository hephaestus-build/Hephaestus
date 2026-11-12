---
"hephaestus": patch
---

Model tool-choice tests now share model capacity and usage accounting with other AI work. Each synthetic call has a 15-second deadline and a 64-token output limit. Tests respect a paused connection without changing its health record. Disabled models can be tested before activation. Set a price before testing a model in a workspace with a provider spending cap.
