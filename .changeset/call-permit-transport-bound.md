---
"hephaestus": patch
---

A worker that loses its database now always cancels its model calls before their capacity is given to other calls, and capacity no longer stays taken by a call that outlives its deadline.
