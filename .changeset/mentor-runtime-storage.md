---
"hephaestus": patch
---

The upgrade adds three empty tables for the personal mentor's intake. Each row belongs to one account, and PostgreSQL row-level security limits it to that account. Nothing writes them yet, so nothing changes for operators or people. Person export and erasure already include them.
