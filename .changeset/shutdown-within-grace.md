---
"hephaestus": patch
---

The server now stops within seconds during an upgrade instead of waiting 20 seconds for open live streams; browsers reconnect to the new server and catch up.
A stopping worker now waits for its whole drain, running Heph turns included, before it closes its database connections. Before, the drain phase ended after a fixed 6 minutes.
The Helm chart gives the server 45 seconds to stop instead of 35.
