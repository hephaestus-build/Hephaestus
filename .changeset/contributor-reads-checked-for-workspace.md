---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects the reads that find who contributed to a piece of work under review. Each of them reads by the id of work that review already holds.
