---
"hephaestus": patch
---

`SANDBOX_API_PORT=0` now lets the worker bind a free port for its sandbox gateway. Review sandboxes and the Hephaestus host then dial the port that was bound. A fixed port, 8081 by default, works as before.
