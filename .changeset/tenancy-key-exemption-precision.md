---
"hephaestus": patch
---

The workspace isolation check is exact about which single-row statements it exempts. A statement whose key predicates sat behind a SQL comment, or whose assignment read from a second table, could be treated as addressing one keyed row when it did not. No released version exempted these shapes.
