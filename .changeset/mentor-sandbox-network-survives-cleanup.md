---
"hephaestus": minor
---

Sandbox cleanup no longer removes a conversation's sandbox while the application container that started it is still running, however long the sandbox takes to start. Previously a reply from Heph that had to start a fresh sandbox could fail when cleanup, on any application container sharing the Docker host, ran before the sandbox's container existed. Each conversation sandbox now records the application container that started it, and automatic cleanup removes its network, storage and containers only once that container has stopped or restarted. Cleanup also no longer disconnects the model proxy from a network a sandbox is attached to.

**Operators:** cleanup now keeps mentor sandbox networks whose owner it cannot establish, including those from before this upgrade, for you to remove by hand; a leftover from an application that cannot identify its own container blocks its conversation until you do. See the migration guide. Practice review sandboxes are cleaned up as before.
