---
"hephaestus": patch
---

Each read of practice standings, the practice profile overview and its review list now sees one moment of the database, and checks a pull request's repository once instead of once for each source. A read that takes longer than 30 seconds now ends with an error instead of returning late.
