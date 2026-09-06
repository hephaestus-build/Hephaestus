---
"hephaestus": patch
---

A practice review whose work finished while the server was being restarted now runs again instead of
ending with nothing. The sandbox can only reach the server at the address it had when the review
started, so a server that comes back elsewhere is unreachable for the rest of that run. The review is
queued for another attempt unless its observations did reach the server, in which case they are
already on record, and what the first attempt reported about itself stays on record either way.
