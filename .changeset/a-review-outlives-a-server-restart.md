---
"hephaestus": patch
---

A practice review whose work finished while the server was being restarted now runs again instead of
ending with nothing. The sandbox can only reach the server at the address it had when the review
started, so a server that comes back elsewhere is out of reach for the rest of that run; the review
is queued for another attempt, and what the first attempt reported about itself stays on record.
