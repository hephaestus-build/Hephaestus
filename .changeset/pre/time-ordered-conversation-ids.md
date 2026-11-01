---
"hephaestus": patch
---

Conversations write much less to the database while an answer streams. New conversations, replies and their entries take ids in the order they are made, so the database adds each one to the end of its indexes. Existing ids keep working.
