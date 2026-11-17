---
"hephaestus": patch
---

The server leaves the virtual-thread scheduler at the JVM's default ceiling. On Java 25, a request that waits for the network no longer holds a thread of its own.
