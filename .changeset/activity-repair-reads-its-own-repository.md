---
"hephaestus": patch
---

Repository syncs put far less load on the database: the activity repair after each sync reads only that repository's work, a slice at a time.
