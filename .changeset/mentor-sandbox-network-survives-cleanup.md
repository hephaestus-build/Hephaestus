---
"hephaestus": patch
---

Sandbox cleanup no longer removes a conversation's sandbox while the application container that started it is still running, however long the sandbox takes to start. Previously a reply from Heph that had to start a fresh sandbox could fail when cleanup, on any application container sharing the Docker host, ran before the sandbox's container existed. Each conversation sandbox now records the application container that started it, and automatic cleanup removes its network, storage and containers only once that container has stopped or restarted. Cleanup also no longer disconnects the model proxy from a network a sandbox is attached to.

**Operators:** automatic cleanup now keeps anything whose owner it cannot establish. When the application cannot identify its own container (no `HOSTNAME`, as when it runs outside Docker, or a customised hostname) it logs a warning, and conversation sandboxes it starts are still removed when a conversation ends normally, but not automatically if a crash leaves them behind; until you remove such a leftover network, that conversation cannot start a new sandbox, and the error names the network. Sandbox networks left from before this upgrade are not removed automatically either; remove unused `hephaestus-sandbox-…` networks by hand. Practice review sandboxes are cleaned up as before.
