---
"hephaestus": patch
---

A restarted or upgraded server takes requests sooner after it starts. The chart now checks a starting server every 2 seconds instead of every 10, and its readiness every 5 seconds instead of every 10. A server still has 12 minutes to start, so a server that waits for its database migration is not restarted earlier. No operator action is needed.
