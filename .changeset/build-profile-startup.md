---
"hephaestus": patch
---

Building the API contract or container class archive no longer starts server background scheduling or attempts sync-job recovery and signing-key seeding against an unavailable database. Production still validates sealed signing keys and seeds the active key at startup.
