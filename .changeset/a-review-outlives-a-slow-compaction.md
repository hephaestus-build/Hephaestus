---
"hephaestus": patch
---

A practice review no longer loses every remaining practice when one of its turns runs out of time
while the model is condensing its context. The condensing call now ends with the turn, and the next
turn waits for the session instead of being refused; on one cohort two reviews in eleven had reached
fifteen of thirty-eight practices this way.
