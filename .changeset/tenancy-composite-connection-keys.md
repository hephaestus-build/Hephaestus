---
"hephaestus": patch
---

The database now refuses a connection's activity, Outline collection or Outline document that names a different workspace than its connection. The upgrade adds these checks without stopping the server. If an existing row names a different workspace than its connection, the upgrade stops and names the row's key. Correct or delete that row, then upgrade again.
